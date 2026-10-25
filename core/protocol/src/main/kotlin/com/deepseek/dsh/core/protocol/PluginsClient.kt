package com.deepseek.dsh.core.protocol

import com.deepseek.dsh.core.model.AgentPresetGroupInfo
import com.deepseek.dsh.core.model.BuiltInPluginInfo
import com.deepseek.dsh.core.model.PluginBundleItem
import com.deepseek.dsh.core.model.PluginComponentInfo
import com.deepseek.dsh.core.model.PluginRowInfo
import com.deepseek.dsh.core.model.PluginConfigItem
import com.deepseek.dsh.core.model.PluginInventorySnapshotInfo
import com.deepseek.dsh.core.model.PluginManagerSnapshot
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Плагины Host: инвентарь, менеджер, строки, установка, включение, RPC плагина. */
internal class PluginsClient(private val conn: HostConnection) : PluginsApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson
    private suspend fun rpcRaw(hostId: String, method: String, argsJson: String) = conn.rpcRaw(hostId, method, argsJson)

    override suspend fun getPluginInventorySnapshot(
        hostId: String
    ): RemoteResult<PluginInventorySnapshotInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginInventory/list")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginInventory/list")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val jsonElem = json.parseToJsonElement(bodyStr)
                    val snapshotObj = if (jsonElem is kotlinx.serialization.json.JsonObject) {
                        jsonElem["result"]?.let { res ->
                            if (res is kotlinx.serialization.json.JsonObject) {
                                res["value"] as? kotlinx.serialization.json.JsonObject
                            } else null
                        } ?: jsonElem
                    } else null

                    if (snapshotObj != null) {
                        val entriesArr = snapshotObj["entries"] as? kotlinx.serialization.json.JsonArray
                        val parsedEntries = entriesArr?.mapNotNull { item ->
                            if (item is kotlinx.serialization.json.JsonObject) {
                                val moduleName = item["moduleName"]?.toString()?.trim('"') ?: return@mapNotNull null
                                val entryId = item["entryId"]?.toString()?.trim('"')
                                val meta = item["meta"] as? kotlinx.serialization.json.JsonObject
                                val title = localizedJsonText(meta?.get("title"))
                                    ?: localizedJsonText(meta?.get("name"))
                                    ?: moduleName.substringAfterLast("/")
                                val description = localizedJsonText(meta?.get("description"))
                                    ?: com.deepseek.dsh.core.model.Tr.t("Global system plugins", "Глобальные системные плагины")
                                val enabled = item["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true
                                val phase = item["fiberPhase"]?.toString()?.trim('"') ?: "active"
                                BuiltInPluginInfo(
                                    title = title,
                                    packageName = moduleName,
                                    description = description,
                                    enabled = enabled,
                                    phase = phase,
                                    entryId = entryId
                                )
                            } else null
                        } ?: emptyList()

                        val presetsArr = snapshotObj["agentPresets"] as? kotlinx.serialization.json.JsonArray
                        val parsedPresets = presetsArr?.mapNotNull { presetItem ->
                            if (presetItem is kotlinx.serialization.json.JsonObject) {
                                val id = presetItem["id"]?.toString()?.trim('"') ?: return@mapNotNull null
                                val name = presetItem["name"]?.toString()?.trim('"') ?: id
                                val isDefault = presetItem["isDefault"]?.toString()?.toBooleanStrictOrNull() ?: false
                                val broken = presetItem["broken"]?.toString()?.trim('"')
                                val rowsArr = presetItem["rows"] as? kotlinx.serialization.json.JsonArray
                                val rows = rowsArr?.mapNotNull { rowItem ->
                                    if (rowItem is kotlinx.serialization.json.JsonObject) {
                                        val mod = rowItem["moduleName"]?.toString()?.trim('"') ?: return@mapNotNull null
                                        val eid = rowItem["entryId"]?.toString()?.trim('"')
                                        val rMeta = rowItem["meta"] as? kotlinx.serialization.json.JsonObject
                                        val title = localizedJsonText(rMeta?.get("title"))
                                            ?: localizedJsonText(rMeta?.get("name"))
                                            ?: mod.substringAfterLast("/")
                                        val desc = localizedJsonText(rMeta?.get("description"))
                                            ?: com.deepseek.dsh.core.model.Tr.t("Composed per session by the agent preset", "Собирается для каждой сессии пресетом агента")
                                        val enabled = rowItem["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true
                                        val phase = rowItem["fiberPhase"]?.toString()?.trim('"') ?: "active"
                                        val cond = rowItem["condition"]?.toString()?.trim('"')
                                        BuiltInPluginInfo(
                                            title = title,
                                            packageName = mod,
                                            description = desc,
                                            enabled = enabled,
                                            phase = phase,
                                            entryId = eid,
                                            presetId = id,
                                            condition = cond
                                        )
                                    } else null
                                } ?: emptyList()

                                AgentPresetGroupInfo(
                                    id = id,
                                    name = name,
                                    description = localizedJsonText(presetItem["description"] as? kotlinx.serialization.json.JsonObject),
                                    isDefault = isDefault,
                                    broken = broken,
                                    rows = rows
                                )
                            } else null
                        } ?: emptyList()

                        return@withContext RemoteResult.Success(
                            PluginInventorySnapshotInfo(
                                managementAvailable = snapshotObj["managementAvailable"]?.toString()?.toBooleanStrictOrNull() ?: false,
                                entries = parsedEntries,
                                agentPresets = parsedPresets
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        RemoteResult.Success(
            PluginInventorySnapshotInfo(
                managementAvailable = false,
                entries = emptyList(),
                agentPresets = emptyList()
            )
        )
    }

    override suspend fun getPluginManagerSnapshot(
        hostId: String
    ): RemoteResult<PluginManagerSnapshot> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginManager/listBundles")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginManager/listBundles")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        // Host не дал базовую конфигурацию — список пуст, ничего не придумываем.
        val basicConfigs = emptyList<PluginConfigItem>()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val jsonElem = json.parseToJsonElement(bodyStr)
                    val bundles = if (jsonElem is kotlinx.serialization.json.JsonObject) {
                        jsonElem["result"]?.let { r ->
                            if (r is kotlinx.serialization.json.JsonObject) r["value"] as? kotlinx.serialization.json.JsonArray else null
                        } ?: jsonElem["bundles"] as? kotlinx.serialization.json.JsonArray
                    } else if (jsonElem is kotlinx.serialization.json.JsonArray) jsonElem else null

                    if (bundles != null) {
                        val experimentalList = mutableListOf<PluginBundleItem>()
                        val installedList = mutableListOf<PluginBundleItem>()

                        bundles.forEach { bElem ->
                            if (bElem is kotlinx.serialization.json.JsonObject) {
                                val name = bElem["name"]?.toString()?.trim('"') ?: return@forEach
                                val meta = bElem["meta"] as? kotlinx.serialization.json.JsonObject
                                val title = localizedJsonText(meta?.get("title"))
                                    ?: localizedJsonText(meta?.get("name"))
                                    ?: localizedJsonText(bElem["title"])
                                    ?: name.substringAfterLast("/")
                                val description = localizedJsonText(meta?.get("description"))
                                    ?: localizedJsonText(bElem["description"])
                                    ?: ""
                                val enabled = bElem["enabled"]?.toString()?.toBooleanStrictOrNull() ?: false
                                val isExperimental = name.contains("experimental") ||
                                    (meta?.get("beta")?.toString()?.toBooleanStrictOrNull() ?: false)

                                val componentRows = (bElem["rows"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { row ->
                                    val rowObj = row as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                    val module = rowObj["moduleName"]?.toString()?.trim('"') ?: return@mapNotNull null
                                    val rowMeta = rowObj["meta"] as? kotlinx.serialization.json.JsonObject
                                    PluginComponentInfo(
                                        rowId = rowObj["rowId"]?.toString()?.trim('"') ?: module,
                                        moduleName = module,
                                        title = localizedJsonText(rowMeta?.get("title"))
                                            ?: localizedJsonText(rowMeta?.get("name"))
                                            ?: module.substringAfterLast("/"),
                                        description = localizedJsonText(rowMeta?.get("description")).orEmpty(),
                                        enabled = rowObj["entryId"] != null,
                                        phase = "active",
                                        entryId = rowObj["entryId"]?.toString()?.trim('"'),
                                    )
                                }.orEmpty()
                                val item = PluginBundleItem(
                                    packageName = name,
                                    title = title,
                                    description = description,
                                    isExperimental = (bElem["official"]?.toString() == "true" && bElem["optional"]?.toString() == "true"),
                                    enabled = enabled,
                                    iconKey = when {
                                        name.contains("team") -> "team"
                                        name.contains("voice") -> "voice"
                                        name.contains("auto-review") -> "shield"
                                        name.contains("inspector") -> "debug"
                                        else -> "default"
                                    },
                                    version = bElem["version"]?.toString()?.trim('"'),
                                    official = bElem["official"]?.toString() == "true",
                                    installed = bElem["installed"]?.toString() == "true",
                                    // `installation` — пришёл с установкой; `installed` — поставил пользователь;
                                    // `missing` — объявлен, но на устройстве нет. В «Установленных» — только второй вид.
                                    availability = bElem["availability"]?.toString()?.trim('"') ?: "",
                                    removable = bElem["removable"]?.toString() != "false",
                                    error = (bElem["error"] as? kotlinx.serialization.json.JsonObject)?.get("code")?.toString()?.trim('"'),
                                    rows = componentRows,
                                    iconData = localizedJsonText((bElem["meta"] as? kotlinx.serialization.json.JsonObject)?.get("icon")),
                                )
                                // В «Установленных» — только сторонние: официальные пакеты (official) и
                                // базовые profile bundle сюда не попадают — они в экспериментальных или скрыты.
                                val isOfficial = bElem["official"]?.toString() == "true"
                                val isBuiltinProfile = name in setOf(
                                    "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app",
                                    "@deepseek-ai/dsh-headless", "@deepseek-ai/dsh-sdk-app",
                                    "@deepseek-ai/dsh-acp-app", "@deepseek-ai/dsh-sdk-minimal",
                                )
                                if (isExperimental) {
                                    experimentalList.add(item)
                                } else if (!isOfficial && !isBuiltinProfile) {
                                    installedList.add(item)
                                }
                            }
                        }

                        if (experimentalList.isNotEmpty() || installedList.isNotEmpty()) {
                            return@withContext RemoteResult.Success(
                                PluginManagerSnapshot(
                                    basicConfigs = basicConfigs,
                                    experimentalBundles = experimentalList,
                                    installedBundles = installedList
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Host не дал данные менеджера плагинов — пустой снимок, без демо-плагинов.
        RemoteResult.Success(
            PluginManagerSnapshot(
                basicConfigs = emptyList(),
                experimentalBundles = emptyList(),
                installedBundles = emptyList()
            )
        )
    }

    override suspend fun getPluginRows(hostId: String): RemoteResult<List<PluginRowInfo>> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val payload = buildJsonObject {
                put("type", "client-request")
                put("rpcId", java.util.UUID.randomUUID().toString())
                put("method", "pluginManager/listPlugins")
                put("payload", buildJsonObject { put("args", buildJsonObject {}) })
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/pluginManager/listPlugins")
                .post(payload.toRequestBody(mediaTypeJson))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@withContext RemoteResult.Failure("dsh/plugin/list", "HTTP ${response.code}")
                    val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return@withContext RemoteResult.Success(emptyList())
                    val arr = (root["result"] as? kotlinx.serialization.json.JsonObject)?.get("value") as? kotlinx.serialization.json.JsonArray
                        ?: root["rows"] as? kotlinx.serialization.json.JsonArray
                        ?: return@withContext RemoteResult.Success(emptyList())
                    val rows = arr.mapNotNull { item ->
                        val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val module = obj["moduleName"]?.toString()?.trim('"') ?: return@mapNotNull null
                        val meta = obj["meta"] as? kotlinx.serialization.json.JsonObject
                        PluginRowInfo(
                            entryId = obj["entryId"]?.toString()?.trim('"'),
                            moduleName = module,
                            title = localizedJsonText(meta?.get("title")) ?: localizedJsonText(meta?.get("name")) ?: module.substringAfterLast("/"),
                            description = localizedJsonText(meta?.get("description")).orEmpty(),
                            enabled = obj["enabled"]?.toString() != "false",
                            phase = obj["phase"]?.toString()?.trim('"') ?: obj["fiberPhase"]?.toString()?.trim('"') ?: "active",
                            iconData = localizedJsonText(meta?.get("icon")),
                        )
                    }
                    RemoteResult.Success(rows)
                }
            } catch (e: Exception) {
                RemoteResult.Failure("dsh/plugin/list", e.message ?: "list failed")
            }
        }

    override suspend fun installPlugin(
        hostId: String,
        packageName: String,
        requestId: String?,
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginManager/installBundle")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("spec", packageName)
                    if (requestId != null) {
                        put("options", buildJsonObject { put("requestId", requestId) })
                    }
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginManager/installBundle")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val jsonElem = json.parseToJsonElement(bodyStr)
                    val resultObj = (jsonElem as? kotlinx.serialization.json.JsonObject)?.get("result") as? kotlinx.serialization.json.JsonObject
                    val ok = resultObj?.get("ok")?.toString()?.toBooleanStrictOrNull() ?: false
                    val valueObj = resultObj?.get("value") as? kotlinx.serialization.json.JsonObject
                    val errorObj = valueObj?.get("error") as? kotlinx.serialization.json.JsonObject
                    val application = valueObj?.get("application")?.toString()?.trim('"')
                    // Ошибка установки бывает двух видов: RPC вернул ok=false, или RPC успешен,
                    // но в ChangeResult application=failed (с error или только error.code). Оба — ошибка.
                    val failed = !ok || errorObj != null || application == "failed"
                    if (failed) {
                        val diag = errorObj?.get("diagnostic")?.toString()?.trim('"')
                            ?.takeIf { it.isNotBlank() }
                            ?: errorObj?.get("message")?.toString()?.trim('"')
                                ?.takeIf { it.isNotBlank() }
                            ?: errorObj?.get("code")?.toString()?.trim('"')
                            ?: com.deepseek.dsh.core.model.Tr.t("Installation failed", "Установка не удалась")
                        RemoteResult.Failure("dsh/plugin/install-failed", diag)
                    } else {
                        RemoteResult.Success(true)
                    }
                } else {
                    RemoteResult.Failure("dsh/plugin/install-failed", "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/plugin/install-error", e.message ?: com.deepseek.dsh.core.model.Tr.t("Network error while installing the plugin", "Сетевая ошибка при установке плагина"))
        }
    }

    override suspend fun uninstallPlugin(
        hostId: String,
        packageName: String
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginManager/removeBundle")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("name", packageName)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginManager/removeBundle")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    RemoteResult.Success(true)
                } else {
                    RemoteResult.Failure("dsh/plugin/uninstall-failed", "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/plugin/uninstall-error", e.message ?: com.deepseek.dsh.core.model.Tr.t("Network error while removing the plugin", "Сетевая ошибка при удалении плагина"))
        }
    }

    override suspend fun setBundleEnabled(
        hostId: String,
        packageName: String,
        enabled: Boolean
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginManager/setBundleEnabled")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("name", packageName)
                    put("enabled", enabled)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginManager/setBundleEnabled")
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

    override suspend fun setPluginEnabled(
        hostId: String,
        entryId: String,
        enabled: Boolean
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "pluginManager/setPluginEnabled")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("id", entryId)
                    put("enabled", enabled)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/pluginManager/setPluginEnabled")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // Для неизвестного плагина или строки только для чтения Host вернёт ok:false — это ошибка.
                val ok = response.isSuccessful &&
                    !body.contains("\"ok\":false")
                if (ok) RemoteResult.Success(true)
                else RemoteResult.Failure(
                    "dsh/plugin/set-enabled-failed",
                    body.take(300).ifBlank { "HTTP ${response.code}" },
                )
            }
        } catch (error: Exception) {
            RemoteResult.Failure("dsh/plugin/set-enabled-error", error.message ?: com.deepseek.dsh.core.model.Tr.t("Network error", "Сетевая ошибка"))
        }
    }

    override suspend fun pluginRpc(
        hostId: String,
        method: String,
        payloadJson: String,
    ): RemoteResult<String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        // A plugin route takes its own payload verbatim; unlike `rpcRaw` there is
        // no `{args}` wrapper, because the plugin reads `envelopeRequest.payload`.
        val body = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", method)
            put(
                "payload",
                runCatching { json.parseToJsonElement(payloadJson) }.getOrNull() ?: buildJsonObject {},
            )
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/$method")
            .post(body.toRequestBody(mediaTypeJson))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) RemoteResult.Success(text)
                else RemoteResult.Failure("dsh/rpc-failed", "HTTP ${response.code}: ${text.take(200)}")
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/rpc-network", e.message ?: "plugin rpc failed")
        }
    }
}

/** Текст из JSON: строка или словарь `{язык: текст}` (язык устройства, затем en). */
private fun localizedJsonText(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
    is kotlinx.serialization.json.JsonPrimitive -> element.content
    is kotlinx.serialization.json.JsonObject -> {
        listOf(java.util.Locale.getDefault().language, "en").firstNotNullOfOrNull { key ->
            (element[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    }
    else -> null
}
