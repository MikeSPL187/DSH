package com.deepseek.dsh.runtime.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceGitHardeningTest {
    private fun git(dir: File, vararg args: String, hardened: Boolean = true): Int {
        val argv = listOf("git") + (if (hardened) WorkspaceGit.HARDENING else emptyList()) + listOf("-C", dir.path) + args
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor()
    }

    private fun repoWithTraps(): Pair<File, File> {
        val dir = Files.createTempDirectory("trap").toFile()
        val marker = File(dir.parentFile, dir.name + ".pwned")
        git(dir, "init", "-q", hardened = false)
        File(dir, "a.txt").writeText("1\n")
        git(dir, "-c", "user.email=a@b", "-c", "user.name=a", "add", "a.txt", hardened = false)
        git(dir, "-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "-m", "init", hardened = false)
        File(dir, ".git/config").appendText("[core]\n\tfsmonitor = \"touch ${marker.path}; false\"\n")
        val hook = File(dir, ".git/hooks/post-checkout")
        hook.writeText("#!/bin/sh\ntouch ${marker.path}\n"); hook.setExecutable(true)
        File(dir, "a.txt").writeText("2\n")
        return dir to marker
    }

    @Test
    fun `fsmonitor and hooks of the project do not run`() {
        assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val (dir, marker) = repoWithTraps()
        git(dir, "status", "--porcelain=v1")
        git(dir, "diff", "--no-ext-diff", "--no-textconv", "HEAD")
        git(dir, "checkout", "HEAD", "--", "a.txt")
        assertFalse("git выполнил команду из конфига или хука проекта", marker.exists())
        // Контроль: без защиты ловушка срабатывает — значит, тест проверяет настоящее.
        File(dir, "a.txt").writeText("3\n")
        git(dir, "status", hardened = false)
        assertTrue(marker.exists())
    }

    @Test
    fun `filters and includes in project config disable git`() {
        val dir = Files.createTempDirectory("cfg").toFile()
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("[core]\n\tbare = false\n")
        assertTrue(WorkspaceGit.repoConfigSafe(File(dir, "sub").apply { mkdirs() }))
        File(dir, ".git/config").appendText("[filter \"lfs\"]\n\tclean = sh -c evil\n")
        assertFalse(WorkspaceGit.repoConfigSafe(dir))
        File(dir, ".git/config").writeText("[includeIf \"gitdir:/\"]\n\tpath = /sdcard/x\n")
        assertFalse(WorkspaceGit.repoConfigSafe(dir))
        val worktree = Files.createTempDirectory("wt").toFile()
        File(worktree, ".git").writeText("gitdir: /elsewhere\n")
        assertFalse(WorkspaceGit.repoConfigSafe(worktree))
    }
}
