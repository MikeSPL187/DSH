package com.dsh.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Пределы агента в настройках Host. Пространство ищется по полям, а не по имени: id записи профиля
 * (`bash-local`, `bash-sandbox`, свой id в `cordis.yml`) у разных сборок разный, а набор полей — нет.
 * [default] — значение по умолчанию в исходниках плагина, на случай если Host не отдал `base`.
 */
enum class AgentLimit(val field: String, internal val marker: Set<String>, val default: Long) {
    /** Время одной команды shell, мс; Host обрезает его сверху своим `maxTimeoutMs`. */
    COMMAND_TIME("timeoutMs", setOf("maxTimeoutMs", "maxOutputBytes"), 120_000),
    /** Вывод одного потока команды в памяти, байты; остальное уходит во временный файл. */
    COMMAND_OUTPUT("maxOutputBytes", setOf("maxTimeoutMs", "timeoutMs"), 64_000),
    /** Глубина вложенности субагентов по умолчанию; 0 — без субагентов. */
    SUBAGENT_DEPTH("maxDepth", setOf("maxActiveSubagents"), 1),
    /** Живых субагентов одновременно. */
    SUBAGENT_ACTIVE("maxActiveSubagents", setOf("maxDepth"), 8),
    /** Безопасных вызовов инструментов за шаг одновременно. */
    PARALLEL_CALLS("maxParallelToolCalls", emptySet(), 10),
}

/**
 * Текущее значение предела. [user] — задано пользователем (есть в `user` пространства), то есть «изменено».
 * [ceiling] — верхняя граница от Host, если она есть (для времени команды — `maxTimeoutMs`).
 */
data class AgentLimitValue(
    val limit: AgentLimit,
    val ns: String,
    val revision: Int?,
    val value: Long,
    val base: Long,
    val user: Boolean,
    val ceiling: Long? = null,
)

/** Пределы из ответа `settings/describe`; пределы, которых в Host нет, пропускаются. */
internal fun parseAgentLimits(describe: JsonElement?): List<AgentLimitValue> {
    val namespaces = ((describe as? JsonObject)?.get("namespaces") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    return AgentLimit.entries.mapNotNull { limit ->
        val candidates = namespaces.filter { ns ->
            val value = ns["value"] as? JsonObject ?: return@filter false
            value.long(limit.field) != null && limit.marker.all { it in value }
        }
        // Если shell несколько (bash и pwsh), на телефоне работает bash.
        val obj = candidates.firstOrNull { it.str("ns")?.contains("bash") == true } ?: candidates.firstOrNull() ?: return@mapNotNull null
        val value = obj["value"] as JsonObject
        AgentLimitValue(
            limit = limit,
            ns = obj.str("ns") ?: return@mapNotNull null,
            revision = obj.str("revision")?.toIntOrNull(),
            value = value.long(limit.field)!!,
            base = (obj["base"] as? JsonObject)?.long(limit.field) ?: limit.default,
            user = (obj["user"] as? JsonObject)?.containsKey(limit.field) == true,
            ceiling = if (limit == AgentLimit.COMMAND_TIME) value.long("maxTimeoutMs") else null,
        )
    }
}

/** `settings/mutate` для одного предела: `set` нового значения или `unset` (вернуть стандартное). */
internal fun agentLimitMutation(current: AgentLimitValue, newValue: Long?): JsonObject = buildJsonObject {
    put("ns", current.ns)
    put("ops", buildJsonArray {
        add(buildJsonObject {
            put("op", if (newValue == null) "unset" else "set")
            put("path", buildJsonArray { add(JsonPrimitive(current.limit.field)) })
            if (newValue != null) put("value", newValue)
        })
    })
    current.revision?.let { put("expectedRevision", it) }
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
