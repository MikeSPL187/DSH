package com.dsh

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

    com.dsh.feature.chat.AgentQuestionSheet(
        questions = parsed.map { com.dsh.feature.chat.AgentQuestion(it.id, it.text, it.detail, it.options, it.multi) },
        onSubmit = { sel, cus ->
            selection.clear(); selection.putAll(sel)
            custom.clear(); custom.putAll(cus)
            onSubmit(answersJson())
        },
        onDismiss = onDismiss,
    )
}

/**
 * Проверка плана (`exit_plan_mode`): единственный вопрос с `intent.kind = plan-review` и планом в `detail`.
 * @return null — обычный вопрос агента.
 */
fun planReviewOf(eventId: String, questions: JsonArray): com.dsh.feature.chat.PlanReviewInfo? {
    val q = questions.singleOrNull() as? JsonObject ?: return null
    val intent = q["intent"] as? JsonObject ?: return null
    if ((intent["kind"] as? JsonPrimitive)?.content != "plan-review") return null
    val plan = (q["detail"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
    val approve = (intent["approve"] as? JsonPrimitive)?.content ?: return null
    val labels = (q["options"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("label") as? JsonPrimitive)?.content }.orEmpty()
    val keep = labels.firstOrNull { it != approve } ?: return null
    return com.dsh.feature.chat.PlanReviewInfo(
        eventId = eventId,
        questionId = (q["id"] as? JsonPrimitive)?.content.orEmpty(),
        markdown = plan,
        approveLabel = approve,
        keepLabel = keep,
    )
}

/** Ответ на проверку плана: одобрить или доработать с замечаниями (свой ответ). */
fun planReviewAnswer(review: com.dsh.feature.chat.PlanReviewInfo, feedback: String?): String = buildJsonObject {
    put("answers", buildJsonArray {
        add(buildJsonObject {
            put("id", review.questionId)
            put("selected", buildJsonArray { add(JsonPrimitive(if (feedback == null) review.approveLabel else review.keepLabel)) })
            if (feedback != null) put("custom", feedback)
        })
    })
}.toString()
