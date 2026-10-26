package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.protocol.SessionRawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryTest {
    private fun frame(text: String, index: Int = 0) = SessionRawFrame(index, "item", text, 0L)

    private fun event(seq: Int, type: String, data: String = "{}") =
        """{"seq":$seq,"type":"$type","data":$data}"""

    @Test
    fun `снимок и поток разворачиваются, повтор события — одна строка, порядок по seq`() {
        val snapshot = frame(
            """{"type":"item","value":{"type":"snapshot","records":[{"event":${event(2, "tool/call", """{"name":"bash","turn":1,"step":2}""")}},{"event":${event(1, "request/header", """{"model":"deepseek-chat"}""")}}]}}""",
        )
        val live = frame("""{"type":"item","value":{"type":"event","event":${event(2, "tool/call", """{"name":"bash"}""")}}}""", 1)
        val live2 = frame("""{"type":"item","value":{"type":"event","event":${event(3, "turn/end", """{"reason":"stop"}""")}}}""", 2)

        val rows = buildTrajectory(listOf(snapshot, live, live2))

        assertEquals(listOf(1L, 2L, 3L), rows.map { it.seq })
        assertEquals(listOf("request", "tool/call", "turn/end"), rows.map { it.title })
        assertEquals(listOf("deepseek-chat", "bash", "stop"), rows.map { it.detail })
        assertEquals(1, rows[1].turn)
        assertEquals(2, rows[1].step)
    }

    @Test
    fun `чужие и битые кадры пропускаются`() {
        val rows = buildTrajectory(
            listOf(
                frame("not json"),
                frame("""{"type":"end"}"""),
                frame("""{"type":"item","value":{"type":"other"}}"""),
                frame("""{"type":"item","value":{"type":"event","event":{"type":"tool/call"}}}"""),
                // reason у turn/end бывает объектом — строка остаётся, деталь пустая.
                frame("""{"type":"item","value":{"type":"event","event":${event(5, "turn/end", """{"reason":{"code":1}}""")}}}"""),
            ),
        )
        assertEquals(listOf(5L), rows.map { it.seq })
        assertEquals("", rows.single().detail)
    }

    @Test
    fun `текст сообщения — строка или текстовые части, в одну строку и коротко`() {
        val user = textOfMessage(kotlinx.serialization.json.Json.parseToJsonElement("""{"message":{"content":"Привет,\n  мир"}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("Привет, мир", user)

        val parts = textOfMessage(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"message":{"content":[{"type":"text","text":"Раз"},{"type":"image","url":"x"},{"type":"text","text":"два"}]}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals("Раз два", parts)

        val long = textOfMessage(kotlinx.serialization.json.Json.parseToJsonElement("""{"message":{"content":"${"а".repeat(500)}"}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(80, long.length)
        assertTrue(textOfMessage(null).isEmpty())
    }
}
