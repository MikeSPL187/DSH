package com.dsh.feature.chat

import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Разговор для «Поделиться»: Markdown — заголовок, строка «DSH · дата · папка», реплики «Вы» / «DSH» и (по
 * желанию) шаги агента цитатой: команда, файл или запрос одной строкой. Секреты скрыты всегда ([SecretMask]):
 * видны только последние 4 символа.
 */
object SessionTranscript {
    /** Подписи на языке интерфейса. */
    data class Labels(val you: String, val agent: String, val failed: String)

    /** Сколько в разговоре реплик и шагов агента. */
    data class Counts(val messages: Int, val steps: Int)

    fun counts(messages: List<ChatMessage>): Counts = Counts(
        messages.count { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() },
        messages.count { it.role == MessageRole.TOOL && parseTool(it) != null },
    )

    fun markdown(title: String, header: String, messages: List<ChatMessage>, withSteps: Boolean, labels: Labels): String = buildString {
        append("# ").append(title.trim().ifEmpty { "DSH" }).append('\n')
        append('_').append(header).append("_\n")
        var quoting = false
        for (m in messages.sortedWith(compareBy<ChatMessage> { it.seq }.thenBy { it.createdAt })) {
            when (m.role) {
                MessageRole.USER, MessageRole.ASSISTANT -> {
                    val text = m.content.trim().takeIf { it.isNotEmpty() } ?: continue
                    append('\n')
                    quoting = false
                    append("**").append(if (m.role == MessageRole.USER) labels.you else labels.agent).append(":** ")
                    append(SecretMask.mask(text)).append('\n')
                }
                MessageRole.TOOL -> {
                    if (!withSteps) continue
                    val t = parseTool(m) ?: continue
                    if (!quoting) { append('\n'); quoting = true }
                    append("> ").append(SecretMask.mask(stepLine(t, labels))).append('\n')
                }
                MessageRole.SYSTEM -> Unit
            }
        }
    }

    /** Шаг одной строкой: имя инструмента и главный аргумент (команда, путь, запрос), ошибка — пометкой. */
    internal fun stepLine(t: ToolMsg, labels: Labels): String {
        val args = runCatching { Json.parseToJsonElement(t.args) as? JsonObject }.getOrNull()
        val main = args?.let { a ->
            KEYS.firstNotNullOfOrNull { k -> a[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() } }
        }?.lineSequence()?.first()?.let { if (it.length > 120) it.take(119) + "…" else it }
        val failed = t.status.equals("error", true) || t.status.equals("failed", true)
        return buildString {
            append('`').append(t.name).append('`')
            if (main != null) append(" ").append(main.replace('`', '\''))
            if (failed) append(" · ").append(labels.failed)
        }
    }

    private val KEYS = listOf("command", "cmd", "file_path", "filePath", "path", "pattern", "query", "url", "description")
}
