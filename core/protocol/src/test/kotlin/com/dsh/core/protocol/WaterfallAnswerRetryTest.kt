package com.dsh.core.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * H-2: у шлюза Host нет срока на ответ клиента. Если POST `$events/result` не дошёл, а событие уже помечено
 * отвеченным, ход агента висит до перезапуска Host: ни повтор, ни ответ после повторной доставки не уходят.
 */
class WaterfallAnswerRetryTest {
    private fun withHost(vararg codes: Int, block: (HostEventsClient, MockWebServer) -> Unit) =
        withReplies(*codes.map { it to if (it == 200) ACCEPTED else "{}" }.toTypedArray(), block = block)

    private fun withReplies(vararg replies: Pair<Int, String>, block: (HostEventsClient, MockWebServer) -> Unit) {
        val server = MockWebServer()
        replies.forEach { (code, body) -> server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
        server.start()
        try {
            val events = HostEventsClient(HostConnection(server.url("/").toString().trimEnd('/'), OkHttpClient()))
            events.forwardedClientId = "client-1"
            events.answerRetryDelayMs = 10
            block(events, server)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failedAnswerIsRetried() = withHost(500, 200) { events, server ->
        events.answerWaterfall("local-host", "e1", """{"decision":"allowed-once"}""")
        assertEquals("/api/\$events/result", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/api/\$events/result", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun undeliveredAnswerCanBeSentAgain() = withHost(500, 500, 500, 200) { events, server ->
        events.answerWaterfall("local-host", "e1", null)
        repeat(3) { server.takeRequest(5, TimeUnit.SECONDS) }
        Thread.sleep(200)
        // Host доставит событие снова (после переподключения) — ответ должен уйти.
        events.answerWaterfall("local-host", "e1", null)
        assertEquals("/api/\$events/result", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun deliveredAnswerIsNotRepeated() = withHost(200, 200) { events, server ->
        events.answerWaterfall("local-host", "e1", null)
        server.takeRequest(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        events.answerWaterfall("local-host", "e1", null)
        assertEquals(null, server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun answerRejectedByHostIsRetried() = withReplies(
        200 to """{"result":{"ok":false,"error":{"code":"gateway/internal","message":"no active event stream"}}}""",
        200 to ACCEPTED,
    ) { events, server ->
        // HTTP 200 с `ok: false` — Host ответ не принял; считать его доставленным нельзя.
        events.answerWaterfall("local-host", "e1", null)
        assertEquals("/api/\$events/result", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/api/\$events/result", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }

    private companion object {
        const val ACCEPTED = """{"type":"server-response","result":{"ok":true}}"""
    }
}
