package com.dsh.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    @Test
    fun `markdown is read as plain text, code blocks are skipped`() {
        val md = "## Почему падает\n\n**Kotlin** 2.0.0 устарел, см. [доку](https://x.dev).\n\n```kotlin\nval a = 1\n```\n\n- обновите `libs.versions.toml`\n- пересоберите"
        val parts = speechChunks(md)
        assertEquals(1, parts.size)
        val t = parts.single()
        assertTrue(t, t.startsWith("Почему падает"))
        assertTrue(t, "Kotlin 2.0.0 устарел, см. доку." in t)
        assertTrue(t, "val a" !in t && "```" !in t && "**" !in t && "https" !in t)
        assertTrue(t, "обновите libs.versions.toml" in t)
    }

    @Test
    fun `long text is split under the engine limit`() {
        val md = (1..30).joinToString("\n\n") { "Абзац $it " + "слово ".repeat(40) }
        val parts = speechChunks(md, max = 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 500 })
        assertTrue(speechChunks("```\nтолько код\n```").isEmpty())
    }

    @Test
    fun `plain text for selection keeps code but drops markup`() {
        val md = "## Итог\nОбновил `libs.versions.toml`, см. [доку](https://x.dev) и **важно**.\n\n```bash\n./gradlew build\n```"
        assertEquals("Итог\nОбновил libs.versions.toml, см. доку (https://x.dev) и важно.\n\n./gradlew build", plainText(md))
    }
}
