package com.dsh.feature.files

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

    /** G-15: фото 108 Мп уменьшается до экрана, а не вдвое. */
    @Test
    fun hugePhotoIsDownsampledToTheScreen() {
        assertEquals(8, previewSampleSize(12000, 9000, PREVIEW_MAX_SIDE))
        assertEquals(2, previewSampleSize(1080, 2400, PREVIEW_MAX_SIDE))
        assertEquals(1, previewSampleSize(800, 600, PREVIEW_MAX_SIDE))
        assertEquals(1, previewSampleSize(-1, -1, PREVIEW_MAX_SIDE))
    }

    /** G-30: ссылка на родительскую папку не зацикливает обход. */
    @Test
    fun walkDoesNotFollowLinkLoops() {
        val root = java.nio.file.Files.createTempDirectory("walk").toFile()
        val empty = File(root, "a/b").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(empty, "up").toPath(), root.toPath())
        File(root, "a/file.txt").writeText("x")
        val started = System.currentTimeMillis()
        assertEquals(listOf("file.txt"), walkFiles(root).map { it.name })
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }
}
