package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileOfTest {
    private val base = Files.createTempDirectory("wsfile").toFile()
    private val workspace = File(base, "ws").apply { mkdirs() }

    @Test
    fun hostPathMapsIntoWorkspace() {
        File(workspace, "app/src").mkdirs()
        assertEquals(File(workspace, "app/src/a.kt").canonicalFile, workspaceFileOf("/workspace/app/src/a.kt", workspace))
        assertEquals(File(workspace, "x.txt").canonicalFile, workspaceFileOf(File(workspace, "x.txt").absolutePath, workspace))
    }

    @Test
    fun pathsLeavingTheWorkspaceAreRejected() {
        assertNull(workspaceFileOf("/workspace/../../etc/passwd", workspace))
        assertNull(workspaceFileOf("/root/.dsh/auth.json", workspace))
        assertNull(workspaceFileOf(workspace.absolutePath + "/../secret", workspace))
        Files.createSymbolicLink(File(workspace, "link").toPath(), base.toPath())
        assertNull(workspaceFileOf("/workspace/link/secret", workspace))
    }
}
