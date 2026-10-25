package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.GoalInfo
import com.deepseek.dsh.core.model.QueuedMessageInfo
import com.deepseek.dsh.core.model.TodoEntryInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Рабочая папка из проекции `workspace/follow`. */
data class HostWorkspaceInfo(
    val id: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

/**
 * Дерево рабочих папок Host, которое собирается из кадров `workspace/follow`:
 * `baseline`/`replace` — всё заново, `upsert`/`remove` — одна папка, `order` — порядок.
 */
internal class WorkspaceTree {
    private val byId = LinkedHashMap<String, HostWorkspaceInfo>()
    private val order = mutableListOf<String>()

    /** Применить кадр и вернуть папки по порядку Host; незнакомый кадр ничего не меняет. */
    fun apply(frame: JsonObject): List<HostWorkspaceInfo> {
        when (frame.str("type")) {
            "baseline", "replace" -> (frame["value"] as? JsonObject)?.let { value ->
                byId.clear()
                order.clear()
                (value["items"] as? JsonArray)?.mapNotNull(::parseWorkspace)?.forEach(::put)
            }
            "upsert" -> parseWorkspace(frame["workspace"])?.let(::put)
            "remove" -> frame.str("workspaceId")?.let { id ->
                byId.remove(id)
                order.remove(id)
            }
            "order" -> (frame["workspaceIds"] as? JsonArray)?.let { ids ->
                order.clear()
                order.addAll(ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter(byId::containsKey))
            }
        }
        return order.mapNotNull(byId::get)
    }

    private fun put(workspace: HostWorkspaceInfo) {
        byId[workspace.id] = workspace
        if (workspace.id !in order) order.add(workspace.id)
    }

    private fun parseWorkspace(element: JsonElement?): HostWorkspaceInfo? {
        val obj = element as? JsonObject ?: return null
        return HostWorkspaceInfo(
            id = obj.str("workspaceId") ?: return null,
            path = obj.str("path").orEmpty(),
            title = obj.str("title").orEmpty(),
            sessionIds = (obj["sessionIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }
}

/** Модель сессии из проекции `modelSelection`: следующая выбранная, иначе последняя использованная. */
internal fun parseSelectedModel(value: JsonElement?): String? {
    val obj = value as? JsonObject ?: return null
    val selected = (obj["next"] as? JsonObject) ?: (obj["lastUsed"] as? JsonObject) ?: return null
    return selected.str("model")
}

/** Очередь на следующий ход из проекции `inbox` (`next-turn`): только текстовые сообщения. */
internal fun parseQueue(value: JsonElement?): List<QueuedMessageInfo> {
    val rows = (value as? JsonObject)?.get("next-turn") as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val text = (obj["content"] as? JsonArray)
            ?.mapNotNull { block -> (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }
            ?.joinToString("").orEmpty()
        if (text.isBlank()) return@mapNotNull null
        QueuedMessageInfo(id = obj.str("id") ?: return@mapNotNull null, text = text)
    }
}

/** План агента из проекции `todos`. */
internal fun parseTodos(value: JsonElement?): List<TodoEntryInfo> =
    (value as? JsonArray)?.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        TodoEntryInfo(content = obj.str("content") ?: return@mapNotNull null, status = obj.str("status") ?: "pending")
    }.orEmpty()

/** Цель сессии из проекции `goal`. */
internal fun parseGoal(value: JsonElement?): GoalInfo? {
    val obj = value as? JsonObject ?: return null
    val goal = obj["goal"] as? JsonObject ?: return null
    return GoalInfo(
        objective = goal.str("objective").orEmpty(),
        phase = goal.str("phase") ?: "active",
        blockedReason = (goal["blockedReason"] as? JsonObject)?.str("message"),
        roundsStarted = obj.str("roundsStarted")?.toIntOrNull() ?: 0,
        maxGoalRounds = goal.str("maxGoalRounds")?.toIntOrNull() ?: 0,
    )
}
