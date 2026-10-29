package com.dsh.core.data

import com.dsh.core.model.SessionInfo
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Список сессий от Host ложится поверх кэша, не стирая того, чего в ответе Host нет (DSH-01). */
class SessionSyncTest {
    private val cached = SessionEntity(
        hostId = "h", sessionId = "s1", title = "Локальное название", workspacePath = "/root/p", status = "idle",
        updatedAt = 1, lastSeq = 42, archived = 1, pinned = 1, model = "DeepSeek V4", permissionPreset = "workspace-write",
    )

    @Test
    fun `host row keeps pins, archive, model and history cursor`() {
        val merged = SessionInfo(hostId = "h", sessionId = "s1", title = "", status = "running", updatedAt = 9, permissionPreset = "read-only")
            .mergedInto(cached)
        assertEquals("Локальное название", merged.title)
        assertEquals("/root/p", merged.workspacePath)
        assertEquals("running", merged.status)
        assertEquals(9L, merged.updatedAt)
        assertEquals(42L, merged.lastSeq)
        assertEquals(1, merged.archived)
        assertEquals(1, merged.pinned)
        assertEquals("DeepSeek V4", merged.model)
        assertEquals("read-only", merged.permissionPreset)
    }

    @Test
    fun `host title wins and a new session without title stays untitled`() {
        assertEquals("План", SessionInfo(hostId = "h", sessionId = "s1", title = "План").mergedInto(cached).title)
        assertNull(SessionInfo(hostId = "h", sessionId = "s2", title = "").mergedInto(null).title)
    }

    @Test
    fun `workspace follow reports pinned and archived sessions`() {
        val tree = WorkspaceTree()
        assertNull(tree.pinnedSessionIds)
        tree.apply(hostJson.parseToJsonElement(
            """{"type":"baseline","value":{"items":[],"pinnedSessionIds":["a"],"archivedSessionIds":["b","c"]}}""",
        ).jsonObject)
        assertEquals(setOf("a"), tree.pinnedSessionIds)
        assertEquals(setOf("b", "c"), tree.archivedSessionIds)
        tree.apply(hostJson.parseToJsonElement("""{"type":"pinned","pinnedSessionIds":[]}""").jsonObject)
        assertEquals(emptySet<String>(), tree.pinnedSessionIds)
        tree.apply(hostJson.parseToJsonElement("""{"type":"archived","archivedSessionIds":["c"]}""").jsonObject)
        assertEquals(setOf("c"), tree.archivedSessionIds)
    }
}
