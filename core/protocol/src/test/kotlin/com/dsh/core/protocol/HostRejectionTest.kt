package com.dsh.core.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-03, G-22: HTTP 200 с `ok: false` — отказ Host, а не успех. */
class HostRejectionTest {
    private fun <T> withHost(body: String, block: suspend (RemoteClient) -> T): T {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(body))
        server.start()
        return try {
            runBlocking { block(RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient())) }
        } finally {
            server.shutdown()
        }
    }

    private val rejected = """{"result":{"ok":false,"error":{"code":"session/busy"}}}"""
    private val accepted = """{"result":{"ok":true,"value":null}}"""

    @Test
    fun cancelRejectedByHostIsNotAStop() {
        assertFalse(withHost(rejected) { it.cancelSession("local-host", "s1") })
        assertTrue(withHost(accepted) { it.cancelSession("local-host", "s1") })
    }

    @Test
    fun answerRejectedByHostIsNotDelivered() {
        assertFalse(withHost(rejected) { it.answerUserQuestion("local-host", "s1", "c1", "q1", "yes") })
        assertTrue(withHost(accepted) { it.answerUserQuestion("local-host", "s1", "c1", "q1", "yes") })
    }
}
