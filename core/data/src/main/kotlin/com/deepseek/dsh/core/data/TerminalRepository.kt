package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.TerminalInfo
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.RemoteResult
import com.deepseek.dsh.core.protocol.TerminalConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Терминалы сессии в Host (`terminal/...`). */
class TerminalRepository internal constructor(
    private val client: DshClientProtocol,
    private val calls: HostCalls,
) {
    /** Открыть терминал и подключиться к нему (`terminal/create` + `terminal/attach`). */
    suspend fun openTerminal(
        hostId: String,
        sessionId: String,
        cols: Int,
        rows: Int,
        terminalId: String = "main",
    ): TerminalConnection? =
        (client.openTerminal(hostId, sessionId, cols, rows, terminalId) as? RemoteResult.Success)?.data

    /** Закрыть терминал и завершить его процесс (`terminal/close`). */
    suspend fun closeTerminal(hostId: String, sessionId: String, terminalId: String = "main") {
        client.closeTerminal(hostId, sessionId, terminalId)
    }

    /** Терминалы сессии (`terminal/list`). */
    suspend fun listTerminals(hostId: String, sessionId: String): List<TerminalInfo> {
        val args = buildJsonObject { put("sessionId", sessionId) }
        val items = calls.value(hostId, "terminal/list", args) as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            terminalInfo(obj, fallbackId = obj.str("id") ?: return@mapNotNull null)
        }
    }

    /** Доступные оболочки: путь и название (`terminal/shells`). */
    suspend fun listTerminalShells(hostId: String, sessionId: String): List<Pair<String, String>> {
        val args = buildJsonObject { put("agentId", sessionId) }
        val items = calls.value(hostId, "terminal/shells", args) as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val path = obj.str("path") ?: return@mapNotNull null
            path to obj.str("name").orEmpty()
        }
    }

    /** Создать терминал с заданным id и оболочкой (`terminal/create`). */
    suspend fun createTerminal(
        hostId: String,
        sessionId: String,
        terminalId: String,
        shellPath: String?,
        cols: Int = 80,
        rows: Int = 24,
    ): TerminalInfo? {
        val args = buildJsonObject {
            put("agentId", sessionId)
            put("request", buildJsonObject {
                put("id", terminalId)
                put("cols", cols)
                put("rows", rows)
                if (!shellPath.isNullOrBlank()) put("shellPath", shellPath)
            })
        }
        val value = calls.value(hostId, "terminal/create", args) as? JsonObject ?: return null
        return terminalInfo(value, fallbackId = terminalId)
    }

    private fun terminalInfo(obj: JsonObject, fallbackId: String) = TerminalInfo(
        id = obj.str("id") ?: fallbackId,
        title = obj.str("title").orEmpty(),
        state = obj.str("state").orEmpty(),
        shellName = (obj["shell"] as? JsonObject)?.str("name").orEmpty(),
    )
}
