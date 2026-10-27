package com.dsh.feature.chat

import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private var clock = 0L
    private fun msg(id: String, role: MessageRole, content: String) =
        ChatMessage(hostId = "local", sessionId = "s", messageId = id, role = role, content = content, createdAt = clock++ * 1000)

    private fun user(id: String, text: String = id) = msg(id, MessageRole.USER, text)
    private fun assistant(id: String, text: String) = msg(id, MessageRole.ASSISTANT, text)
    private fun tool(id: String, status: String) =
        msg(id, MessageRole.TOOL, listOf("", "", "call_$id", "bash", status, "output", """{"command":"ls"}""").joinToString(2.toChar().toString()))

    @Test
    fun `turn is question, work, then final answer`() {
        val rows = buildChatRows(
            listOf(user("q"), tool("t1", "done"), assistant("mid", "Let me check"), assistant("a", "Thinking\u0001Done.")),
            isThinking = false,
        )
        assertEquals(3, rows.size)
        assertEquals("q", (rows[0] as ChatRow.Single).message.messageId)
        val work = rows[1] as ChatRow.Work
        assertTrue(work.done)
        assertEquals(listOf("t1", "mid", "a_r"), work.items.map { it.messageId })
        assertEquals("Done.", (rows[2] as ChatRow.Single).message.content)
        assertEquals(3000L, work.durationMs)
    }

    @Test
    fun `running turn has an open work block even before any step`() {
        val rows = buildChatRows(listOf(user("q")), isThinking = true)
        val work = rows[1] as ChatRow.Work
        assertFalse(work.done)
        assertTrue(work.items.isEmpty())
    }

    @Test
    fun `running tool keeps the turn open, an answer closes it`() {
        assertFalse((buildChatRows(listOf(user("q"), tool("t", "running")), isThinking = false)[1] as ChatRow.Work).done)
        val answered = buildChatRows(listOf(user("q"), tool("t", "running"), assistant("a", "Here")), isThinking = true)
        assertTrue((answered[1] as ChatRow.Work).done)
    }

    @Test
    fun `earlier turns are closed when a new question follows`() {
        val rows = buildChatRows(listOf(user("q1"), tool("t", "running"), user("q2")), isThinking = true)
        assertTrue((rows[1] as ChatRow.Work).done)
        assertFalse((rows[3] as ChatRow.Work).done)
    }

    @Test
    fun `tool message fields are parsed in both formats`() {
        val full = parseTool(tool("t", "done"))!!
        assertEquals("call_t", full.callId)
        assertEquals("bash", full.name)
        assertEquals("done", full.status)
        assertEquals("""{"command":"ls"}""", full.args)
        val short = parseTool(msg("o", MessageRole.TOOL, listOf("", "", "read", "running", "body").joinToString(2.toChar().toString())))!!
        assertEquals("running", short.status)
        assertEquals("read", short.name)
    }

    @Test
    fun `tool summary prefers the command, then the first line of the result`() {
        assertEquals("ls -la", toolSummary("bash", """{"command":"ls -la"}""", "", "done"))
        assertEquals("src/A.kt", toolSummary("read_file", """{"file_path":"src/A.kt"}""", "", "done"))
        assertEquals("first", toolSummary("other", "not json", "first\nsecond", "done"))
        assertEquals("", toolSummary("other", "", "partial", "running"))
    }

    @Test
    fun `agent questions skip entries without id or text`() {
        val questions = parseAskQuestions(
            """{"questions":[
                {"id":"q1","question":"Which DB?","header":"Storage","options":[{"label":"SQLite","description":"Local"}]},
                {"id":"","question":"skipped"},
                {"id":"q3"}
            ]}""",
        )
        assertEquals(1, questions.size)
        assertEquals(listOf(AskOption("SQLite", "Local")), questions.single().options)
        assertTrue(parseAskQuestions("not json").isEmpty())
    }

    @Test
    fun `markdown blocks cover headings, lists, tasks, code, tables and quotes`() {
        val blocks = parseMarkdownBlocks(
            """
            # Title
            Some text
            continues here.

            - item
              - nested
            - [x] done
            1. first

            ```kotlin
            val a = 1
            ```
            | A | B |
            |---|---|
            | 1 | 2 |
            > quoted
            """.trimIndent(),
        )
        assertEquals(MarkdownBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Some text continues here."), blocks[1])
        assertEquals(MarkdownBlock.ListItem("•", "item"), blocks[2])
        assertEquals(MarkdownBlock.ListItem("•", "nested", depth = 1), blocks[3])
        assertEquals(MarkdownBlock.ListItem("•", "done", checked = true), blocks[4])
        assertEquals(MarkdownBlock.ListItem("1.", "first"), blocks[5])
        assertEquals(MarkdownBlock.Code("val a = 1"), blocks[6])
        assertEquals(MarkdownBlock.Table(listOf(listOf("A", "B"), listOf("1", "2"))), blocks[7])
        assertEquals(MarkdownBlock.Quote("quoted"), blocks[8])
    }

    @Test
    fun `unclosed code block during streaming keeps the text`() {
        assertEquals(listOf(MarkdownBlock.Code("partial")), parseMarkdownBlocks("```\npartial"))
    }
}
