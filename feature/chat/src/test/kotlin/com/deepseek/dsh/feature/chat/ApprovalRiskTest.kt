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

    @Test
    fun `escaped quoted and indirect deletions are still destructive`() {
        // В JSON: \\rm → \rm, \"rm\" → "rm".
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"\\rm -rf build"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"r'm' -rf build"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"\"rm\" -rf build"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("sudo find . -delete"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("rsync -a --delete src/ dst/"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("python3 -c 'import shutil; shutil.rmtree(\\'src\\')'"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("node -e 'require(`fs`).rmSync(`src`, {recursive: true})'"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git branch -D feature"))
        assertEquals(ApprovalTier.DESTRUCTIVE, bash("git stash drop"))
        // Команда под незнакомым ключом аргументов.
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("shell_exec", """{"script":"rm -rf build"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("exec", """{"commands":["ls","rm -rf build"]}"""))
        assertEquals(ApprovalTier.WRITE, approvalTier("exec", """{"script":"ls -la","rm":"key name only"}"""))
    }

    @Test
    fun `plugin tool names with short delete words are destructive, similar words are not`() {
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("fs_rm", """{"path":"a"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("wipe_dir", """{"path":"a"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("cache.purge", """{}"""))
        assertEquals(ApprovalTier.WRITE, approvalTier("format_code", """{"path":"a"}"""))
        assertEquals(ApprovalTier.WRITE, approvalTier("select_model", """{}"""))
        assertEquals(ApprovalTier.WRITE, approvalTier("confirm_terminal", """{}"""))
    }

    /** G-04: аргументы раскрываются JSON-парсером, а не регулярным выражением. */
    @Test
    fun `json escapes and string arguments cannot hide a deletion`() {
        // В JSON: \t — табуляция между rm и флагом, \/ — косая черта, r — буква r.
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"rm\t-rf ~"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"\/bin\/rm -rf ~"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"rm -rf ~"}"""))
        // Host прислал аргументы строкой (toolArgsText отдаёт её содержимое) — внутри снова JSON.
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"rm\t-rf ~","description":"list"}"""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """"{\"command\":\"rm\\t-rf ~\"}""""))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", "rm -rf ~"))
        // Первый ключ безобиден, удаление — во втором.
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"ls","then":"rm -rf build"}"""))
        // Не разбирается, но похоже на JSON — спросить.
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", """{"command":"ls" """))
        assertEquals(ApprovalTier.WRITE, approvalTier("bash", """{"command":"ls\t-la"}"""))
        assertEquals("rm -rf ~", approvalTarget("""{"command":"rm\t-rf ~"}""").orEmpty().replace('\t', ' '))
        assertEquals(listOf("~"), deletionTargets("bash", """{"input":{"command":"\/bin\/rm -rf ~"}}"""))
    }

    /** G-51: аргументы инструмента от агента с глубокой вложенностью — не падение, а «спросить». */
    @Test
    fun `deeply nested tool arguments do not crash`() {
        val deep = "{\"command\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertEquals("", toolSummary("bash", deep, "", "running"))
        assertEquals(emptyList<AskQuestion>(), parseAskQuestions(deep))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", deep))
    }
}
