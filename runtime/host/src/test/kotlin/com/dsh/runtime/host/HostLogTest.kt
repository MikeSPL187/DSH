package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** G-09: в журнал Host на диске не попадают секреты, файл ограничен, старые журналы удаляются. */
class HostLogTest {
    private fun dir() = Files.createTempDirectory("hostlog").toFile()

    @Test
    fun secretsAreScrubbedBeforeTheDiskAndSizeIsCapped() {
        val file = File(dir(), "host_1.log")
        val log = HostLog(file, maxBytes = 4096)
        log.append("[STDOUT]", "env DEEPSEEK_API_KEY=sk-live-abcdef0123456789abcdef")
        log.append("[STDOUT]", "Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        val text = file.readText()
        assertFalse(text, text.contains("sk-live-abcdef0123456789abcdef"))
        assertFalse(text, text.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
        repeat(1000) { log.append("[STDOUT]", "line $it ${"x".repeat(100)}") }
        assertTrue(file.length() <= 4096 + 100)
        assertTrue(file.readText().contains("size limit reached"))
    }

    @Test
    fun rotationKeepsOnlyTheNewest() {
        val d = dir()
        (1..8).forEach { i -> File(d, "host_$i.log").apply { writeText("x"); setLastModified(i * 1000L) } }
        File(d, "other.txt").writeText("keep")
        HostLog.rotate(d, "host_", ".log", keep = 5)
        assertEquals(listOf("host_5.log", "host_6.log", "host_7.log", "host_8.log"), d.list()!!.filter { it.startsWith("host_") }.sorted())
        assertTrue(File(d, "other.txt").exists())
    }
}
