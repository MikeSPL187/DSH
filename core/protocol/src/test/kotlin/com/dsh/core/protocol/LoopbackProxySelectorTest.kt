package com.dsh.core.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*

class LoopbackProxySelectorTest {
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))
    private val selected = mutableListOf<URI>()
    private val failures = mutableListOf<URI>()
    private val external = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> { selected += uri; return listOf(proxy) }
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) { failures += uri }
    }

    @Test fun onlyLiteralLoopbackBypassesTheUserProxy() {
        val selector = LoopbackProxySelector(external)
        for (url in listOf("http://127.0.0.1:1234/", "http://localhost:1234/", "http://[::1]:1234/"))
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(url)))
        assertTrue(selected.isEmpty())
        for (url in listOf("https://api.deepseek.com/", "http://192.168.1.1/", "http://localhost.example/")) {
            val uri = URI(url)
            assertEquals(listOf(proxy), selector.select(uri))
            assertEquals(uri, selected.last())
        }
        val failed = URI("https://api.deepseek.com/")
        selector.connectFailed(failed, proxy.address(), IOException("fixture"))
        assertEquals(listOf(failed), failures)
    }

    @Test fun localHostAuthenticationWorksWithAnUnreachableSystemProxy() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().addHeader("Set-Cookie", "dsh=qa; Path=/; Max-Age=60").setBody("OK"))
        server.start()
        try {
            val client = OkHttpClient.Builder().cookieJar(HostCookieJar()).proxySelector(LoopbackProxySelector(external)).build()
            assertTrue(HostAuthenticator(client).exchangeTokenForCookie(server.url("/").toString(), "fixture") is RemoteResult.Success)
            assertTrue("Loopback was sent to the configured external proxy", selected.isEmpty())
        } finally { server.shutdown() }
    }
}
