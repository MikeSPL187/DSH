package com.dsh.feature.chat

import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptTest {
    private val labels = SessionTranscript.Labels("Вы", "DSH", "ошибка")
    private fun msg(seq: Long, role: MessageRole, text: String) = ChatMessage("h", "s", "m$seq", role, text, seq = seq)
    private fun tool(seq: Long, name: String, status: String, args: String) =
        msg(seq, MessageRole.TOOL, "\u0002TOOL\u0002c$seq\u0002$name\u0002$status\u0002body\u0002$args")

    private val chat = listOf(
        msg(1, MessageRole.USER, "Добавь ключ sk-abcdefghijklmnopqrstuvwxyz123456 в .env"),
        tool(2, "bash", "ok", """{"command":"echo KEY=sk-abcdefghijklmnopqrstuvwxyz123456 >> .env\nsecond line"}"""),
        tool(3, "edit", "error", """{"file_path":"src/a.kt"}"""),
        msg(4, MessageRole.ASSISTANT, "Готово."),
        msg(5, MessageRole.SYSTEM, "служебное"),
    )

    @Test
    fun `secrets never leave, steps are optional quotes`() {
        val md = SessionTranscript.markdown("Ключ", "DSH · 28 окт · DSH-Workspace", chat, withSteps = true, labels)
        assertFalse(md.contains("abcdefghijklmnop"))
        assertTrue(md.contains("••••3456"))
        assertTrue(md.startsWith("# Ключ\n_DSH · 28 окт · DSH-Workspace_\n"))
        assertTrue(md.contains("> `bash` echo KEY=••••3456 >> .env\n"))
        assertTrue(md.contains("> `edit` src/a.kt · ошибка\n"))
        assertTrue(md.contains("**DSH:** Готово."))
        assertFalse(md.contains("служебное"))
        val short = SessionTranscript.markdown("Ключ", "x", chat, withSteps = false, labels)
        assertFalse(short.contains("> "))
        assertEquals(SessionTranscript.Counts(2, 2), SessionTranscript.counts(chat))
    }
}
