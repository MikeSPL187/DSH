package com.dsh.core.data

import com.dsh.core.model.HostCommandInfo
import com.dsh.core.model.HostCommandResult
import com.dsh.core.model.SkillInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Слэш-команды и навыки сессии (`commands/...`, `skills/list`). */
class CommandsRepository internal constructor(private val calls: HostCalls) {

    private val _hostCommandsFlow = MutableStateFlow<List<HostCommandInfo>>(emptyList())
    val hostCommandsFlow: StateFlow<List<HostCommandInfo>> = _hostCommandsFlow.asStateFlow()

    /** Навыки сессии, которые может вызвать человек. */
    private val _hostSkillsFlow = MutableStateFlow<List<SkillInfo>>(emptyList())
    val hostSkillsFlow: StateFlow<List<SkillInfo>> = _hostSkillsFlow.asStateFlow()

    /** Перечитать слэш-команды сессии (`commands/list`). */
    suspend fun refreshHostCommands(hostId: String, sessionId: String) {
        val items = calls.value(hostId, "commands/list", buildJsonObject { put("agentId", sessionId) })
            as? JsonArray ?: return
        _hostCommandsFlow.value = items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val input = obj["input"] as? JsonObject
            HostCommandInfo(
                name = obj.str("name") ?: return@mapNotNull null,
                description = obj.str("description").orEmpty(),
                inputHint = input?.str("hint"),
                acceptsAttachments = input?.get("attachments")?.toString() == "true",
            )
        }
    }

    /**
     * Выполнить слэш-команду (`commands/execute`).
     * @return null — Host не знает такой команды, её надо отправить обычным сообщением.
     */
    suspend fun executeHostCommand(hostId: String, sessionId: String, line: String): HostCommandResult? {
        val args = buildJsonObject {
            put("agentId", sessionId)
            put("line", line)
            put("submittedAttachments", JsonArray(emptyList()))
        }
        val result = (calls.value(hostId, "commands/execute", args) as? JsonObject)?.get("result")
            as? JsonObject ?: return null
        return HostCommandResult(kind = result.str("kind").orEmpty(), text = result.str("text"))
    }

    /** Навыки сессии (`skills/list`; агент не активируется). */
    suspend fun listSkills(hostId: String, sessionId: String): List<SkillInfo> =
        fetchSkills(hostId, sessionId).orEmpty()

    /** Перечитать навыки в [hostSkillsFlow]; при ошибке остаются прежние. */
    suspend fun refreshHostSkills(hostId: String, sessionId: String) {
        fetchSkills(hostId, sessionId)?.let { _hostSkillsFlow.value = it }
    }

    private suspend fun fetchSkills(hostId: String, sessionId: String): List<SkillInfo>? {
        val args = buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) }
        val skills = (calls.value(hostId, "skills/list", args) as? JsonObject)?.get("skills")
            as? JsonArray ?: return null
        return skills.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            SkillInfo(
                name = obj.str("name") ?: return@mapNotNull null,
                description = obj.str("description").orEmpty(),
                whenToUse = obj.str("whenToUse"),
                modelInvocable = obj.str("modelInvocable") == "true",
            )
        }
    }
}
