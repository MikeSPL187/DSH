package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.designsystem.DshCodeHighlight
import com.deepseek.dsh.core.designsystem.DshCodeHighlight.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
    private fun kinds(line: String, lang: String): List<Pair<String, Kind>> =
        DshCodeHighlight.line(line, lang).first.map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun `kotlin keywords, strings, numbers and trailing comment`() {
        val k = kinds("val name = \"dsh\" + 42 // note", "kotlin")
        assertEquals(listOf("val" to Kind.Keyword, "\"dsh\"" to Kind.Str, "42" to Kind.Num, "// note" to Kind.Comment), k)
    }

    @Test
    fun `block comments continue across lines`() {
        val spans = DshCodeHighlight.lines(listOf("/* start", "middle", "end */ val x"), "kotlin")
        assertEquals(Kind.Comment, spans[1].single().kind)
        assertEquals(listOf(Kind.Comment, Kind.Keyword), spans[2].map { it.kind })
    }

    @Test
    fun `language labels normalize and guess`() {
        assertEquals("python", DshCodeHighlight.normalize("py"))
        assertEquals("sh", DshCodeHighlight.normalize("", "$ ls -la"))
        assertEquals("json", DshCodeHighlight.normalize("", "{\"a\": 1}"))
        assertEquals("Kotlin", DshCodeHighlight.label("kt"))
        assertEquals("", DshCodeHighlight.label(""))
    }

    @Test
    fun `shell comments and apostrophes inside words`() {
        val k = kinds("echo don't # comment", "sh")
        assertTrue(k.contains("echo" to Kind.Keyword))
        assertTrue(k.contains("# comment" to Kind.Comment))
        assertTrue(k.none { it.second == Kind.Str })
    }

    @Test
    fun `plain text is not highlighted`() {
        assertTrue(DshCodeHighlight.line("if true then 1", "text").first.isEmpty())
    }
}
