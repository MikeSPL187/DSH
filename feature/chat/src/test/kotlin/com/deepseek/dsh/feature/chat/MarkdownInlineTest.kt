package com.deepseek.dsh.feature.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-16, G-31: строчный Markdown — один проход; ссылка нажимается только для http(s) и показывает домен. */
class MarkdownInlineTest {
    private fun parse(text: String, onLink: (String) -> Unit = {}) =
        inlineMarkdownOf(text, Color.Blue, Color.Gray, Color.LightGray, onLink)

    @Test
    fun httpLinkIsClickableAndShowsItsDomain() {
        val opened = mutableListOf<String>()
        val s = parse("Смотрите [документацию](https://www.example.com/docs) и **это**") { opened += it }
        assertEquals("Смотрите документацию (example.com) и это", s.text)
        val links = s.getLinkAnnotations(0, s.length)
        assertEquals(1, links.size)
        (links.single().item as androidx.compose.ui.text.LinkAnnotation.Clickable).linkInteractionListener!!.onClick(links.single().item)
        assertEquals(listOf("https://www.example.com/docs"), opened)
    }

    @Test
    fun otherSchemesAreNotLinks() {
        val s = parse("[нажми](javascript:alert(1)) [файл](file:///data/data/x)")
        assertTrue(s.getLinkAnnotations(0, s.length).isEmpty())
    }

    @Test
    fun longReplyIsParsedInOnePass() {
        val text = "слово *курсив* `код` **жирный** ".repeat(20_000)
        val started = System.nanoTime()
        val s = parse(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("слишком долго: $ms мс", ms < 3_000)
        assertTrue(s.text.startsWith("слово курсив"))
    }
}
