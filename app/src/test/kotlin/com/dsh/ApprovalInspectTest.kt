package com.dsh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** G-14: экран опасного действия правильно говорит, внутри ли проекта удаление. */
class ApprovalInspectTest {
    private val base = Files.createTempDirectory("inspect").toFile()
    private val rootfs = File(base, "rootfs").apply { mkdirs() }
    private val workspaceRoot = File(base, "DSH-Workspace").apply { mkdirs() }
    private val proj = File(workspaceRoot, "Proj").apply { mkdirs() }

    @Test
    fun siblingWithTheSamePrefixIsOutside() = runBlocking {
        File(workspaceRoot, "Proj-backup").apply { mkdirs(); File(this, "a").writeText("12345") }
        val info = inspectDeletion(rootfs, workspaceRoot, "/workspace/Proj", listOf("/workspace/Proj-backup"))
        // Раньше canonicalPath.startsWith без разделителя считал `/…/Proj-backup` внутри `/…/Proj`.
        assertTrue(info.targets.single().outside)
        assertEquals(5L, info.targets.single().bytes)
    }

    @Test
    fun linuxWorkspacePathsMapToThePhoneFolderAndMasksExpand() = runBlocking {
        File(proj, "build").mkdirs()
        File(proj, "build/a.log").writeText("aa")
        File(proj, "build/b.log").writeText("bbb")
        File(proj, "build/keep.txt").writeText("x")
        val info = inspectDeletion(rootfs, workspaceRoot, "/workspace/Proj", listOf("/workspace/Proj/build/*.log", "build"))
        val (mask, dir) = info.targets
        assertFalse(mask.outside)
        assertEquals(5L, mask.bytes)
        assertFalse(dir.outside)
        assertEquals(6L, dir.bytes)
    }

    @Test
    fun homeAndSystemPathsAreOutside() = runBlocking {
        File(rootfs, "root").mkdirs()
        val info = inspectDeletion(rootfs, workspaceRoot, "/workspace/Proj", listOf("~", "/etc"))
        assertTrue(info.targets.all { it.outside })
    }
}
