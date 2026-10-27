package com.dsh

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceOpsTest {
    private lateinit var base: File
    private lateinit var root: File
    private val calls = mutableListOf<String>()
    private var hostAgrees = true
    private val host = object : WorkspaceHost {
        override suspend fun create(path: String) = hostAgrees.also { calls += "create:" + File(path).name }
        override suspend fun rename(workspaceId: String, title: String) = hostAgrees.also { calls += "rename:$workspaceId:$title" }
        override suspend fun delete(workspaceId: String) = hostAgrees.also { calls += "delete:$workspaceId" }
        override suspend fun renameSession(sessionId: String, title: String) = hostAgrees.also { calls += "session:$sessionId:$title" }
    }
    private lateinit var ops: WorkspaceOps

    private fun Result<*>.problem() = (exceptionOrNull() as? WorkspaceOpException)?.problem

    @Before
    fun setUp() {
        base = Files.createTempDirectory("ws").toFile()
        root = File(base, "DSH-Workspace").apply { mkdirs() }
        ops = WorkspaceOps(root, host) { path -> if (File(path).name in setOf("app", DEFAULT_WORKSPACE_DIR_NAME)) "w1" else null }
    }

    @After
    fun tearDown() { base.deleteRecursively() }

    @Test
    fun createsFolderAndRegistersIt() = runBlocking {
        assertEquals("app", ops.create("  app ").getOrThrow())
        assertTrue(File(root, "app").isDirectory)
        assertEquals(listOf("create:app"), calls)
    }

    @Test
    fun rejectsNamesThatLeaveTheRoot() = runBlocking {
        assertEquals(WorkspaceProblem.INVALID_NAME, ops.create("../evil").problem())
        assertEquals(WorkspaceProblem.INVALID_NAME, ops.create("..").problem())
        assertEquals(WorkspaceProblem.INVALID_NAME, ops.create("a/b").problem())
        // Двоеточие proot принял бы за разделитель `-b путь:гость`; управляющие символы — не имя.
        assertEquals(WorkspaceProblem.INVALID_NAME, ops.create("evil:etc").problem())
        assertEquals(WorkspaceProblem.INVALID_NAME, ops.create("a\nb").problem())
        // G-28: длинное имя — понятная причина, а не тихо закрытое окно.
        assertEquals(WorkspaceProblem.NAME_TOO_LONG, ops.create("я".repeat(200)).problem())
        assertFalse(File(base, "evil").exists())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun hostRefusalIsReported() = runBlocking {
        hostAgrees = false
        assertEquals(WorkspaceProblem.HOST_REJECTED, ops.create("app").problem())
        File(root, "app").mkdirs()
        // Host не принял новое имя — папка возвращается.
        assertEquals(WorkspaceProblem.HOST_REJECTED, ops.rename("app", File(root, "app").absolutePath, "android").problem())
        assertTrue(File(root, "app").isDirectory)
        assertFalse(File(root, "android").exists())
        // G-27: отказ Host в переименовании сессии — не успех.
        assertEquals(WorkspaceProblem.HOST_REJECTED, ops.renameSession("s1", "Тест", "Новый").problem())
        // Host не снял проект — файлы не удаляются.
        assertEquals(WorkspaceProblem.HOST_REJECTED, ops.delete("app", File(root, "app").absolutePath).problem())
        assertTrue(File(root, "app").isDirectory)
    }

    @Test
    fun renamesFolderAndTellsHost() = runBlocking {
        File(root, "app").mkdirs()
        assertEquals("android", ops.rename("app", File(root, "app").absolutePath, "android").getOrThrow())
        assertTrue(File(root, "android").isDirectory)
        assertFalse(File(root, "app").exists())
        assertEquals(listOf("rename:w1:android"), calls)
    }

    @Test
    fun renameDoesNotOverwriteExistingFolder() = runBlocking {
        File(root, "app").mkdirs()
        File(root, "taken").mkdirs()
        assertEquals(WorkspaceProblem.NAME_TAKEN, ops.rename("app", "", "taken").problem())
        assertTrue(File(root, "app").isDirectory)
    }

    @Test
    fun deletesOnlyInsideRoot() = runBlocking {
        val inside = File(root, "app").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        val outside = File(base, "keep").apply { mkdirs() }
        assertTrue(ops.delete("app", inside.absolutePath).isSuccess)
        assertFalse(inside.exists())
        // Путь проверяется до Host: Host не снимает проект вне корня.
        assertEquals(WorkspaceProblem.OUTSIDE_ROOT, ops.delete("keep", outside.absolutePath).problem())
        assertTrue(outside.exists())
        assertEquals(WorkspaceProblem.OUTSIDE_ROOT, ops.delete("root", root.absolutePath).problem())
        assertTrue(root.exists())
        assertEquals(listOf("delete:w1"), calls)
    }

    /** G-12: «Default» и проект с идущей сессией не удаляются. */
    @Test
    fun defaultAndBusyProjectsAreKept() = runBlocking {
        val def = File(root, DEFAULT_WORKSPACE_DIR_NAME).apply { mkdirs() }
        assertEquals(WorkspaceProblem.DEFAULT_PROTECTED, ops.delete(DEFAULT_WORKSPACE_DIR_NAME, def.absolutePath).problem())
        val app = File(root, "app").apply { mkdirs() }
        assertEquals(WorkspaceProblem.SESSION_RUNNING, ops.delete("app", app.absolutePath, busy = true).problem())
        assertTrue(def.isDirectory && app.isDirectory)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun sessionRenameSkipsSameTitle() = runBlocking {
        assertFalse(ops.renameSession("s1", "Тест", " Тест ").getOrThrow())
        assertTrue(ops.renameSession("s1", "Тест", "Новый").getOrThrow())
        assertEquals(listOf("session:s1:Новый"), calls)
    }

    /** G-38: в журнал «создано DSH» (база для git init) попадает только новая папка, не существующая папка пользователя. */
    @Test
    fun onlyFoldersCreatedByDshAreLogged() = runBlocking {
        val log = mutableListOf<String>()
        val tracked = WorkspaceOps(root, host, object : CreatedProjectsLog {
            override fun add(name: String) { log += name }
            override fun rename(from: String, to: String) { log += "$from>$to" }
        }) { null }
        File(root, "mine-already").mkdirs()
        tracked.create("mine-already").getOrThrow()
        tracked.create("fresh").getOrThrow()
        tracked.rename("fresh", "", "fresh2").getOrThrow()
        assertEquals(listOf("fresh", "fresh>fresh2"), log)
    }
}
