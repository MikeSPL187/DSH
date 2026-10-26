package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Разобранный вопрос. */
private data class ParsedQuestion(
    val id: String,
    val text: String,
    val detail: String?,
    val options: List<String>,
    val multi: Boolean,
)

/**
 * Нативное окно ответа на `user-questions/request`.
 *
 * Ответ уходит в Host как `{answers:[{id,selected:[label],custom?}]}` —
 * формат `AskUserQuestionAnswer`.
 */
@Composable
fun UserQuestionDialog(
    questions: JsonArray,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(questions) {
        questions.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ParsedQuestion(
                id = (obj["id"] as? JsonPrimitive)?.content.orEmpty(),
                text = (obj["question"] as? JsonPrimitive)?.content.orEmpty(),
                detail = (obj["detail"] as? JsonPrimitive)?.content,
                options = (obj["options"] as? JsonArray)?.mapNotNull { option ->
                    val label = (option as? JsonObject)?.get("label") as? JsonPrimitive
                    label?.content
                } ?: emptyList(),
                multi = obj["multiSelect"]?.toString() == "true",
            )
        }
    }
    val selection = remember(questions) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(questions) { mutableStateMapOf<String, String>() }
    val ready = parsed.isNotEmpty() && parsed.all { question ->
        !selection[question.id].isNullOrEmpty() || !custom[question.id].isNullOrBlank()
    }

    fun answersJson(): String = buildJsonObject {
        put("answers", buildJsonArray {
            parsed.forEach { question ->
                add(buildJsonObject {
                    put("id", question.id)
                    put("selected", buildJsonArray {
                        selection[question.id].orEmpty().forEach { add(JsonPrimitive(it)) }
                    })
                    custom[question.id]?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                })
            }
        })
    }.toString()

    com.deepseek.dsh.feature.chat.AgentQuestionSheet(
        questions = parsed.map { com.deepseek.dsh.feature.chat.AgentQuestion(it.id, it.text, it.detail, it.options, it.multi) },
        onSubmit = { sel, cus ->
            selection.clear(); selection.putAll(sel)
            custom.clear(); custom.putAll(cus)
            onSubmit(answersJson())
        },
        onDismiss = onDismiss,
    )
}
