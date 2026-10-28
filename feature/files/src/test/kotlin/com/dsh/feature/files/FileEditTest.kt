package com.dsh.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileEditTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `crlf survives a round trip, executable bit is kept`() {
        val f = tmp.newFile("run.sh").apply { writeText("#!/bin/sh\r\necho 1\r\n"); setExecutable(true) }
        val doc = openForEdit(f).getOrThrow()
        assertTrue(doc.crlf)
        assertEquals("#!/bin/sh\necho 1\n", doc.text)
        val r = saveEdit(f, doc.text + "echo 2\n", doc.crlf, doc.stamp).getOrThrow()
        assertTrue(r is SaveResult.Saved)
        assertEquals("#!/bin/sh\r\necho 1\r\necho 2\r\n", f.readText())
        assertTrue(f.canExecute())
        assertEquals(listOf("run.sh"), tmp.root.list()!!.toList())
    }

    @Test
    fun `binary, broken utf-8 and big files are refused`() {
        val bin = tmp.newFile("a.bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        assertEquals(EditProblem.NOT_TEXT, (openForEdit(bin).exceptionOrNull() as EditException).problem)
        val broken = tmp.newFile("b.txt").apply { writeBytes(byteArrayOf(0x61, 0xC3.toByte(), 0x28)) }
        assertEquals(EditProblem.NOT_TEXT, (openForEdit(broken).exceptionOrNull() as EditException).problem)
        val big = tmp.newFile("c.txt").apply { writeText("x".repeat((MAX_EDIT_BYTES + 1).toInt())) }
        assertEquals(EditProblem.TOO_BIG, (openForEdit(big).exceptionOrNull() as EditException).problem)
        assertEquals("привет", openForEdit(tmp.newFile("d.txt").apply { writeText("привет") }).getOrThrow().text)
    }

    @Test
    fun `a file changed outside is not overwritten without asking`() {
        val f = tmp.newFile("x.txt").apply { writeText("one") }
        val doc = openForEdit(f).getOrThrow()
        f.writeText("agent changed it")
        f.setLastModified(doc.stamp.second + 5_000)
        assertEquals(SaveResult.Conflict, saveEdit(f, "mine", false, doc.stamp).getOrThrow())
        assertEquals("agent changed it", f.readText())
        assertTrue(saveEdit(f, "mine", false, doc.stamp, force = true).getOrThrow() is SaveResult.Saved)
        assertEquals("mine", File(f.path).readText())
    }
}

class FileEditorHistoryTest {
    @Test
    fun `undo goes back by words, redo returns, a new edit clears redo`() {
        val e = FileEditor()
        fun type(t: String, at: Long) = e.change(androidx.compose.ui.text.input.TextFieldValue(t), now = at)
        type("a", 1_000); type("ab", 1_100); type("ab ", 1_200); type("ab c", 1_300); type("ab cd", 1_400)
        e.undo()
        assertEquals("ab ", e.value.text)
        e.undo()
        assertEquals("", e.value.text)
        e.redo()
        assertEquals("ab ", e.value.text)
        type("ab x", 5_000)
        assertTrue(!e.canRedo)
        e.insert("{")
        e.undo()
        assertEquals("ab x", e.value.text)
    }
}

class FindReplaceTest {
    @Test
    fun `find ignores case, replace all is one undo step`() {
        assertEquals(listOf(0, 8), findAll("Version version", "version").let { listOf(it[0], it[1]) })
        assertEquals(emptyList<Int>(), findAll("abc", ""))
        assertEquals("x.x.x" to 3, replaceAll("a.A.a", "a", "x"))
        val e = FileEditor()
        e.change(androidx.compose.ui.text.input.TextFieldValue("kotlin = 1\nkotlin = 2"), now = 1)
        e.findOpen = true; e.query = "KOTLIN"; e.replacement = "java"
        e.seek(0)
        assertEquals(0, e.current)
        e.seek(1)
        assertEquals(11, e.value.selection.start)
        assertEquals(2, e.replaceEverything())
        assertEquals("java = 1\njava = 2", e.value.text)
        e.undo()
        assertEquals("kotlin = 1\nkotlin = 2", e.value.text)
    }
}

class EditorHighlightTest {
    @Test
    fun `syntax highlight keeps the text so offsets stay valid`() {
        val text = "[versions]\nkotlin = \"2.0.21\" # comment\n// note\nfun main() {}\n"
        val colors = com.dsh.core.designsystem.DshPorcelain
        val out = highlight(text.split('\n'), "toml", colors)
        assertEquals(text, out.text)
        assertTrue(out.spanStyles.isNotEmpty())
    }
}

class RevertHunkTest {
    private fun rows(vararg r: DiffRow) = r.toList()

