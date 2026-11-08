package com.dsh

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutomationWorkspaceTest {
    @Test fun androidAndGuestPathsResolveToTheSameMountedProject() {
        val root = Files.createTempDirectory("dsh-auto-workspace").toFile()
        try {
            assertEquals("/workspace", automationWorkspacePath("", root))
            assertEquals("/workspace", automationWorkspacePath(root.absolutePath, root))
            assertEquals("/workspace/Project one/src", automationWorkspacePath(File(root, "Project one/src").absolutePath, root))
            assertEquals("/workspace/Project one/src", automationWorkspacePath("/workspace/Project one/src", root))
            assertEquals("/workspace/B", automationWorkspacePath(File(root, "A/../B").absolutePath, root))
            assertNull(automationWorkspacePath(root.absolutePath + "-other/project", root))
            assertNull(automationWorkspacePath("/workspace/../../outside", root))
            assertNull(automationWorkspacePath("relative/project", root))
        } finally { root.deleteRecursively() }
    }

    @Test fun aSymlinkCannotRemapAnAutomationToAnUnboundDirectory() {
        val root = Files.createTempDirectory("dsh-auto-links").toFile()
        val outside = Files.createTempDirectory("dsh-auto-outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "outside").toPath(), outside.toPath())
            assertNull(automationWorkspacePath(File(root, "outside").absolutePath, root))
            assertNull(automationWorkspacePath("/workspace/outside", root))
        } finally { File(root, "outside").delete(); root.deleteRecursively(); outside.deleteRecursively() }
    }
}
