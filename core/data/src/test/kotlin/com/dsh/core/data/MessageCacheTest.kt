package com.dsh.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** G-17: кэш сообщений ограничен — окно чтения, предел хранения, сообщения удалённых сессий, вывод инструментов. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MessageCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, DshDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.dshDao()

    private fun msg(session: String, i: Int) =
        MessageEntity("h", session, "m$i", "ASSISTANT", i.toLong(), "текст $i", i.toLong(), "done")

    @Test
    fun readWindowAndTrimKeepTheNewest() = runBlocking {
        dao.insertSessions(listOf(SessionEntity("h", "s1", "S", null, null, 1, 0)))
        dao.insertMessages((1..50).map { msg("s1", it) })
        val window = dao.getMessages("h", "s1", limit = 10).first()
        assertEquals((41..50).map { "m$it" }, window.map { it.messageId })

        dao.trimSessionMessages("h", "s1", keep = 20)
        assertEquals((31..50).map { "m$it" }, dao.getMessages("h", "s1", limit = 100).first().map { it.messageId })
        assertEquals(emptyList<SessionKey>(), dao.sessionsWithMoreMessagesThan(20))
    }

    @Test
    fun messagesOfMissingSessionsAreRemoved() = runBlocking {
        dao.insertSessions(listOf(SessionEntity("h", "kept", "S", null, null, 1, 0)))
        dao.insertMessages(listOf(msg("kept", 1), msg("gone", 2)))
        assertEquals(1, dao.deleteOrphanMessages())
        assertEquals(1, dao.getMessages("h", "kept").first().size)
    }

    @Test
    fun hugeToolOutputIsTruncatedInTheCache() {
        val body = "x".repeat(5_000_000)
        val tool = ChatMessage("h", "s", "t1", MessageRole.TOOL, "\u0002TOOL\u0002t1\u0002bash\u0002ok\u0002$body\u0002{\"command\":\"cat big\"}")
        val stored = storedContent(tool)
        assertTrue(stored.length < MAX_TOOL_BODY + 200)
        assertTrue(stored.endsWith("{\"command\":\"cat big\"}"))
        val answer = ChatMessage("h", "s", "a1", MessageRole.ASSISTANT, "ok")
        assertEquals("ok", storedContent(answer))
    }
}
