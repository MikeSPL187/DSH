package com.dsh

import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole

/** Combine Host history with the journal without moving a recovered reply before its user message. */
internal fun mergeChatMessages(repoMessages: List<ChatMessage>, requests: List<SavedPrompt>): List<ChatMessage> {
    val echoed = mutableMapOf<String, ChatMessage>()
    val history = repoMessages.map { message ->
        val request = requests.firstOrNull {
            message.role == MessageRole.USER && it.id !in echoed &&
                (message.content == it.prompt || message.content == it.text) &&
                kotlin.math.abs(message.createdAt - it.createdAt) < 60_000
        }
        if (request != null) {
            echoed[request.id] = message
            message.copy(failed = request.status !in ChatRunJournal.ACTIVE, failureReason = request.error, retryMessageId = request.id)
        } else message
    }
    val pending = requests.filter { it.id !in echoed }.map { request ->
        ChatMessage(request.hostId, request.sessionId ?: PENDING_SESSION, request.id, MessageRole.USER, request.text,
            createdAt = request.createdAt, failed = request.status !in ChatRunJournal.ACTIVE, failureReason = request.error)
    }
    val partial = requests.filter { it.status !in ChatRunJournal.ACTIVE && it.partial.isNotBlank() }.map { request ->
        // Host acceptance happens after enqueue/session creation, sometimes seconds later.
        val afterUser = maxOf(request.createdAt, echoed[request.id]?.createdAt ?: request.createdAt)
        ChatMessage(request.hostId, request.sessionId ?: PENDING_SESSION, "partial_${request.id}", MessageRole.ASSISTANT,
            request.partial, createdAt = afterUser + 1)
    }
    return (history + pending + partial).sortedBy { it.createdAt }
}
