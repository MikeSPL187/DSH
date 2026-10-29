package com.dsh.core.data

import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.protocol.DshClientProtocol
import com.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Настройки Host: документы настроек (`settings/...`), провайдеры моделей и их ключи,
 * каталог моделей, режимы доступа, язык Host, выгрузка журналов сессий.
 */
class HostSettingsRepository internal constructor(
    private val client: DshClientProtocol,
    private val calls: HostCalls,
) {
    private val _modelCatalogFlow =
        MutableStateFlow(ModelCatalogInfo(routableProviders = emptyList(), groups = emptyList(), defaultModel = ""))
    val modelCatalogFlow: StateFlow<ModelCatalogInfo> = _modelCatalogFlow.asStateFlow()

    suspend fun refreshModelCatalog(hostId: String) {
        (client.getModelCatalog(hostId) as? RemoteResult.Success)?.data?.let { _modelCatalogFlow.value = it }
    }

    // ── Документы настроек ───────────────────────────────────────────────────

    /** Ответ `settings/describe` целиком — для разборщиков, которым нужен весь документ. */
    suspend fun describeSettingsRaw(hostId: String): String? = calls.rpc(hostId, "settings/describe").getOrNull()

    /** Одно пространство настроек из `settings/describe`; null — нет такого или Host не ответил. */
    suspend fun namespace(hostId: String, ns: String): SettingsNamespace? {
        val namespaces = (calls.value(hostId, "settings/describe") as? JsonObject)?.get("namespaces") as? JsonArray
        val obj = namespaces?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("ns") == ns } ?: return null
        return SettingsNamespace(
            value = obj["value"] as? JsonObject ?: JsonObject(emptyMap()),
            revision = obj.str("revision")?.toIntOrNull(),
        )
    }

    /**
     * Записать поля пространства одной операцией `settings/mutate`.
     * @param values путь поля → новое значение.
     * @param expectedRevision ревизия, по которой читали; Host отклонит запись поверх чужих правок. null — без проверки.
     */
    suspend fun setSettings(
        hostId: String,
        ns: String,
        values: List<Pair<List<String>, JsonElement>>,
        expectedRevision: Int?,
    ): Boolean {
        val args = buildJsonObject {
            put("ns", ns)
            put("ops", buildJsonArray {
                values.forEach { (path, value) ->
                    add(buildJsonObject {
                        put("op", "set")
                        put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
                        put("value", value)
                    })
                }
            })
            // Контракт Host: число или отсутствие поля. JSON null Host отклоняет (gateway/input-invalid),
            // поэтому запись «без проверки ревизии» не отправляет поле вовсе (DSH-04).
            expectedRevision?.let { put("expectedRevision", it) }
        }
        return calls.ok(hostId, "settings/mutate", args)
    }

    /** Убрать значения по путям [paths] пространства [ns] (`unset`); false — Host не принял. */
    suspend fun unsetSettings(hostId: String, ns: String, paths: List<List<String>>): Boolean {
        val args = buildJsonObject {
            put("ns", ns)
            put("ops", buildJsonArray {
                paths.forEach { path ->
                    add(buildJsonObject {
                        put("op", "unset")
                        put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        return calls.ok(hostId, "settings/mutate", args)
    }

    // ── Пределы агента ───────────────────────────────────────────────────────

    /**
     * Записать предел: [newValue] — новое значение, null — вернуть стандартное (`unset`).
     * Пишется по ревизии [current]: если настройки успели измениться, Host откажет — false.
     */
    suspend fun setAgentLimit(hostId: String, current: AgentLimitValue, newValue: Long?): Boolean =
        calls.ok(hostId, "settings/mutate", agentLimitMutation(current, newValue))

    /** Пределы агента и веб-поиск одним чтением `settings/describe`. */
    suspend fun agentSettings(hostId: String): Pair<List<AgentLimitValue>, WebSearchState> {
        val describe = calls.value(hostId, "settings/describe")
        return parseAgentLimits(describe) to parseWebSearch(describe)
    }

    /** Включить или выключить Search API (поиск и чтение страниц вместе); false — Host не принял. */
    suspend fun setWebSearchApi(hostId: String, state: WebSearchState, useApi: Boolean): Boolean =
        webApiMutation(state, useApi)?.let { calls.ok(hostId, "settings/mutate", it) } ?: false

    /** Передавать ли поисковику начало вопроса пользователя; false — Host не принял. */
    suspend fun setWebSendQuestion(hostId: String, state: WebSearchState, send: Boolean): Boolean =
        sendQuestionMutation(state, send)?.let { calls.ok(hostId, "settings/mutate", it) } ?: false

    // ── Провайдеры моделей ───────────────────────────────────────────────────

    /** Ответ `llm/listConfigurableProviders` целиком (каталог провайдеров, которые можно настроить). */
    suspend fun configurableProvidersRaw(hostId: String): String? =
        calls.rpc(hostId, "llm/listConfigurableProviders").getOrNull()

    /** id провайдеров, уже зарегистрированных в Host (`llm/listProviders`). */
    suspend fun registeredProviderIds(hostId: String): List<String> =
        (calls.value(hostId, "llm/listProviders") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.str("id") }.orEmpty()

    /** Ответ `credentials/describe` по ссылкам [refs] целиком; значения ключей в нём нет никогда. */
    suspend fun describeCredentialsRaw(hostId: String, refs: List<String>): String? {
        val args = buildJsonObject { put("refs", buildJsonArray { refs.forEach { add(JsonPrimitive(it)) } }) }
        return calls.rpc(hostId, "credentials/describe", args).getOrNull()
    }

    // ── Прочее ───────────────────────────────────────────────────────────────

    /** Режимы доступа Host (`permissionPresets/catalog`): значение → название. */
    suspend fun permissionPresets(hostId: String): List<Pair<String, String>> =
        ((calls.value(hostId, "permissionPresets/catalog") as? JsonObject)?.get("options") as? JsonArray)
            ?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val value = obj.str("value") ?: return@mapNotNull null
                value to (obj.str("name") ?: value)
            }.orEmpty()

    /**
     * Язык текстов Host (описания команд, расписания, страницы плагинов). Host знает
     * только zh и en, приложение — ru и en, поэтому Host всегда на английском.
     */
    suspend fun syncHostLocale(hostId: String) {
        val args = buildJsonObject {
            put("ns", "locale")
            put("patch", buildJsonObject { put("preference", "en") })
        }
        calls.rpc(hostId, "settings/update", args)
    }

    /** Включена ли выгрузка журналов сессий (`session-log-deepseek`). */
    suspend fun fetchSessionLogUploadEnabled(hostId: String): Boolean? =
        namespace(hostId, SESSION_LOG_NS)?.value?.str("enabled")?.toBooleanStrictOrNull()

    /** Включить или выключить выгрузку журналов сессий. */
    suspend fun setSessionLogUploadEnabled(hostId: String, enabled: Boolean): Boolean =
        setSettings(
            hostId,
            SESSION_LOG_NS,
            listOf(listOf("enabled") to JsonPrimitive(enabled)),
            namespace(hostId, SESSION_LOG_NS)?.revision,
        )

    private companion object {
        const val SESSION_LOG_NS = "session-log-deepseek"
    }
}

/** Пространство настроек Host: значение и ревизия (для записи без затирания чужих правок). */
data class SettingsNamespace(val value: JsonObject, val revision: Int?)
