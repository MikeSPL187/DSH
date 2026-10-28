package com.dsh.runtime.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `device code and poll answers follow the device flow`() {
        val code = GitHubAuth.parseDeviceCode(json("""{"device_code":"d","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":899,"interval":2}"""))!!
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals(5, code.intervalSec)
        // Адрес не на github.com не показывается пользователю.
        assertEquals("https://github.com/login/device", GitHubAuth.parseDeviceCode(json("""{"device_code":"d","user_code":"X","verification_uri":"https://evil.example/login"}"""))!!.verificationUri)
        assertNull(GitHubAuth.parseDeviceCode(json("""{"error":"unauthorized_client"}""")))
        assertEquals(GitHubAuth.Poll.Token("gho_x"), GitHubAuth.parsePoll(json("""{"access_token":"gho_x","token_type":"bearer"}""")))
        assertEquals(GitHubAuth.Poll.Pending, GitHubAuth.parsePoll(json("""{"error":"authorization_pending"}""")))
        assertEquals(GitHubAuth.Poll.SlowDown, GitHubAuth.parsePoll(json("""{"error":"slow_down"}""")))
        assertEquals(GitHubAuth.Poll.Denied, GitHubAuth.parsePoll(json("""{"error":"access_denied"}""")))
        assertEquals(GitHubAuth.Poll.Expired, GitHubAuth.parsePoll(json("""{"error":"expired_token"}""")))
    }

    @Test
    fun `token goes only to https github repositories`() {
        assertEquals("owner" to "repo", GitHubPush.parseGitHubUrl("https://github.com/owner/repo.git"))
        assertEquals("o-1" to "my.repo", GitHubPush.parseGitHubUrl("https://github.com/o-1/my.repo"))
        assertNull(GitHubPush.parseGitHubUrl("git@github.com:owner/repo.git"))
        assertNull(GitHubPush.parseGitHubUrl("https://user@github.com/owner/repo.git"))
        assertNull(GitHubPush.parseGitHubUrl("https://github.com.evil.io/owner/repo"))
        assertNull(GitHubPush.parseGitHubUrl("http://github.com/owner/repo"))
        assertNull(GitHubPush.parseGitHubUrl("https://github.com/owner/repo/extra"))
    }

    @Test
    fun `config sections that redirect requests, weaken tls or run hooks are refused`() {
        assertTrue(GitHubPush.configSafe("[core]\n\tbare = false\n[remote \"origin\"]\n\turl = https://github.com/o/r.git\n"))
        assertFalse(GitHubPush.configSafe("[url \"https://evil.io/\"]\n\tinsteadOf = https://github.com/\n"))
        assertFalse(GitHubPush.configSafe("[http]\n\tsslVerify = false\n"))
        assertFalse(GitHubPush.configSafe("[credential]\n\thelper = store\n"))
        assertFalse(GitHubPush.configSafe("[include]\n\tpath = x\n"))
        assertFalse(GitHubPush.configSafe("[core]\n\thooksPath = /tmp/h\n"))
    }

    @Test
    fun `target is the checked origin and the current branch`() {
        val dir = tmp.newFolder("p")
        GitHubPush.isolate()
        Git.init().setDirectory(dir).setInitialBranch("feature").call().use { git ->
            dir.resolve("a.txt").writeText("a")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("a").setAuthor("t", "t@e.st").setCommitter("t", "t@e.st").setSign(false).call()
            git.repository.config.apply { setString("remote", "origin", "url", "https://github.com/owner/repo"); save() }
        }
        val t = GitHubPush.target(dir).getOrThrow()
        assertEquals("https://github.com/owner/repo.git", t.url)
        assertEquals("feature", t.branch)

        Git.open(dir).use { it.repository.config.apply { setString("remote", "origin", "url", "git@github.com:owner/repo.git"); save() } }
        val ssh = GitHubPush.target(dir).exceptionOrNull() as GitHubPush.PushException
        assertEquals(GitHubPush.Failure.NotGitHub, ssh.failure)

        Git.open(dir).use { it.repository.config.apply { setString("remote", "origin", "url", "https://github.com/owner/repo"); setString("url", "https://evil.io/", "insteadOf", "https://github.com/"); save() } }
        assertEquals(GitHubPush.Failure.UnsafeConfig, (GitHubPush.target(dir).exceptionOrNull() as GitHubPush.PushException).failure)
    }
}
