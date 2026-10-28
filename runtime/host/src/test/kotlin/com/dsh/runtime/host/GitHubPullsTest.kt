package com.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubPullsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val pulls = GitHubPulls(api = server.url("").toString().trimEnd('/'))

    @After fun stop() = server.shutdown()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `create sends head, base and draft with the token, answer becomes a pull`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"number":12,"html_url":"https://github.com/o/r/pull/12","title":"Fix","draft":true}"""))
        val pull = pulls.create("tok", "o", "r", "dsh/fix", "main", "Fix", "Body", draft = true).getOrThrow()
        assertEquals(GitHubPulls.Pull(12, "https://github.com/o/r/pull/12", "Fix", true), pull)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/o/r/pulls", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = json(req.body.readUtf8())
        assertEquals("dsh/fix", body["head"]!!.jsonPrimitive.content)
        assertEquals("main", body["base"]!!.jsonPrimitive.content)
        assertTrue(body["draft"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `open pull is looked up by owner and branch`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(pulls.findOpen("tok", "o", "r", "dsh/a b").getOrThrow())
        assertEquals("/repos/o/r/pulls?state=open&per_page=1&head=o%3Adsh%2Fa+b", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""[{"number":3,"html_url":"https://github.com/o/r/pull/3","title":"T"}]"""))
        assertEquals(3, pulls.findOpen("tok", "o", "r", "x").getOrThrow()!!.number)
        server.enqueue(MockResponse().setBody("""{"default_branch":"develop"}"""))
        assertEquals("develop", pulls.defaultBranch("tok", "o", "r").getOrThrow())
    }

    @Test
    fun `errors are told apart`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Validation Failed","errors":[{"resource":"PullRequest","code":"custom","message":"A pull request already exists for o:dsh/fix."}]}"""))
        val e = pulls.create("tok", "o", "r", "dsh/fix", "main", "T", "", false).exceptionOrNull() as GitHubPulls.PullException
        assertEquals(GitHubPulls.Failure.Exists, e.failure)
        assertEquals(GitHubPulls.Failure.Auth, GitHubPulls.failureOf(401, json("""{"message":"Bad credentials"}""")))
        assertEquals(GitHubPulls.Failure.NoAccess, GitHubPulls.failureOf(404, json("""{"message":"Not Found"}""")))
        assertEquals(GitHubPulls.Failure.NoCommits, GitHubPulls.failureOf(422, json("""{"message":"Validation Failed","errors":[{"message":"No commits between main and dsh/x"}]}""")))
        assertEquals(GitHubPulls.Failure.DraftUnsupported, GitHubPulls.failureOf(422, json("""{"message":"Draft pull requests are not supported in this repository."}""")))
        assertEquals(GitHubPulls.Failure.Other("base is invalid"), GitHubPulls.failureOf(422, json("""{"message":"Validation Failed","errors":[{"message":"base is invalid"}]}""")))
        // Ссылка не на github.com в ответе не принимается.
        assertNull(GitHubPulls.parsePull(json("""{"number":1,"html_url":"https://evil.example/pull/1"}""")))
    }

    @Test
    fun `unpushed commits move to a new branch without touching files`() {
        val dir = tmp.newFolder("p")
        GitHubPush.isolate()
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            fun commit(name: String) {
                dir.resolve("$name.txt").writeText(name)
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Add $name").setAuthor("t", "t@e.st").setCommitter("t", "t@e.st").setSign(false).call()
            }
            commit("a")
            val pushed = git.repository.resolve(Constants.HEAD)
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(pushed) }.forceUpdate()
            git.repository.updateRef("refs/remotes/origin/HEAD").link("refs/remotes/origin/main")
            commit("b"); commit("c")
            git.repository.config.apply { setString("remote", "origin", "url", "https://github.com/o/r"); save() }
        }
        val before = GitHubPush.branchInfo(dir).getOrThrow()
        assertEquals(GitHubPush.BranchInfo("main", onRemote = true, localDefault = "main"), before)
        assertEquals(listOf("Add c", "Add b"), GitHubPush.commitsSince(dir, "main"))

        assertFalse(GitHubPush.validBranch("-x"))
        assertFalse(GitHubPush.validBranch("a..b"))
        assertTrue(GitHubPush.moveToNewBranch(dir, "bad name").isFailure)
        GitHubPush.moveToNewBranch(dir, "dsh/add-b-c").getOrThrow()
        assertTrue(GitHubPush.moveToNewBranch(dir, "dsh/add-b-c").isFailure)

        Git.open(dir).use { git ->
            val repo = git.repository
            assertEquals("refs/heads/dsh/add-b-c", repo.fullBranch)
            assertEquals(repo.resolve("refs/remotes/origin/main"), repo.resolve("refs/heads/main"))
            assertTrue(git.status().call().isClean)
        }
        assertEquals(GitHubPush.BranchInfo("dsh/add-b-c", onRemote = false, localDefault = "main"), GitHubPush.branchInfo(dir).getOrThrow())
        assertEquals(listOf("Add c", "Add b"), GitHubPush.commitsSince(dir, "main"))
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), dir.list()!!.filter { it.endsWith(".txt") }.sorted())
    }
}
