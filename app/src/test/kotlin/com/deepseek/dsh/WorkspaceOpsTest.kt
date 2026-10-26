package com.deepseek.dsh

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceOpsTest {
    private lateinit var base: File
    private lateinit var root: File
    private val calls = mutableListOf<String>()
    private val host = object : WorkspaceHost {
        override suspend fun create(path: String) { calls += "create:" + File(path).name }
        override suspend fun rename(workspaceId: String, title: String) { calls += "rename:$workspaceId:$title" }
        override suspend fun delete(workspaceId: String) { calls += "delete:$workspaceId" }
        override suspend fun renameSession(sessionId: String, title: String) { calls += "session:$sessionId:$title" }
    }
    private lateinit var ops: WorkspaceOps

    @Before
    fun setUp() {
        base = Files.createTempDirectory("ws").toFile()
        root = File(base, "DSH-Workspace").apply { mkdirs() }
        ops = WorkspaceOps(root, host) { path -> if (File(path).name == "app") "w1" else null }
    }

    @After
    fun tearDown() { base.deleteRecursively() }

    @Test
    fun createsFolderAndRegistersIt() = runBlocking {
        assertEquals("app", ops.create("  app "))
        assertTrue(File(root, "app").isDirectory)
        assertEquals(listOf("create:app"), calls)
    }

    @Test
    fun rejectsNamesThatLeaveTheRoot() = runBlocking {
        assertNull(ops.create("../evil"))
        assertNull(ops.create(".."))
        assertNull(ops.create("a/b"))
        // Двоеточие proot принял бы за разделитель `-b путь:гость`; управляющие символы — не имя.
        assertNull(ops.create("evil:etc"))
        assertNull(ops.create("a\nb"))
        assertFalse(File(base, "evil").exists())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun renamesFolderAndTellsHost() = runBlocking {
        File(root, "app").mkdirs()
        assertEquals("android", ops.rename("app", File(root, "app").absolutePath, "android"))
        assertTrue(File(root, "android").isDirectory)
        assertFalse(File(root, "app").exists())
        assertEquals(listOf("rename:w1:android"), calls)
    }

    @Test
    fun renameDoesNotOverwriteExistingFolder() = runBlocking {
        File(root, "app").mkdirs()
        File(root, "taken").mkdirs()
        assertNull(ops.rename("app", "", "taken"))
        assertTrue(File(root, "app").isDirectory)
    }

    @Test
    fun deletesOnlyInsideRoot() = runBlocking {
        val inside = File(root, "app").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        val outside = File(base, "keep").apply { mkdirs() }
        assertTrue(ops.delete("app", inside.absolutePath))
        assertFalse(inside.exists())
        assertFalse(ops.delete("keep", outside.absolutePath))
        assertTrue(outside.exists())
        assertFalse(ops.delete("root", root.absolutePath))
        assertTrue(root.exists())
        assertEquals(listOf("delete:w1"), calls)
    }

    @Test
    fun sessionRenameSkipsSameTitle() = runBlocking {
        assertFalse(ops.renameSession("s1", "Тест", " Тест "))
        assertTrue(ops.renameSession("s1", "Тест", "Новый"))
        assertEquals(listOf("session:s1:Новый"), calls)
    }
}
