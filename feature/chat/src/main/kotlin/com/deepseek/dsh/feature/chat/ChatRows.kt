package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.protocol.parseHostJson
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Строка ленты чата. */
internal sealed interface ChatRow {
    data class Single(val message: ChatMessage) : ChatRow

    /** Ход работы одного запроса: шаги, готово ли, сколько длилось. */
    data class Work(val key: String, val items: List<ChatMessage>, val done: Boolean, val durationMs: Long) : ChatRow
}

/**
 * Лента чата по ходам. Ход — сообщение пользователя и следующие за ним сообщения агента
 * и инструментов. После вопроса идёт ход работы (инструменты, рассуждения, промежуточные ответы),
 * затем итоговый ответ — последнее сообщение ассистента с содержимым. Рассуждение итогового ответа
 * (до `\u0001`) уходит в ход работы.
 *
 * Ход закрыт («Готово»), если после него есть следующий вопрос, если уже есть ответ (статус
 * running мог ещё не смениться) или если агент не работает и нет идущих инструментов.
 * У идущего запроса ход работы есть всегда — там «Думаю».
 */
internal fun buildChatRows(messages: List<ChatMessage>, isThinking: Boolean): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()

    fun appendTurn(user: ChatMessage?, turn: List<ChatMessage>, closed: Boolean) {
        if (user != null) rows.add(ChatRow.Single(user))
        val workAt = rows.size
        val process = mutableListOf<ChatMessage>()
        var finalAnswer: ChatMessage? = null
        val finalIndex = turn.indexOfLast { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
        turn.forEachIndexed { index, message ->
            when {
                message.role == MessageRole.TOOL -> process.add(message)
                message.role == MessageRole.ASSISTANT && index == finalIndex -> {
                    val sep = message.content.indexOf('\u0001')
                    if (sep < 0) {
                        finalAnswer = message
                    } else {
                        val reasoning = message.content.substring(0, sep)
                        val answer = message.content.substring(sep + 1)
                        if (reasoning.isNotBlank()) {
                            process.add(message.copy(messageId = message.messageId + "_r", content = reasoning + '\u0001'))
                        }
                        if (answer.isNotBlank()) finalAnswer = message.copy(messageId = message.messageId + "_a", content = answer)
                    }
                }
                message.role == MessageRole.ASSISTANT -> process.add(message)
                else -> rows.add(ChatRow.Single(message))
            }
        }
        if (process.isNotEmpty() || (user != null && !closed)) {
            val start = user?.createdAt ?: process.first().createdAt
            val lastCreated = turn.maxOfOrNull { it.createdAt } ?: start
            rows.add(
                workAt,
                ChatRow.Work(
                    key = user?.messageId ?: process.first().messageId,
                    items = process.toList(),
                    done = closed || user == null,
                    durationMs = (lastCreated - start).coerceAtLeast(0L),
                ),
            )
        }
        finalAnswer?.let { rows.add(ChatRow.Single(it)) }
    }

    var index = 0
    while (index < messages.size) {
        val user = messages[index].takeIf { it.role == MessageRole.USER }
        var end = index + 1
        while (end < messages.size && messages[end].role != MessageRole.USER) end++
        val turn = messages.subList(index + 1, end)
        val running = turn.any { parseTool(it)?.status == "running" }
        val answered = turn.any { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
        val closed = user != null && (end < messages.size || answered || (!isThinking && !running))
        appendTurn(user, turn, closed)
        index = end
    }
    return rows
}

/** Подробности вызова инструмента для листа «Подробнее». */
data class ToolDetailData(
    val callId: String,
    val name: String,
    val status: String,
    val args: String,
    val body: String,
)

/** Строка-описание шага: команда, файл, шаблон поиска…; нет — первая строка ответа готового инструмента. */
internal fun toolSummary(name: String, args: String, body: String, status: String): String {
    val obj = runCatching { lenientJson.parseHostJson(args) as? JsonObject }.getOrNull()
    fun pick(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> obj?.string(key)?.takeIf { it.isNotBlank() } }.orEmpty()
    val summary = when {
        name.contains("bash") || name.contains("shell") -> pick("command", "description")
        name.contains("read") || name.contains("write") || name.contains("edit") -> pick("file_path", "path")
        name.contains("grep") || name.contains("glob") -> pick("pattern", "query", "path")
        name.contains("ask_user_question") -> pick("question", "header")
        name.contains("goal") -> pick("objective", "title")
        name.contains("subagent") -> pick("description", "prompt")
        else -> pick("description", "command", "path", "pattern", "query")
    }
    if (summary.isNotBlank()) return summary
    if (status != "running" && body.isNotBlank()) return body.lineSequence().firstOrNull()?.take(120).orEmpty()
    return ""
}

internal data class AskOption(val label: String, val description: String)
internal data class AskQuestion(val id: String, val question: String, val header: String, val options: List<AskOption>)

/** Вопросы агента из аргументов `ask_user_question`; без id или текста вопрос пропускается. */
internal fun parseAskQuestions(raw: String): List<AskQuestion> {
    val root = runCatching { lenientJson.parseHostJson(raw) as? JsonObject }.getOrNull() ?: return emptyList()
    val questions = root["questions"] as? JsonArray ?: return emptyList()
    return questions.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id").orEmpty()
        val question = obj.string("question").orEmpty()
        if (id.isBlank() || question.isBlank()) return@mapNotNull null
        val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            (option as? JsonObject)?.let { AskOption(it.string("label").orEmpty(), it.string("description").orEmpty()) }
        }
        AskQuestion(id, question, obj.string("header").orEmpty(), options)
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
