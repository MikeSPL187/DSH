package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.ModelCatalogInfo
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Настройки Host: каталог моделей, язык Host, выгрузка журналов сессий. */
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

    /** Включена ли выгрузка журналов сессий (`session-log-deepseek` в `settings/describe`). */
    suspend fun fetchSessionLogUploadEnabled(hostId: String): Boolean? =
        sessionLogNamespace(hostId)
            ?.let { (it["value"] as? JsonObject)?.str("enabled")?.toBooleanStrictOrNull() }

    /** Включить или выключить выгрузку журналов сессий (`settings/mutate`). */
    suspend fun setSessionLogUploadEnabled(hostId: String, enabled: Boolean): Boolean {
        val revision = sessionLogNamespace(hostId)?.str("revision")?.toIntOrNull()
        val args = buildJsonObject {
            put("ns", SESSION_LOG_NS)
            put("ops", buildJsonArray {
                add(buildJsonObject {
                    put("op", "set")
                    put("path", buildJsonArray { add(JsonPrimitive("enabled")) })
                    put("value", JsonPrimitive(enabled))
                })
            })
            if (revision != null) put("expectedRevision", revision)
        }
        return calls.ok(hostId, "settings/mutate", args)
    }

    private suspend fun sessionLogNamespace(hostId: String): JsonObject? {
        val namespaces = (calls.value(hostId, "settings/describe") as? JsonObject)?.get("namespaces") as? JsonArray
        return namespaces?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("ns") == SESSION_LOG_NS }
    }

    private companion object {
        const val SESSION_LOG_NS = "session-log-deepseek"
    }
}
