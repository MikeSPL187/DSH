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
}
