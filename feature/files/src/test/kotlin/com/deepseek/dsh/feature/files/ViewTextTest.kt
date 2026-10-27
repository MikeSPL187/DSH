package com.deepseek.dsh.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** G-01: просмотр файла с огромной строкой не держит её целиком и не рисует текст шире предела. */
class ViewTextTest {
    private fun file(text: String) = File.createTempFile("view", ".js").apply { writeText(text); deleteOnExit() }

    @Test
    fun minifiedBundleLineIsCut() {
        val view = readViewText(file("x".repeat(5_000_000) + "\nsecond\n"))
        assertEquals(listOf("second"), view.lines.drop(1))
        assertEquals(MAX_VIEW_LINE_CHARS + 1, view.lines[0].length)
        assertTrue(view.lines[0].endsWith("…"))
    }

    @Test
    fun hugeSingleLineStopsAtTheReadBudget() {
        val view = readViewText(file("z".repeat(9_000_000)))
        assertTrue(view.truncated)
        assertEquals(1, view.lines.size)
    }

    @Test
    fun longLinesStopAtTheCharacterBudget() {
        val view = readViewText(file(("y".repeat(10_000) + "\n").repeat(400)))
        assertTrue(view.truncated)
        assertTrue(view.lines.sumOf { it.length } <= MAX_VIEW_CHARS + view.lines.size)
        assertTrue(view.lines.all { it.length <= MAX_VIEW_LINE_CHARS + 1 })
    }

    @Test
    fun ordinaryFileIsShownWhole() {
        val view = readViewText(file("a\r\nb\n\nc"))
        assertFalse(view.truncated)
        assertEquals(listOf("a", "b", "", "c"), view.lines)
        assertEquals(listOf("a"), readViewText(file("a\n")).lines)
        assertEquals(MAX_VIEW_LINES, readViewText(file("l\n".repeat(MAX_VIEW_LINES + 5))).lines.size)
        assertTrue(readViewText(file("l\n".repeat(MAX_VIEW_LINES + 5))).truncated)
    }
}
