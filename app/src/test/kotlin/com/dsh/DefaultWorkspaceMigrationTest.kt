package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultWorkspaceMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "DSH-Workspace")
    private val sessions get() = File(tmp.root, "sessions")

    private fun legacySessions() = File(sessions, workspaceSessionDirName(LEGACY_DEFAULT_WORKSPACE_DIR_NAME))
    private fun newSessions() = File(sessions, workspaceSessionDirName(DEFAULT_WORKSPACE_DIR_NAME))

    @Test
    fun `legacy folder and its sessions move to the new name`() {
        File(root, "$LEGACY_DEFAULT_WORKSPACE_DIR_NAME/src").mkdirs()
        File(root, "$LEGACY_DEFAULT_WORKSPACE_DIR_NAME/src/main.kt").writeText("fun main() {}")
        File(legacySessions(), "s1").mkdirs()

        assertTrue(migrateDefaultWorkspace(root, sessions))

        assertEquals("fun main() {}", File(root, "$DEFAULT_WORKSPACE_DIR_NAME/src/main.kt").readText())
        assertFalse(File(root, LEGACY_DEFAULT_WORKSPACE_DIR_NAME).exists())
        assertTrue(File(newSessions(), "s1").isDirectory)
        assertFalse(legacySessions().exists())
    }

    @Test
    fun `existing new folder is never overwritten`() {
        File(root, "$LEGACY_DEFAULT_WORKSPACE_DIR_NAME/a.txt").apply { parentFile!!.mkdirs(); writeText("old") }
        File(root, "$DEFAULT_WORKSPACE_DIR_NAME/a.txt").apply { parentFile!!.mkdirs(); writeText("new") }

        assertFalse(migrateDefaultWorkspace(root, sessions))

        assertEquals("old", File(root, "$LEGACY_DEFAULT_WORKSPACE_DIR_NAME/a.txt").readText())
        assertEquals("new", File(root, "$DEFAULT_WORKSPACE_DIR_NAME/a.txt").readText())
    }

    @Test
    fun `sessions merge into an existing sessions folder without replacing`() {
        File(root, LEGACY_DEFAULT_WORKSPACE_DIR_NAME).mkdirs()
        File(legacySessions(), "s1").mkdirs()
        File(legacySessions(), "s2/log").apply { parentFile!!.mkdirs(); writeText("legacy") }
        File(newSessions(), "s2/log").apply { parentFile!!.mkdirs(); writeText("current") }

        assertTrue(migrateDefaultWorkspace(root, sessions))

        assertTrue(File(newSessions(), "s1").isDirectory)
        assertEquals("current", File(newSessions(), "s2/log").readText())
    }

    @Test
    fun `nothing to migrate is a no-op and repeat is safe`() {
        root.mkdirs()
        assertFalse(migrateDefaultWorkspace(root, sessions))
        File(root, LEGACY_DEFAULT_WORKSPACE_DIR_NAME).mkdirs()
        assertTrue(migrateDefaultWorkspace(root, sessions))
        assertFalse(migrateDefaultWorkspace(root, sessions))
    }
}
