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
