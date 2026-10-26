package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.deepseek.dsh.core.data.DshRepository
import kotlinx.coroutines.launch

/**
 * Режимы инструментов из «Плагинов» («Сразу / Спросить / Выкл.») для запросов агента
 * и журнал вызовов инструментов. Удаление и опасные команды спрашиваются всегда.
 */
@Composable
internal fun ToolPolicyEffect(repository: DshRepository) {
    LaunchedEffect(repository) {
        repository.approvals.approvalPolicy = { w ->
            val tool = (w.request["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val args = toolArgsText(w.request["args"] ?: w.request["arguments"])
            when {
                tool == null -> null
                // Удаление и опасные команды всегда спрашиваются, какой бы ни был режим.
                com.deepseek.dsh.feature.chat.approvalTier(tool, args) == com.deepseek.dsh.feature.chat.ApprovalTier.DESTRUCTIVE -> null
                else -> when (com.deepseek.dsh.feature.settings.logic.ToolPolicy.mode(tool)) { "auto" -> true; "off" -> false; else -> null }
            }
        }
        launch {
            repository.approvals.approvalOutcomes.collect { (w, approved, byUser) ->
                val tool = (w.request["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@collect
                com.deepseek.dsh.feature.settings.logic.ToolCallLog.approval(tool, approved, byUser)
            }
        }
        repository.doneMessages.collect { m ->
            if (m.role == com.deepseek.dsh.core.model.MessageRole.TOOL) com.deepseek.dsh.feature.settings.logic.ToolCallLog.ingest(m.content, m.createdAt)
        }
    }
}

/** Аргументы вызова как JSON-текст; если Host прислал их строкой — сама строка, без экранирования. */
internal fun toolArgsText(element: kotlinx.serialization.json.JsonElement?): String? = when {
    element == null || element is kotlinx.serialization.json.JsonNull -> null
    element is kotlinx.serialization.json.JsonPrimitive && element.isString -> element.content
    else -> element.toString()
}
