package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceGitParseTest {

    @Test
    fun porcelainKindsAndRename() {
        val raw = " M gradle/libs.versions.toml\u0000?? scripts/backup.sh\u0000D  old.txt\u0000R  new.kt\u0000old.kt\u0000A  added.md\u0000"
        val out = WorkspaceGit.parsePorcelain(raw)
        assertEquals(
            listOf(
                "gradle/libs.versions.toml" to WorkspaceGit.Kind.MODIFIED,
                "scripts/backup.sh" to WorkspaceGit.Kind.ADDED,
                "old.txt" to WorkspaceGit.Kind.DELETED,
                "new.kt" to WorkspaceGit.Kind.RENAMED,
                "added.md" to WorkspaceGit.Kind.ADDED,
            ),
            out,
        )
    }

    @Test
    fun numstatCountsBinaryAndRename() {
        val raw = "1\t1\tgradle/libs.versions.toml\u0000-\t-\tlogo.png\u00002\t0\t\u0000old.kt\u0000new.kt\u0000"
        val out = WorkspaceGit.parseNumstat(raw)
        assertEquals(1 to 1, out["gradle/libs.versions.toml"])
        assertEquals(-1 to -1, out["logo.png"])
        assertEquals(2 to 0, out["new.kt"])
    }

    @Test
    fun unifiedDiffNumbersLines() {
        val raw = """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -3,3 +3,3 @@ [versions]
             compose-bom = "1"
            -kotlin = "2.0.0"
            +kotlin = "2.0.21"
             room = "2"
            \ No newline at end of file
        """.trimIndent()
        val out = WorkspaceGit.parseUnified(raw)
        assertEquals(WorkspaceGit.DiffLine.Hunk("@@ -3,3 +3,3 @@ [versions]"), out[0])
        assertEquals(WorkspaceGit.DiffLine.Line(' ', 3, "compose-bom = \"1\""), out[1])
        assertEquals(WorkspaceGit.DiffLine.Line('-', 4, "kotlin = \"2.0.0\""), out[2])
        assertEquals(WorkspaceGit.DiffLine.Line('+', 4, "kotlin = \"2.0.21\""), out[3])
        assertEquals(WorkspaceGit.DiffLine.Line(' ', 5, "room = \"2\""), out[4])
        assertEquals(5, out.size)
    }

    @Test
    fun logSplitsSubjectAndBody() {
        val raw = "a".repeat(40) + "\u001faaaaaaa\u001fМихаил\u001f1700000000\u001fТема коммита\n\nТело первая строка\nвторая\n\u0000" +
            "\n" + "b".repeat(40) + "\u001fbbbbbbb\u001fDSH\u001f1700000100\u001fТолько тема\n\u0000"
        val out = WorkspaceGit.parseLog(raw)
        assertEquals(2, out.size)
        assertEquals("Тема коммита", out[0].subject)
        assertEquals("Тело первая строка\nвторая", out[0].body)
        assertEquals(1_700_000_000_000L, out[0].time)
        assertEquals("aaaaaaa", out[0].short)
        assertEquals("Только тема", out[1].subject)
        assertEquals("", out[1].body)
        assertEquals("b".repeat(40), out[1].hash)
    }

    @Test
    fun nameStatusKindsAndRename() {
        val raw = "M\u0000a.kt\u0000A\u0000new.md\u0000D\u0000gone.sh\u0000R087\u0000old.kt\u0000moved.kt\u0000"
        val out = WorkspaceGit.parseNameStatus(raw)
        assertEquals(
            listOf(
                Triple("a.kt", null, WorkspaceGit.Kind.MODIFIED),
                Triple("new.md", null, WorkspaceGit.Kind.ADDED),
                Triple("gone.sh", null, WorkspaceGit.Kind.DELETED),
                Triple("moved.kt", "old.kt", WorkspaceGit.Kind.RENAMED),
            ),
            out,
        )
    }
}
