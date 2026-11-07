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
    @Test fun expiredHostCookieIsRenewedWithoutResendingThePrompt() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/?token=") -> MockResponse().addHeader("Set-Cookie", "dsh=session; Path=/; Max-Age=60").setBody("OK")
                request.path == "/api/session/prompt" -> MockResponse().setResponseCode(401)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val client = RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient.Builder().cookieJar(HostCookieJar()).build())
            assertTrue(client.authenticate(server.url("/").toString().trimEnd('/'), "fixture-token") is RemoteResult.Success)
            val result = client.sendMessage("local", "s1", "hello", emptyList())
            assertTrue(result is RemoteResult.Failure)
            org.junit.Assert.assertEquals("dsh/auth/expired", (result as RemoteResult.Failure).code)
            val requests = (0 until server.requestCount).map { server.takeRequest().path }
            org.junit.Assert.assertEquals(1, requests.count { it == "/api/session/prompt" })
            org.junit.Assert.assertEquals(2, requests.count { it!!.startsWith("/?token=") })
        } finally { server.shutdown() }
    }

}
