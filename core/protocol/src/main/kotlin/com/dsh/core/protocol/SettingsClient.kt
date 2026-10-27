package com.dsh.core.protocol

import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.model.ModelItem
import com.dsh.core.model.ModelReasoningEffort
import com.dsh.core.model.ModelProviderGroup
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Настройки Host и каталог моделей (`settings/...`, `llm/...`). */
internal class SettingsClient(private val conn: HostConnection) : SettingsApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    override suspend fun getModelCatalog(hostId: String): RemoteResult<ModelCatalogInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/modelCatalog")
            put("payload", buildJsonObject { put("args", buildJsonObject {}) })
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/session/modelCatalog")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure("dsh/model/catalog-http", "HTTP ${response.code}")
                }
                val bodyStr = response.body?.string() ?: "{}"
                val root = json.parseHostJson(bodyStr) as? kotlinx.serialization.json.JsonObject
                val value = (root?.get("result") as? kotlinx.serialization.json.JsonObject)
                    ?.get("value") as? kotlinx.serialization.json.JsonObject
                    ?: return@withContext RemoteResult.Failure("dsh/model/catalog-malformed", "Missing catalog value")
                val groups = (value["groups"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { g ->
                    val obj = g as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val models = (obj["models"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { m ->
                        val mo = m as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = mo["id"]?.toString()?.trim('"') ?: return@mapNotNull null
                        val reasoning = mo["reasoning"] as? kotlinx.serialization.json.JsonObject
                        val efforts = (reasoning?.get("efforts") as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { e ->
                                val eo = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                val eid = eo["id"]?.toString()?.trim('"') ?: return@mapNotNull null
                                ModelReasoningEffort(
                                    id = eid,
                                    name = eo["name"]?.toString()?.trim('"') ?: eid,
                                    description = eo["description"]?.toString()?.trim('"'),
                                )
                            } ?: emptyList()
                        ModelItem(
                            id = id,
                            name = mo["name"]?.toString()?.trim('"') ?: id,
                            description = mo["description"]?.toString()?.trim('"'),
                            reasoningEfforts = efforts,
                            defaultReasoningEffort = reasoning?.get("defaultEffort")?.toString()?.trim('"'),
                        )
                    } ?: emptyList()
                    ModelProviderGroup(
                        id = obj["id"]?.toString()?.trim('"') ?: "",
                        name = obj["name"]?.toString()?.trim('"') ?: "",
                        models = models,
                    )
                } ?: emptyList()
                val default = value["default"] as? kotlinx.serialization.json.JsonObject
                val defaultModel = default?.get("model")?.toString()?.trim('"') ?: ""
                val routable = (value["routableProviders"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.toString().trim('"') } ?: emptyList()
                RemoteResult.Success(ModelCatalogInfo(routableProviders = routable, groups = groups, defaultModel = defaultModel))
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/model/catalog-error", e.message ?: "catalog failed")
        }
    }

}
