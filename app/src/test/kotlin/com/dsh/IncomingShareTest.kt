package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareTest {
    @Test
    fun `subject and text are joined without repeats`() {
        assertNull(IncomingShare.sharedText("", ""))
        assertEquals("https://x.dev/a", IncomingShare.sharedText("", "https://x.dev/a"))
        assertEquals("Заголовок", IncomingShare.sharedText("Заголовок", ""))
        // Браузер: заголовок страницы темой, ссылка текстом.
        assertEquals("Compose 1.7\nhttps://x.dev/a", IncomingShare.sharedText("Compose 1.7", "https://x.dev/a"))
        // Тема уже внутри текста — не дублируем.
        assertEquals("Compose 1.7 https://x.dev/a", IncomingShare.sharedText("Compose 1.7", "Compose 1.7 https://x.dev/a"))
    }

    @Test
    fun `shortcut actions map to targets`() {
        IncomingShare.pending.value = null
        assertEquals(false, IncomingShare.fromShortcut(android.content.Intent("other")))
        assertNull(IncomingShare.pending.value)
    }
}
