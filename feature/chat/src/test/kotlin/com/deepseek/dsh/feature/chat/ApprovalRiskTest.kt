package com.deepseek.dsh.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalRiskTest {
    private fun bash(command: String) = approvalTier("bash", """{"command":"$command"}""")

    @Test
    fun `deleting with rm is destructive with or without flags`() {
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("rm notes.txt"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("rm -rf build"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("cd src && sudo /bin/rm old.kt"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("ls | xargs rm"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("find . -name '*.tmp' -delete"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("shred secrets.env"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("dd if=/dev/zero of=disk.img"))
    }

    @Test
    fun `irreversible git commands are destructive`() {
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git reset --hard HEAD~3"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git checkout -- ."))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git push --force origin main"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git clean -fdx"))
    }

    @Test
    fun `ordinary commands are not destructive`() {
        assertEquals(ApprovalTier.WRITE, bash("./gradlew test"))
        assertEquals(ApprovalTier.WRITE, bash("echo perform the task"))
        assertEquals(ApprovalTier.WRITE, bash("npm run format"))
        assertEquals(ApprovalTier.WRITE, bash("git commit -m 'remove unused code'"))
        assertEquals(ApprovalTier.WRITE, bash("dd if=a.img"))
    }

    @Test
    fun `non shell tools are judged by name, not by file contents`() {
        assertEquals(ApprovalTier.WRITE, approvalTier("write_file", """{"path":"a.md","content":"rm -rf / and remove all"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("delete_file", """{"path":"a.md"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("fs_remove", """{"path":"dir"}"""))
    }

    @Test
    fun `deletion targets come from every part of the command`() {
        assertEquals(listOf("x.txt", "y"), deletionTargets("bash", """{"command":"cd a && rm x.txt; sudo /bin/rm -rf \"y\""}"""))
        assertEquals(listOf("tmp/a.log"), deletionTargets("delete_file", """{"path":"tmp/a.log"}"""))
        assertEquals(emptyList<String>(), deletionTargets("bash", """{"command":"ls -la"}"""))
    }

    @Test
    fun `target is read from the first known key`() {
        assertEquals("rm a", approvalTarget("""{"command":"rm a","path":"b"}"""))
        assertEquals("src/Main.kt", approvalTarget("""{"file_path":"src/Main.kt"}"""))
        assertEquals(null, approvalTarget("""{"other":1}"""))
    }
}