    @Test
    fun `one fragment is reverted, the other stays`() {
        val text = "a\nB\nc\nd\ne\nf\nG\nh\n"
        val diff = rows(
            DiffRow.Hunk("@@ -1,3 +1,3 @@"), DiffRow.Line(' ', 1, "a"), DiffRow.Line('-', 2, "b"), DiffRow.Line('+', 2, "B"), DiffRow.Line(' ', 3, "c"),
            DiffRow.Hunk("@@ -6,2 +6,3 @@"), DiffRow.Line(' ', 6, "f"), DiffRow.Line('+', 7, "G"), DiffRow.Line(' ', 8, "h"),
        )
        val hunks = hunksOf(diff)
        assertEquals(2, hunks.size)
        assertEquals("a\nb\nc\nd\ne\nf\nG\nh\n", revertHunk(text, hunks[0]))
        assertEquals("a\nB\nc\nd\ne\nf\nh\n", revertHunk(text, hunks[1]))
    }

    @Test
    fun `a pure deletion is put back, a changed file is refused`() {
        val text = "x\nz\n"
        val del = rows(DiffRow.Hunk("@@ -1,3 +1,2 @@"), DiffRow.Line(' ', 1, "x"), DiffRow.Line('-', 2, "y"), DiffRow.Line(' ', 2, "z"))
        assertEquals("x\ny\nz\n", revertHunk(text, del))
        val tail = rows(DiffRow.Hunk("@@ -2 +1,0 @@"), DiffRow.Line('-', 2, "gone"))
        assertEquals("x\ngone\nz\n", revertHunk(text, tail))
        assertEquals(null, revertHunk("x\nCHANGED\n", del))
    }
}

class GrepTextTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `one hit per line, line numbers, offsets and snippet`() {
        val text = "[versions]\n    kotlin = \"2.0\"\nroom = 1 kotlin kotlin\nend"
        val (hits, total) = grepText(text, "KOTLIN")
        assertEquals(2, total)
        assertEquals(listOf(2, 3), hits.map { it.line })
        assertEquals(text.indexOf("kotlin"), hits[0].offset)
        // Отступ убран, найденное — по start..end.
        assertEquals("kotlin = \"2.0\"", hits[0].snippet)
        assertEquals("kotlin", hits[0].snippet.substring(hits[0].start, hits[0].end))
        assertEquals(text.indexOf("kotlin", text.indexOf("room")), hits[1].offset)
    }

    @Test
    fun `far match is cut with ellipsis, keep and limit are honoured`() {
        val long = "x".repeat(60) + "needle" + "y".repeat(400)
        val (hits, _) = grepText(long, "needle")
        val h = hits.single()
        assertTrue(h.snippet.startsWith("…") && h.snippet.endsWith("…"))
        assertEquals("needle", h.snippet.substring(h.start, h.end))
        assertEquals(GREP_LEAD, h.start - 1)
        val many = (1..20).joinToString("\n") { "a$it" }
        val (kept, total) = grepText(many, "a", keep = 3)
        assertEquals(3, kept.size)
        assertEquals(20, total)
        assertEquals(7, grepText(many, "a", limit = 7).second)
        assertEquals(listOf(1, 2, 3), kept.map { it.line })
        assertTrue(grepText("abc", "").first.isEmpty())
    }

    @Test
    fun `project search skips binaries, normalises crlf and caps lines`() {
        val root = tmp.root
        File(root, "a.kt").writeText("val room = 1\r\nval x = Room()\r\n")
        File(root, "b.bin").writeBytes(byteArrayOf(0x72, 0x6F, 0x6F, 0x6D, 0))
        File(root, "sub").mkdirs(); File(root, "sub/c.md").writeText("no match here\n")
        val r = searchText(root, walkFiles(root), "room")
        assertEquals(listOf("a.kt"), r.files.map { it.path })
        assertEquals(2, r.lines)
        val a = r.files.single()
        assertEquals(listOf(1, 2), a.hits.map { it.line })
        // Смещение — в тексте правки (\n вместо \r\n).
        assertEquals("val room = 1\nval x = Room()\n".indexOf("Room"), a.hits[1].offset)
        val capped = searchText(root, walkFiles(root), "room", maxLines = 1)
        assertTrue(capped.capped)
        assertEquals(1, capped.lines)
    }
}

class CommitBodyTest {
    @Test
    fun `wrapped lines are joined, paragraphs and lists are kept`() {
        val body = "Первая строка абзаца\nего продолжение.\n\nСписок:\n- один\n- два\n1. три"
        assertEquals("Первая строка абзаца его продолжение.\n\nСписок:\n- один\n- два\n1. три", unwrapBody(body))
    }
}
