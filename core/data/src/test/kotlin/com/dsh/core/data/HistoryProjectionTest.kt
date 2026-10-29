package com.dsh.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Проекция истории не теряет намеренно повторённые сообщения (QA DSH 1.1.0: DSH-09). */
class HistoryProjectionTest {
    private fun history(vararg rows: MessageEntity): List<String> {
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, m, _ ->
            if (m.name == "getMessages") flowOf(rows.toList()) else Unit
        } as DshDao
        val scope = CoroutineScope(SupervisorJob())
        return try {
            runBlocking { DshRepository(dao, FakeHost { _, _ -> null }.client, scope).getMessages("h", "s1").first().map { it.messageId } }
        } finally {
            scope.cancel()
        }
    }

    private fun user(id: String, seq: Long, at: Long, text: String = "continue") =
        MessageEntity("h", "s1", id, "USER", seq, text, at, "done")

    @Test
    fun `two committed identical messages both stay`() {
        assertEquals(listOf("first", "second"), history(user("first", 1, 1_000), user("second", 2, 2_000)))
    }

    @Test
    fun `legacy optimistic copy next to the Host echo collapses`() {
        assertEquals(listOf("msg_1000"), history(user("msg_1000", 0, 1_000), user("host-1", 1, 1_200)))
        assertEquals(listOf("local_5"), history(user("local_5", 0, 1_000), user("host-1", 1, 1_200)))
    }

    @Test
    fun `different text or a long pause is never collapsed`() {
        assertEquals(listOf("msg_1", "host-1"), history(user("msg_1", 0, 1_000, "a"), user("host-1", 1, 1_200, "b")))
        assertEquals(listOf("msg_1", "host-1"), history(user("msg_1", 0, 1_000), user("host-1", 1, 9_000)))
    }
}
