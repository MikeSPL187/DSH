package com.dsh.runtime.host

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCheckpointsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): Pair<File, FileCheckpoints> {
        val p = tmp.newFolder("proj")
        File(p, "src").mkdirs()
        File(p, "src/a.kt").writeText("val a = 1\n")
        File(p, "README.md").writeText("# Readme\n")
        File(p, "node_modules/x").mkdirs(); File(p, "node_modules/x/index.js").writeText("big")
        File(p, ".gitignore").writeText("secret.env\n")
        File(p, "secret.env").writeText("KEY=1")
        return p to FileCheckpoints(p, tmp.newFolder("store"))
    }

    @Test
    fun `restore brings back edited, deleted and removes added files without touching the project git`() {
        val (p, cp) = project()
        Git.init().setDirectory(p).call().close()
        val gitBefore = File(p, ".git").walkTopDown().map { it.relativeTo(p).path }.toSet()
        val point = cp.snapshot("s1", "Сделай рефакторинг", now = 1_000)!!
        // src/a.kt, README.md, .gitignore — без node_modules, .git и игнорируемого secret.env.
        assertEquals(3, point.files)

        File(p, "src/a.kt").writeText("val a = 2\n")
        File(p, "README.md").delete()
        File(p, "src/b.kt").writeText("new")
        File(p, "node_modules/x/index.js").writeText("changed")
        File(p, "secret.env").writeText("KEY=2")

        val preview = cp.preview(point)!!
        assertEquals(listOf("src/a.kt"), preview.modified)
        assertEquals(listOf("README.md"), preview.restored)
        assertEquals(listOf("src/b.kt"), preview.removed)

        val (done, undo) = cp.restore(point, "s1").getOrThrow()
        assertEquals(3, done.total)
        assertEquals("val a = 1\n", File(p, "src/a.kt").readText())
        assertEquals("# Readme\n", File(p, "README.md").readText())
        assertFalse(File(p, "src/b.kt").exists())
        // Вне снимков — не тронуто.
        assertEquals("changed", File(p, "node_modules/x/index.js").readText())
        assertEquals("KEY=2", File(p, "secret.env").readText())
        // git проекта и сама папка — без следов хранилища.
        assertEquals(gitBefore, File(p, ".git").walkTopDown().map { it.relativeTo(p).path }.toSet())

        // Отмена возврата — возврат к точке «как было».
        cp.restore(undo, "s1").getOrThrow()
        assertEquals("val a = 2\n", File(p, "src/a.kt").readText())
        assertTrue(File(p, "src/b.kt").exists())
        assertFalse(File(p, "README.md").exists())
    }

    @Test
    fun `point is found by session, text and time`() {
        val (_, cp) = project()
        cp.snapshot("s1", "first", now = 10_000)
        cp.snapshot("s1", "second", now = 20_000)
        cp.snapshot("s2", "first", now = 30_000)
        assertEquals(10_000L, cp.pointFor("s1", " first ", sentAt = 11_500)!!.at)
        assertEquals(20_000L, cp.pointFor("s1", "second", sentAt = 21_000)!!.at)
        assertNull(cp.pointFor("s1", "third", sentAt = 21_000))
        assertNull(cp.pointFor("s1", "first", sentAt = 10_000 + 11 * 60_000))
        assertNotNull(cp.pointFor("s2", "first", sentAt = 30_000))
    }

    @Test
    fun `nested repositories are snapshotted like plain folders, unchanged files are not reread`() {
        val (p, cp) = project()
        val nested = File(p, "cloned").apply { mkdirs() }
        Git.init().setDirectory(nested).call().close()
        File(nested, "main.py").writeText("print(1)")
        val point = cp.snapshot("s", "x")!!
        File(nested, "main.py").writeText("print(2)")
        assertEquals(listOf("cloned/main.py"), cp.preview(point)!!.modified)
        val again = cp.snapshot("s", "y")!!
        assertEquals(point.files, again.files)
    }
}

class FileCheckpointsSpeedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `second snapshot of a big folder rereads only changed files`() {
        val p = tmp.newFolder("big")
        repeat(5000) { i -> File(p, "d${i % 50}").mkdirs(); File(p, "d${i % 50}/f$i.txt").writeText("line $i\n".repeat(40)) }
        val cp = FileCheckpoints(p, tmp.newFolder("store"))
        val t1 = System.nanoTime(); cp.snapshot("s", "a")!!; val first = (System.nanoTime() - t1) / 1_000_000
        File(p, "d1/f1.txt").writeText("changed")
        val t2 = System.nanoTime(); cp.snapshot("s", "b")!!; val second = (System.nanoTime() - t2) / 1_000_000
        println("checkpoint 5000 files: first=${first}ms second=${second}ms")
        assertTrue("second=$second", second < 3000)
    }
}

class FileCheckpointsMatchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `undo points are never matched to a message`() {
        val p = tmp.newFolder("p").apply { File(this, "a").writeText("1") }
        val cp = FileCheckpoints(p, tmp.newFolder("s"))
        cp.snapshot("s1", FileCheckpoints.UNDO_TEXT, now = 200_000)
        assertNull(cp.pointFor("s1", "другое", sentAt = 200_500))
        cp.warm()
        assertNotNull(cp.snapshot("s1", "x", now = 300_000))
    }
}

class FileCheckpointsVariantsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `variants keep their results, compare against the origin and survive pruning`() {
        val p = tmp.newFolder("p").apply { File(this, "a.txt").writeText("start") }
        val cp = FileCheckpoints(p, tmp.newFolder("s"))
        val origin = cp.snapshot("s1", "Сделай")!!
        File(p, "a.txt").writeText("variant 1"); File(p, "b.txt").writeText("new")
        val r1 = cp.snapshot("s1", FileCheckpoints.VARIANT_TEXT)!!
        cp.putGroup(FileCheckpoints.Group(origin.id, "Сделай".hashCode(), listOf(FileCheckpoints.Variant("s1", r1.id), FileCheckpoints.Variant("s2"))))
        cp.restore(origin, "s1").getOrThrow()
        assertEquals("start", File(p, "a.txt").readText())
        File(p, "a.txt").writeText("variant 2")

        val ch = cp.changes(origin, r1)!!
        assertEquals(listOf("a.txt"), ch.modified)
        assertEquals(listOf("b.txt"), ch.restored) // есть в результате, нет в точке
        assertEquals("s1", cp.groupOf("s2")!!.variants.first().session)

        // Оставить вариант 1 — его файлы возвращаются.
        cp.restore(cp.pointById(r1.id)!!, "s2").getOrThrow()
        assertEquals("variant 1", File(p, "a.txt").readText())
        assertTrue(File(p, "b.txt").exists())

        repeat(FileCheckpoints.KEEP + 5) { cp.snapshot("s3", "m$it") }
        assertNotNull(cp.pointById(origin.id))
        assertNotNull(cp.pointById(r1.id))
    }
}
