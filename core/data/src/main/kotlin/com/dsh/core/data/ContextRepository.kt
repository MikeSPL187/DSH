package com.dsh.core.data

import com.dsh.core.model.GoalInfo
import com.dsh.core.model.ReferenceCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Контекст сообщения: ссылки «@» на файлы и сессии (`fileReferences/list`,
 * `sessionReferenceResolver/candidates`) и изменения цели сессии (`goals/pause`, `goals/edit` и другие).
 * Ссылка — обычный текст сообщения; разворачивает её Host при отправке.
 */
class ContextRepository internal constructor(private val calls: HostCalls) {

    /** Кандидаты ответа «@»: файлы и папки, затем сессии; [failed] — ни один список не пришёл. */
    data class Candidates(val items: List<ReferenceCandidate>, val failed: Boolean)

    /**
     * Кандидаты для `@query`. Файлы и сессии запрашиваются вместе; отказ одного списка не прячет другой.
     * @param filesOnly открыта кавычка `@"…` или запрос — путь внутри папки: сессии не нужны.
     */
    suspend fun candidates(hostId: String, sessionId: String, query: String, filesOnly: Boolean): Candidates = coroutineScope {
        val args = buildJsonObject { put("agentId", sessionId); put("query", query) }
        val files = async { calls.valueOrThrow(hostId, "fileReferences/list", args) }
        val sessions = async { if (filesOnly) null else calls.valueOrThrow(hostId, "sessionReferenceResolver/candidates", args) }
        val fileResult = files.await()
        val sessionResult = sessions.await()
        val items = fileResult.getOrNull().fileCandidates() + sessionResult?.getOrNull().sessionCandidates()
        Candidates(items, failed = fileResult.isFailure && (sessionResult == null || sessionResult.isFailure))
    }

    /** Поставить цель на паузу: агент не продолжит её сам. */
    suspend fun pauseGoal(hostId: String, sessionId: String, goal: GoalInfo): Result<Unit> = goalCall(hostId, sessionId, goal, "pause")

    /** Продолжить цель на паузе, остановленную или снова включить активную. */
    suspend fun resumeGoal(hostId: String, sessionId: String, goal: GoalInfo): Result<Unit> = goalCall(hostId, sessionId, goal, "resume")

    /** Отметить цель выполненной. */
    suspend fun completeGoal(hostId: String, sessionId: String, goal: GoalInfo): Result<Unit> = goalCall(hostId, sessionId, goal, "complete")

    /** Убрать цель из сессии. */
    suspend fun clearGoal(hostId: String, sessionId: String, goal: GoalInfo): Result<Unit> = goalCall(hostId, sessionId, goal, "clear")

    /** Заменить текст цели. */
    suspend fun editGoal(hostId: String, sessionId: String, goal: GoalInfo, objective: String): Result<Unit> =
        goalCall(hostId, sessionId, goal, "edit") { putJsonObject("request") { put("objective", objective) } }

    /**
     * Сообщение продолжаемому субагенту (`subagents/prompt`): встаёт в его очередь, родитель — сессия,
     * из которой субагента открыли. [requestId] создаётся до вызова, чтобы повтор не задвоил сообщение.
     */
    suspend fun promptSubagent(
        hostId: String,
        parentSessionId: String,
        childSessionId: String,
        text: String,
        requestId: String = java.util.UUID.randomUUID().toString(),
    ): Result<Unit> = calls.valueOrThrow(hostId, "subagents/prompt", buildJsonObject {
        putJsonObject("request") {
            put("requestId", requestId)
            put("parentSessionId", parentSessionId)
            put("childSessionId", childSessionId)
            put("mode", "continuable")
            put("delivery", "queue")
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            })
        }
    }).map { }

    /** Остановить ход продолжаемого субагента (`subagents/interruptByParent`). */
    suspend fun stopSubagent(hostId: String, parentSessionId: String, childSessionId: String): Result<Unit> =
        calls.valueOrThrow(hostId, "subagents/interruptByParent", buildJsonObject {
            put("childSessionId", childSessionId)
            put("parentSessionId", parentSessionId)
            put("mode", "continuable")
        }).map { }

    private suspend fun goalCall(
        hostId: String,
        sessionId: String,
        goal: GoalInfo,
        action: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): Result<Unit> {
        if (goal.id.isEmpty()) return Result.failure(IllegalStateException("goal/no-ref"))
        val args = buildJsonObject {
            put("agentId", sessionId)
            putJsonObject("ref") { put("id", goal.id); put("revision", goal.revision) }
            extra()
        }
        return calls.valueOrThrow(hostId, "goals/$action", args).map { }
    }
}

/** Строка ссылки на путь: с пробелами — в кавычках, как в грамматике `@path` Host. */
internal fun fileMention(path: String): String = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

private fun JsonElement?.fileCandidates(): List<ReferenceCandidate> = (this as? JsonArray).orEmpty().mapNotNull { item ->
    val obj = item as? JsonObject ?: return@mapNotNull null
    val path = obj.str("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val dir = obj.str("kind") == "directory"
    val shown = if (dir) path.trimEnd('/') + "/" else path
    ReferenceCandidate(
        kind = if (dir) ReferenceCandidate.Kind.DIRECTORY else ReferenceCandidate.Kind.FILE,
        title = shown,
        mention = fileMention(shown),
    )
}

private fun JsonElement?.sessionCandidates(): List<ReferenceCandidate> = (this as? JsonArray).orEmpty().mapNotNull { item ->
    val obj = item as? JsonObject ?: return@mapNotNull null
    val mention = obj.str("mention")?.takeIf { it.startsWith("@[") } ?: return@mapNotNull null
    val same = (obj["sameWorkspace"] as? JsonPrimitive)?.booleanOrNull ?: true
    ReferenceCandidate(
        kind = ReferenceCandidate.Kind.SESSION,
        title = obj.str("displayTitle") ?: obj.str("label") ?: return@mapNotNull null,
        mention = mention,
        detail = if (same) "" else obj.str("cwd").orEmpty(),
        createdAt = obj.str("createdAt")?.toDoubleOrNull()?.toLong() ?: 0,
    )
}

/**
 * Кандидаты «@» из рабочей папки на устройстве — для нового чата, пока у Host нет сессии.
 * Папка запроса (`app/src/`) должна оставаться внутри [root] (без `..` и ссылок наружу);
 * `.git` пропускается, папки идут первыми, затем совпадения с начала имени.
 */
fun localFileCandidates(root: java.io.File, query: String, limit: Int = 50): List<ReferenceCandidate> {
    val dirPart = query.substring(0, query.lastIndexOf('/') + 1)
    val needle = query.substring(dirPart.length)
    val base = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
    val dir = runCatching { java.io.File(base, dirPart).canonicalFile }.getOrNull() ?: return emptyList()
    if (dir != base && !dir.path.startsWith(base.path + java.io.File.separator)) return emptyList()
    val children = dir.listFiles().orEmpty().filter { it.name != ".git" && it.name.contains(needle, ignoreCase = true) }
    return children
        .sortedWith(compareBy<java.io.File>({ !it.isDirectory }, { !it.name.startsWith(needle, ignoreCase = true) }, { it.name.lowercase() }))
        .take(limit)
        .map { file ->
            val path = dirPart + file.name + if (file.isDirectory) "/" else ""
            ReferenceCandidate(
                kind = if (file.isDirectory) ReferenceCandidate.Kind.DIRECTORY else ReferenceCandidate.Kind.FILE,
                title = path,
                mention = fileMention(path),
            )
        }
}
