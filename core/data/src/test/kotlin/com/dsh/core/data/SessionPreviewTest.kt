package com.dsh.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPreviewTest {
    @Test
    fun `menu preview shows the answer, not the model reasoning`() {
        val content = "The user asks to list three fruits. Simple.\u0001Apple\nBanana\nOrange"
        assertEquals("Apple", sessionPreviewLine(content))
    }

    @Test
    fun `markdown marks and code fences are skipped`() {
        assertEquals("Итог", sessionPreviewLine("## **Итог**\nтекст"))
        assertEquals("docs", sessionPreviewLine("[docs](https://x.y)"))
        assertEquals(null, sessionPreviewLine("  \n"))
    }

    @Test
    fun `link with parentheses in its address leaves no stray bracket`() {
        // Настоящий ответ агента: в меню было «Created hello.py) and ran it».
        assertEquals("Created hello.py and ran it", sessionPreviewLine("Created [hello.py](hello.py#print(\"hi\")) and ran it"))
    }
}
