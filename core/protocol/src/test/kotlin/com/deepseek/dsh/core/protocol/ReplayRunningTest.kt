package com.deepseek.dsh.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ход из истории без turn/end (Host перезапустился и не записал конец) не оставляет сессию
 * «Думаю» навсегда: идёт он, только если у Host есть активный ответ.
 */
class ReplayRunningTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun lastRunning(assistantStream: String): Boolean? {
        val snapshot = """{"type":"item","value":{"type":"snapshot","cursor":2,"hasMore":false,
            "records":[{"type":"event","event":{"seq":1,"type":"user/message","data":{"message":{"content":[{"type":"text","text":"hi"}]}}}},
                       {"type":"event","event":{"seq":2,"type":"turn/start","data":{}}}]$assistantStream}}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(snapshot.replace("\n", ""))
            }
        }))
        server.start()
        val client = RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
        val running = LinkedBlockingQueue<Boolean>()
        scope.launch { client.subscribeEvents("local").collect { if (it is RemoteEvent.SessionRunning) running.add(it.running) } }
        Thread.sleep(300)
        client.followSession("local", "s1")
        var last: Boolean? = null
        while (true) last = running.poll(3, TimeUnit.SECONDS) ?: break
        return last
    }

    @Test
    fun `interrupted turn in history is not running`() {
        assertEquals(false, lastRunning(""))
    }

    @Test
    fun `turn with an active answer is running`() {
        assertEquals(true, lastRunning(""","assistantStream":{"revision":1,"activeAttempt":{"stream":[]}}"""))
    }
}
