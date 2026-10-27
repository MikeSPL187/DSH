package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.dsh.core.data.DshRepository
import kotlinx.coroutines.launch

/**
 * Режимы инструментов из «Плагинов» («Сразу / Спросить / Выкл.») для запросов агента
 * и журнал вызовов инструментов. Удаление и опасные команды спрашиваются всегда.
 */
@Composable
internal fun ToolPolicyEffect(repository: DshRepository) {
    LaunchedEffect(repository) {
        launch {
            repository.approvals.approvalOutcomes.collect { (w, approved, byUser) ->
                val tool = (w.request["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@collect
                com.dsh.feature.settings.logic.ToolCallLog.approval(tool, approved, byUser)
            }
        }
        repository.doneMessages.collect { m ->
            if (m.role == com.dsh.core.model.MessageRole.TOOL) com.dsh.feature.settings.logic.ToolCallLog.ingest(m.content, m.createdAt)
        }
    }
}

/**
 * Ответ на запрос одобрения по режиму инструмента: true — «Сразу», false — «Выкл.», null — спросить.
 * Ставится в [DshApplication] (H-7): раньше — из эффекта экрана, и автоматизация в фоне без интерфейса
 * не отклоняла «Выкл.» и ждала 10 минут на «Сразу».
 */
internal fun toolApprovalPolicy(w: com.dsh.core.protocol.ForwardedWaterfall): Boolean? {
    val tool = (w.request["toolName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
    val args = toolArgsText(w.request["args"] ?: w.request["arguments"])
    return when {
        // Удаление и опасные команды всегда спрашиваются, какой бы ни был режим.
        com.dsh.feature.chat.approvalTier(tool, args) == com.dsh.feature.chat.ApprovalTier.DESTRUCTIVE -> null
        else -> when (com.dsh.feature.settings.logic.ToolPolicy.mode(tool)) { "auto" -> true; "off" -> false; else -> null }
    }
}

/** Аргументы вызова как JSON-текст; если Host прислал их строкой — сама строка, без экранирования. */
internal fun toolArgsText(element: kotlinx.serialization.json.JsonElement?): String? = when {
    element == null || element is kotlinx.serialization.json.JsonNull -> null
    element is kotlinx.serialization.json.JsonPrimitive && element.isString -> element.content
    else -> element.toString()
}

/**
 * Права нового чата в проекте с клонированным чужим кодом (G-50): «Только чтение», если чата ещё нет и
 * пользователь не выбрал права сам; иначе null — ничего не менять.
 */
internal fun clonedProjectPreset(cloned: Boolean, sessionId: String?, pending: String?): String? =
    if (cloned && sessionId == null && pending == null) READ_ONLY_PRESET else null

/** Пресет прав Host «Только чтение». */
internal const val READ_ONLY_PRESET = "read_only"
