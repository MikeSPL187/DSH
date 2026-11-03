package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {
    @Test
    fun `session title is cut at a word boundary`() {
        assertEquals("Reply with one short sentence: what is…", sessionTitleFrom("Reply with one short sentence: what is 2+2? And more words here"))
        assertEquals("Короткий вопрос", sessionTitleFrom("Короткий вопрос"))
        assertEquals("Проверь сборку", sessionTitleFrom("@app/build.gradle.kts\n\nПроверь сборку"))
    }
}
