package com.dsh.core.protocol

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Оборванный session/follow должен переподключаться, а не молча прекращать поток ответа. */
class FollowReconnectTest {
    private val server = MockWebServer()
    private val openFrames = LinkedBlockingQueue<String>()

    private fun acceptingUpgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            openFrames.add(text)
        }
    })

    private fun startClient(): RemoteClient {
        server.start()
        return RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reconnectsAfterNetworkDrop() {
        // Первая попытка: соединение рвётся (смена сети, Host ещё поднимается).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(acceptingUpgrade())
        startClient().followSession("local-host", "s-42")
        val frame = openFrames.poll(15, TimeUnit.SECONDS)
        assertNotNull("follow was not re-established after the drop", frame)
        assertTrue(frame!!.contains("session/follow"))
        assertTrue(frame.contains("s-42"))
    }

    @Test
    fun reconnectsWhenHostClosesTheSocket() {
        // Host закрывает сокет при перезапуске (1001 Going Away).
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1001, "restarting")
            }
        }))
        server.enqueue(acceptingUpgrade())
        startClient().followSession("local-host", "s-42")
        val frame = openFrames.poll(15, TimeUnit.SECONDS)
        assertNotNull("follow was not re-established after Host closed it", frame)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retryDelayGrowsAndIsCapped() {
        assertEquals(1_000L, followRetryDelayMs(1))
        assertEquals(2_000L, followRetryDelayMs(2))
        assertEquals(16_000L, followRetryDelayMs(5))
        assertEquals(30_000L, followRetryDelayMs(6))
        assertEquals(30_000L, followRetryDelayMs(FOLLOW_MAX_RETRIES))
    }
}
