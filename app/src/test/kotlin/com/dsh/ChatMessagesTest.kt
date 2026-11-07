package com.dsh

import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.core.model.RunStatus
import org.junit.Assert.*
import org.junit.Test

class ChatMessagesTest {
    private fun request(id: String, at: Long, partial: String) = SavedPrompt(
        "same prompt", "same prompt", emptyList(), ModelChoice("m", "p"), id = id,
        sessionId = "s", createdAt = at, status = RunStatus.INTERRUPTED,
        error = "Interrupted", partial = partial)

    @Test fun recoveredPartialFollowsTheHostEchoEvenWhenSessionCreationDelayedAcceptance() {
        val echo = ChatMessage("local-host", "s", "host-user", MessageRole.USER, "same prompt", createdAt = 4_000)
        val rows = mergeChatMessages(listOf(echo), listOf(request("r1", 1_000, "partial answer")))
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), rows.map { it.role })
        assertEquals("partial answer", rows.last().content)
        assertTrue(rows.last().createdAt > echo.createdAt)
        assertEquals("r1", rows.first().retryMessageId)
        assertEquals("Interrupted", rows.first().failureReason)
    }

    @Test fun repeatedQueuedPromptsKeepTheirOwnRecoveryAndDoNotDuplicateHostEchoes() {
        val echoes = listOf(3_000L, 4_000L).mapIndexed { i, at ->
            ChatMessage("local-host", "s", "host-$i", MessageRole.USER, "same prompt", createdAt = at)
        }
        val rows = mergeChatMessages(echoes, listOf(request("r1", 1_000, "first partial"), request("r2", 2_000, "second partial")))
        assertEquals(listOf("same prompt", "first partial", "same prompt", "second partial"), rows.map { it.content })
        assertEquals(listOf("r1", "r2"), rows.filter { it.role == MessageRole.USER }.map { it.retryMessageId })
    }
}
