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
}
