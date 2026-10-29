package com.dsh.core.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.model.SessionInfo
import com.dsh.core.protocol.DshClientProtocol
import com.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Закрепление и архив от Host доходят до строки Room при любом порядке кадров (R2-03):
 * baseline workspace/follow раньше строки сессии и строка раньше baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionFlagsRoomTest {
    private fun flagsAfterSync(baselineFirst: Boolean): List<Int> = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), DshDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.dshDao()
        val frame = """{"type":"baseline","value":{"items":[],"pinnedSessionIds":["s1"],"archivedSessionIds":["s1"]}}"""
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, _ ->
            when (method.name) {
                "workspaceFollow" -> flow { emit(frame); awaitCancellation() }
                "listSessions" -> RemoteResult.Success(listOf(SessionInfo(hostId = "h", sessionId = "s1", title = "Remote session")))
                else -> emptyFlow<Any>()
            }
        } as DshClientProtocol
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = DshRepository(dao, client, scope)
            if (!baselineFirst) repository.refreshSessions("h")
            repository.subscribeRemoteEvents("h")
            // После baseline список сессий перечитывается через 250 мс.
            val row = withTimeout(5_000) {
                dao.getSessionsForHost("h").first { rows -> rows.singleOrNull()?.let { it.pinned == 1 && it.archived == 1 } == true }
            }.single()
            listOf(row.pinned, row.archived)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    @Test
    fun `fresh cache keeps Host flags when the baseline arrives before the session row`() {
        assertEquals(listOf(1, 1), flagsAfterSync(baselineFirst = true))
    }

    @Test
    fun `existing cache row receives Host flags from a later baseline`() {
        assertEquals(listOf(1, 1), flagsAfterSync(baselineFirst = false))
    }
}
