package com.deepseek.dsh.core.protocol

import com.deepseek.dsh.core.model.ModelCatalogInfo
import com.deepseek.dsh.core.model.ModelItem
import com.deepseek.dsh.core.model.ModelReasoningEffort
import com.deepseek.dsh.core.model.ModelProviderGroup
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
                val root = json.parseToJsonElement(bodyStr) as? kotlinx.serialization.json.JsonObject
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

    override suspend fun openSettingsDocument(hostId: String): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("method", "settings/openSettingsDocument")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                RemoteResult.Success(response.isSuccessful)
            }
        } catch (_: Exception) {
            RemoteResult.Success(true)
        }
    }

    override suspend fun getRemoteSettings(hostId: String): RemoteResult<Map<String, String>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "settings/describe")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val jsonElem = json.parseToJsonElement(bodyStr)
                    val map = mutableMapOf<String, String>()
                    val resObj = if (jsonElem is kotlinx.serialization.json.JsonObject) {
                        jsonElem["result"]?.let { r ->
                            if (r is kotlinx.serialization.json.JsonObject) r["value"] as? kotlinx.serialization.json.JsonObject else null
                        } ?: jsonElem
                    } else null

                    val namespaces = resObj?.get("namespaces") as? kotlinx.serialization.json.JsonArray
                    namespaces?.forEach { nsItem ->
                        if (nsItem is kotlinx.serialization.json.JsonObject) {
                            val ns = nsItem["ns"]?.toString()?.trim('"') ?: return@forEach
                            val valObj = nsItem["value"]
                            if (valObj != null) {
                                map[ns] = valObj.toString()
                            }
                        }
                    }
                    if (map.isNotEmpty()) return@withContext RemoteResult.Success(map)
                }
            }
        } catch (_: Exception) {}

        RemoteResult.Success(emptyMap())
    }

    override suspend fun updateRemoteSettings(
        hostId: String,
        ns: String,
        patchJson: String
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val patchObj = runCatching { json.parseToJsonElement(patchJson) }.getOrNull() ?: buildJsonObject {}
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "settings/update")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("ns", ns)
                    put("patch", patchObj)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                RemoteResult.Success(response.isSuccessful)
            }
        } catch (_: Exception) {
            RemoteResult.Success(true)
        }
    }
}
