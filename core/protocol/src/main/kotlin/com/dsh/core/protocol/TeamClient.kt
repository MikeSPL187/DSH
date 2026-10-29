package com.dsh.core.protocol

import com.dsh.core.model.AgentTeamMember
import com.dsh.core.model.AgentTeamSummary
import com.dsh.core.model.AgentTeamTask
import com.dsh.core.model.AutomationTaskItem
import com.dsh.core.model.TeamTaskItem
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Команда агента и задачи: команда сессии, общие задачи, автоматизации Host. */
internal class TeamClient(private val conn: HostConnection) : TeamApi {
    /** `result.value` ответа, только если Host подтвердил `ok: true`; иначе null (отказ, пустой или битый ответ). */
    private fun confirmedValue(body: String): kotlinx.serialization.json.JsonElement? {
        val result = runCatching { (json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject)?.get("result") }
            .getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        val ok = result["ok"] as? kotlinx.serialization.json.JsonPrimitive
        if (ok == null || ok.isString || ok.content != "true") return null
        return result["value"] ?: kotlinx.serialization.json.JsonNull
    }

    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    /**
     * Проекция сессии `agentTeam` — состав команды агента.
     *
     * @param hostId идентификатор Host.
     * @param sessionId id сессии.
     * @returns сводка команды; null, если команды нет (или плагин выключен).
     */
    override suspend fun getAgentTeam(
        hostId: String,
        sessionId: String,
    ): RemoteResult<AgentTeamSummary?> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/projections")
            put("payload", buildJsonObject {
                put("args", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
            })
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/session/projections")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure(
                        "dsh/team/unavailable",
                        "session projections failed: ${response.code}",
                    )
                }
                val bodyStr = response.body?.string().orEmpty()
                // Отказ Host — ошибка, а не «команды нет».
                val values = (confirmedValue(bodyStr)
                    ?: return@withContext RemoteResult.Failure("dsh/team/unavailable", conn.extractRpcError(bodyStr) ?: "session projections were rejected")
                    ).let { (it as? kotlinx.serialization.json.JsonObject)?.get("values") }
                val team = (values as? kotlinx.serialization.json.JsonObject)
                    ?.get("agentTeam") as? kotlinx.serialization.json.JsonObject
                    ?: return@withContext RemoteResult.Success(null)
                val members = (team["members"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { element ->
                        val obj = element as? kotlinx.serialization.json.JsonObject
                            ?: return@mapNotNull null
                        val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull ?: return@mapNotNull null
                        AgentTeamMember(
                            id = id,
                            name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull ?: com.dsh.core.model.Tr.t("Member", "Участник"),
                            role = (obj["role"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull ?: "teammate",
                            phase = (obj["phase"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull ?: "active",
                            error = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull,
                        )
                    }
                val tasks = (team["tasks"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .mapNotNull { element ->
                        val obj = element as? kotlinx.serialization.json.JsonObject
                            ?: return@mapNotNull null
                        val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull ?: return@mapNotNull null
                        fun text(key: String): String? =
                            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        fun strings(key: String): List<String> =
                            (obj[key] as? kotlinx.serialization.json.JsonArray).orEmpty()
                                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                        AgentTeamTask(
                            id = id,
                            subject = text("subject") ?: com.dsh.core.model.Tr.t("Task", "Задача"),
                            description = text("description").orEmpty(),
                            status = text("status") ?: "pending",
                            ownerName = text("ownerName"),
                            blockedBy = strings("blockedBy"),
                            writeScopes = strings("writeScopes"),
                            ready = (obj["ready"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull != "false",
                            writeScopeWarnings = strings("writeScopeWarnings"),
                        )
                    }
                val failure = (team["failure"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                RemoteResult.Success(
                    AgentTeamSummary(members = members, tasks = tasks, failure = failure),
                )
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/team/unavailable", e.message ?: "session projections failed")
        }
    }

    override suspend fun getAutomationTasks(
        hostId: String
    ): RemoteResult<List<AutomationTaskItem>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "schedule/catalog")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/schedule/catalog")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        // Отказ Host или сети — ошибка: вызывающий оставляет прежний список, а не показывает «автоматизаций нет».
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val items = confirmedValue(bodyStr) as? kotlinx.serialization.json.JsonArray

                    if (items != null) {
                        val list = items.mapNotNull { item ->
                            if (item is kotlinx.serialization.json.JsonObject) {
                                val id = item["id"]?.toString()?.trim('"') ?: return@mapNotNull null
                                val title = item["title"]?.toString()?.trim('"') ?: com.dsh.core.model.Tr.t("Scheduled automation", "Автоматическая задача по расписанию")
                                val prompt = item["prompt"]?.toString()?.trim('"') ?: ""
                                val status = item["status"]?.toString()?.trim('"') ?: "active"
                                val timing = item["at"]?.toString() ?: item["cron"]?.toString() ?: item["every_seconds"]?.toString() ?: com.dsh.core.model.Tr.t("Runs on schedule", "По расписанию")
                                AutomationTaskItem(
                                    id = id,
                                    title = title,
                                    summary = "$timing · $prompt",
                                    inactive = status != "active",
                                    prompt = prompt
                                )
                            } else null
                        }
                        return@withContext RemoteResult.Success(list)
                    }
                    return@withContext RemoteResult.Failure("dsh/schedule/rejected", conn.extractRpcError(bodyStr) ?: "schedule/catalog was rejected")
                }
                RemoteResult.Failure("dsh/schedule/unavailable", "schedule/catalog: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/schedule/unavailable", e.message ?: "schedule/catalog failed")
        }
    }

    override suspend fun getTeamTasks(
        hostId: String
    ): RemoteResult<List<TeamTaskItem>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "team/listTasks")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/team/listTasks")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        // В Host 0.2.x маршрута team/listTasks нет (404): задачи команды приходят в проекции agentTeam.
        // Отказ маршрута, `ok: false` и битый ответ — ошибка, а не «задач нет» (R2-04).
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure("dsh/team/tasks-unavailable", "team/listTasks: HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val items = confirmedValue(body) as? kotlinx.serialization.json.JsonArray
                    ?: return@withContext RemoteResult.Failure("dsh/team/tasks-rejected", conn.extractRpcError(body) ?: "team/listTasks was rejected")
                val list = items.mapNotNull { item ->
                    val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    fun text(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    TeamTaskItem(
                        id = text("id") ?: return@mapNotNull null,
                        subject = text("subject") ?: text("title") ?: com.dsh.core.model.Tr.t("Task", "Задача"),
                        description = text("description").orEmpty(),
                        status = text("status") ?: "pending",
                        ownerName = text("ownerName"),
                    )
                }
                RemoteResult.Success(list)
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/team/tasks-unavailable", e.message ?: "team/listTasks failed")
        }
    }
}
