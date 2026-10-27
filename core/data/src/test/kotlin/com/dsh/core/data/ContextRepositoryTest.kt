package com.dsh.core.data

import com.dsh.core.model.GoalInfo
import com.dsh.core.model.ReferenceCandidate.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRepositoryTest {
    @Test
    fun `files come before sessions and keep the host mention grammar`() = runBlocking {
        val host = FakeHost { method, _ ->
            when (method) {
                "fileReferences/list" -> hostOk("""[{"path":"app","kind":"directory"},{"path":"docs/my notes.md","kind":"file"},{"kind":"file"}]""")
                "sessionReferenceResolver/candidates" -> hostOk("""[{"sessionId":"s9","label":"Tests","displayTitle":"Тесты","mention":"@[Тесты](dsh-session:s9)","sameWorkspace":false,"cwd":"/root/other","createdAt":1700000000000}]""")
                else -> null
            }
        }
        val result = ContextRepository(HostCalls(host.client)).candidates("local", "s1", "", filesOnly = false)
        assertFalse(result.failed)
        assertEquals(listOf(Kind.DIRECTORY, Kind.FILE, Kind.SESSION), result.items.map { it.kind })
        assertEquals("@app/", result.items[0].mention)
        assertEquals("@\"docs/my notes.md\"", result.items[1].mention)
        assertEquals("@[Тесты](dsh-session:s9)", result.items[2].mention)
        assertEquals("/root/other", result.items[2].detail)
        assertTrue(host.calls.all { it.second.contains("\"agentId\":\"s1\"") })
    }

    @Test
    fun `one failed list keeps the other and files only skips sessions`() = runBlocking {
        val host = FakeHost { method, _ -> if (method == "fileReferences/list") hostOk("""[{"path":"a.kt","kind":"file"}]""") else null }
        val repo = ContextRepository(HostCalls(host.client))
        val both = repo.candidates("local", "s1", "a", filesOnly = false)
        assertFalse(both.failed)
        assertEquals(1, both.items.size)
        host.calls.clear()
        repo.candidates("local", "s1", "src/", filesOnly = true)
        assertEquals(listOf("fileReferences/list"), host.calls.map { it.first })
        assertTrue(ContextRepository(HostCalls(FakeHost { _, _ -> null }.client)).candidates("local", "s1", "", false).failed)
    }

    @Test
    fun `subagent prompt and stop address the child under its parent`() = runBlocking {
        val host = FakeHost { _, _ -> hostOk("""{"messageId":"m1"}""") }
        val repo = ContextRepository(HostCalls(host.client))
        assertTrue(repo.promptSubagent("local", "p1", "c1", "ещё раз", requestId = "r1").isSuccess)
        assertTrue(repo.stopSubagent("local", "p1", "c1").isSuccess)
        assertEquals("subagents/prompt", host.calls[0].first)
        assertTrue(host.calls[0].second.contains("\"request\":{\"requestId\":\"r1\",\"parentSessionId\":\"p1\",\"childSessionId\":\"c1\",\"mode\":\"continuable\",\"delivery\":\"queue\""))
        assertEquals("subagents/interruptByParent", host.calls[1].first)
    }

    @Test
    fun `goal actions send the current ref and need one`() = runBlocking {
        val host = FakeHost { _, _ -> hostOk("""{"id":"g1","revision":4}""") }
        val repo = ContextRepository(HostCalls(host.client))
        val goal = GoalInfo(objective = "ship", phase = "active", id = "g1", revision = 3)
        assertTrue(repo.pauseGoal("local", "s1", goal).isSuccess)
        assertTrue(repo.editGoal("local", "s1", goal, "ship v2").isSuccess)
        assertEquals("goals/pause", host.calls[0].first)
        assertTrue(host.calls[0].second.contains("\"ref\":{\"id\":\"g1\",\"revision\":3}"))
        assertTrue(host.calls[1].second.contains("\"request\":{\"objective\":\"ship v2\"}"))
        assertTrue(repo.clearGoal("local", "s1", goal.copy(id = "")).isFailure)
    }
}

class LocalFileCandidatesTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `lists inside the workspace only, folders first, without git`() {
        val root = tmp.newFolder("ws")
        java.io.File(root, "app/src").mkdirs()
        java.io.File(root, ".git").mkdirs()
        java.io.File(root, "apple.txt").writeText("x")
        java.io.File(root, "app/build.gradle.kts").writeText("x")
        tmp.newFile("secret.txt")
        assertEquals(listOf("app/", "apple.txt"), localFileCandidates(root, "ap").map { it.title })
        assertEquals(listOf("app/src/", "app/build.gradle.kts"), localFileCandidates(root, "app/").map { it.title })
        assertTrue(localFileCandidates(root, "../").isEmpty())
        assertTrue(localFileCandidates(root, "app/../../").isEmpty())
        assertEquals("@app/", localFileCandidates(root, "app").first().mention)
    }
}
