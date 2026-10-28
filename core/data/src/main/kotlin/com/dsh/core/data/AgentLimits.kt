package com.dsh.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Пределы агента в настройках Host. Пространство ищется по полям, а не по имени: id записи профиля
 * (`bash-local`, `bash-sandbox`, свой id в `cordis.yml`) у разных сборок разный, а набор полей — нет.
 * [default] — значение по умолчанию в исходниках плагина, на случай если Host не отдал `base`.
 */
enum class AgentLimit(val field: String, internal val marker: Set<String>, val default: Long, val web: Boolean = false) {
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
    /** Результатов на один запрос Search API (1–50); работает, когда включён Search API. */
    WEB_RESULTS("count", setOf("sendOriginalQuestion"), 10, web = true),
    /** Поисков вспомогательной модели за один запрос Messages; работает, когда Search API выключен. */
    WEB_SEARCHES("maxUses", setOf("maxTokens", "apiVersion"), 5, web = true),
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
            ceiling = when (limit) {
                AgentLimit.COMMAND_TIME -> value.long("maxTimeoutMs")
                AgentLimit.WEB_RESULTS -> WEB_RESULTS_MAX
                else -> null
            },
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

/** Search API отдаёт не больше 50 результатов на запрос (схема `web-deepseek-api`). */
private const val WEB_RESULTS_MAX = 50L

/**
 * Веб-поиск Host. [useApi] — поиск и чтение страниц идут через Search и Fetch API DeepSeek, иначе — поиск через
 * Messages и чтение страниц с телефона. [sendQuestion] — Search API получает начало вопроса пользователя
 * (`sendOriginalQuestion`); null — Host этого не умеет. [apiAvailable] — в Host есть и выбор поставщика, и Search API.
 */
data class WebSearchState(
    val apiAvailable: Boolean,
    val useApi: Boolean,
    val sendQuestion: Boolean?,
    internal val web: SettingsDoc?,
    internal val api: SettingsDoc?,
)

/** Пространство настроек: имя, ревизия, значение и стандартное значение. */
data class SettingsDoc(val ns: String, val revision: Int?, val value: JsonObject, val base: JsonObject)

/** Поставщики, которые выбирает переключатель Search API (как в `ui-settings-web-search`). */
private val WEB_PROVIDERS = mapOf(
    "searchProvider" to ("deepseek-search-api" to "deepseek-official"),
    "fetchProvider" to ("deepseek-fetch-api" to "http"),
)

private fun doc(obj: JsonObject): SettingsDoc? {
    val ns = obj.str("ns") ?: return null
    val value = obj["value"] as? JsonObject ?: return null
    return SettingsDoc(ns, obj.str("revision")?.toIntOrNull(), value, obj["base"] as? JsonObject ?: JsonObject(emptyMap()))
}

/** Веб-поиск из ответа `settings/describe`; пространства ищутся по полям, как пределы. */
internal fun parseWebSearch(describe: JsonElement?): WebSearchState {
    val namespaces = ((describe as? JsonObject)?.get("namespaces") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val web = namespaces.firstOrNull { (it["value"] as? JsonObject)?.containsKey("searchProvider") == true }?.let(::doc)
    val api = namespaces.firstOrNull { (it["value"] as? JsonObject)?.let { v -> "count" in v && "sendOriginalQuestion" in v } == true }?.let(::doc)
    val provider = (web?.value?.get("searchProvider") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return WebSearchState(
        apiAvailable = web != null && api != null,
        useApi = provider == WEB_PROVIDERS.getValue("searchProvider").first,
        sendQuestion = (api?.value?.get("sendOriginalQuestion") as? JsonPrimitive)?.booleanOrNull,
        web = web,
        api = api,
    )
}

/**
 * Переключить Search API: оба поставщика (поиск и чтение страниц) разом. Значение, равное стандартному
 * для этой сборки, не записывается копией, а снимается (`unset`) — тогда выбор снова следует за Host.
 */
internal fun webApiMutation(state: WebSearchState, useApi: Boolean): JsonObject? {
    val web = state.web ?: return null
    return buildJsonObject {
        put("ns", web.ns)
        put("ops", buildJsonArray {
            WEB_PROVIDERS.forEach { (field, ids) ->
                val target = if (useApi) ids.first else ids.second
                val base = (web.base[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                add(buildJsonObject {
                    put("op", if (target == base) "unset" else "set")
                    put("path", buildJsonArray { add(JsonPrimitive(field)) })
                    if (target != base) put("value", target)
                })
            }
        })
        web.revision?.let { put("expectedRevision", it) }
    }
}

/** Передавать ли Search API начало вопроса; совпадение со стандартным — `unset`. */
internal fun sendQuestionMutation(state: WebSearchState, send: Boolean): JsonObject? {
    val api = state.api ?: return null
    val base = (api.base["sendOriginalQuestion"] as? JsonPrimitive)?.booleanOrNull ?: true
    return buildJsonObject {
        put("ns", api.ns)
        put("ops", buildJsonArray {
            add(buildJsonObject {
                put("op", if (send == base) "unset" else "set")
                put("path", buildJsonArray { add(JsonPrimitive("sendOriginalQuestion")) })
                if (send != base) put("value", send)
            })
        })
        api.revision?.let { put("expectedRevision", it) }
    }
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
