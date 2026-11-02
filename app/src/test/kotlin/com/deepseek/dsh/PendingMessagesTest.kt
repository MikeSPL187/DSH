package com.deepseek.dsh

import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingMessagesTest {
    private fun m(id: String, session: String) = ChatMessage("local-host", session, id, MessageRole.ASSISTANT, "⚠️ x")

    @Test
    fun `send error from a new chat does not leak into other sessions`() {
        val pending = listOf(m("local_error_1", PENDING_SESSION), m("local_2", "s1"))
        // Вернулись в существующую сессию s2 — ни ошибки нового чата, ни чужого пузыря.
        assertEquals(emptyList<ChatMessage>(), pendingForSession(pending, "s2"))
        // В своей сессии виден свой пузырь.
        assertEquals(listOf("local_2"), pendingForSession(pending, "s1").map { it.messageId })
        // В новом чате — его ошибка.
        assertEquals(listOf("local_error_1"), pendingForSession(pending, null).map { it.messageId })
    }
}
