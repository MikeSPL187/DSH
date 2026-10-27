package com.dsh.core.data

import com.dsh.core.model.GoalInfo
import com.dsh.core.model.QueuedMessageInfo
import com.dsh.core.model.TodoEntryInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostProjectionsTest {
    private fun json(text: String): JsonObject = hostJson.parseToJsonElement(text).jsonObject

    private fun workspace(id: String, vararg sessions: String) =
        """{"workspaceId":"$id","path":"/w/$id","title":"$id","sessionIds":[${sessions.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun `workspace baseline replaces the tree and keeps host order`() {
        val tree = WorkspaceTree()
        tree.apply(json("""{"type":"upsert","workspace":${workspace("old")}}"""))
        val result = tree.apply(json("""{"type":"baseline","value":{"items":[${workspace("b", "s1")},${workspace("a")}]}}"""))
        assertEquals(listOf("b", "a"), result.map { it.id })
        assertEquals(listOf("s1"), result.first().sessionIds)
    }

    @Test
    fun `workspace upsert updates in place and appends new ones`() {
        val tree = WorkspaceTree()
        tree.apply(json("""{"type":"baseline","value":{"items":[${workspace("a")},${workspace("b")}]}}"""))
        tree.apply(json("""{"type":"upsert","workspace":${workspace("a", "s9")}}"""))
        val result = tree.apply(json("""{"type":"upsert","workspace":${workspace("c")}}"""))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals(listOf("s9"), result.first().sessionIds)
    }

    @Test
    fun `workspace order drops unknown ids and remove forgets the folder`() {
        val tree = WorkspaceTree()
        tree.apply(json("""{"type":"baseline","value":{"items":[${workspace("a")},${workspace("b")}]}}"""))
        assertEquals(
            listOf("b", "a"),
            tree.apply(json("""{"type":"order","workspaceIds":["b","ghost","a"]}""")).map { it.id },
        )
        assertEquals(listOf("a"), tree.apply(json("""{"type":"remove","workspaceId":"b"}""")).map { it.id })
    }

    @Test
    fun `unknown workspace frame changes nothing`() {
        val tree = WorkspaceTree()
        tree.apply(json("""{"type":"baseline","value":{"items":[${workspace("a")}]}}"""))
        assertEquals(listOf("a"), tree.apply(json("""{"type":"archived","archivedSessionIds":["x"]}""")).map { it.id })
    }

    @Test
    fun `selected model prefers next over last used`() {
        assertEquals("next", parseSelectedModel(json("""{"next":{"model":"next"},"lastUsed":{"model":"old"}}""")))
        assertEquals("old", parseSelectedModel(json("""{"lastUsed":{"model":"old"}}""")))
        assertNull(parseSelectedModel(json("""{}""")))
    }

    @Test
    fun `queue keeps text messages only`() {
        val value = json(
            """{"next-turn":[
                {"id":"1","content":[{"type":"text","text":"Hello "},{"type":"image"},{"type":"text","text":"world"}]},
                {"id":"2","content":[{"type":"image"}]},
                {"content":[{"type":"text","text":"no id"}]}
            ]}""",
        )
        assertEquals(listOf(QueuedMessageInfo(id = "1", text = "Hello world")), parseQueue(value))
    }

    @Test
    fun `todos default to pending and skip entries without content`() {
        val value = hostJson.parseToJsonElement("""[{"content":"Read","status":"done"},{"content":"Write"},{"status":"done"}]""")
        assertEquals(
            listOf(TodoEntryInfo(content = "Read", status = "done"), TodoEntryInfo(content = "Write", status = "pending")),
            parseTodos(value),
        )
    }

    @Test
    fun `goal reads objective, phase, blocked reason, rounds and ref`() {
        val value = json(
            """{"roundsStarted":3,"goal":{"objective":"Ship","phase":"blocked",
                "blockedReason":{"message":"Needs key"},"maxGoalRounds":10,"id":"g1","revision":7}}""",
        )
        assertEquals(
            GoalInfo(objective = "Ship", phase = "blocked", blockedReason = "Needs key", roundsStarted = 3, maxGoalRounds = 10, id = "g1", revision = 7),
            parseGoal(value),
        )
        assertNull(parseGoal(json("""{"roundsStarted":1}""")))
    }

    @Test
    fun `subagent catalog keeps order, modes and labels`() {
        val list = parseSubagents(kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"a","mode":"continuable","label":"Tests","createdAt":5},{"id":"b","mode":"one-shot"},{"id":"c","mode":"external","label":"Codex"},{"mode":"one-shot"}]"""))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals(com.dsh.core.model.SubagentInfo.Mode.CONTINUABLE, list[0].mode)
        assertEquals(5L, list[0].createdAt)
        assertEquals("", list[1].label)
        assertEquals(com.dsh.core.model.SubagentInfo.Mode.EXTERNAL, list[2].mode)
    }
}
