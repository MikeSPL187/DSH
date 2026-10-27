package com.deepseek.dsh.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H-4: лист деталей инструмента не рисует мегабайты аргументов одним Text. */
class ToolDetailLimitTest {
    @Test
    fun hugeTextIsCutWithTotal() {
        val (shown, total) = detailText("x".repeat(3 * 1024 * 1024))
        assertEquals(MAX_DETAIL_CHARS, shown.length)
        assertEquals(3 * 1024 * 1024, total)
    }

    @Test
    fun normalTextIsWhole() {
        val (shown, total) = detailText("""{"path":"a.txt"}""")
        assertEquals("""{"path":"a.txt"}""", shown)
        assertNull(total)
    }
}
