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
