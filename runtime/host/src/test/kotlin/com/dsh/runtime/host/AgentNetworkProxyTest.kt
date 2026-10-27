package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/** G-50 (гигиена): прокси сети агента пускает только разрешённые адреса и только с паролем. */
class AgentNetworkProxyTest {
    /** Сервер «назначения»: отвечает строкой с первой строкой запроса, который получил. */
    private fun upstream(): ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val first = it.getInputStream().bufferedReader().readLine().orEmpty()
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${first.length}\r\nConnection: close\r\n\r\n$first".toByteArray())
                    }
                }
            }
        }
    }

    private fun exchange(proxy: AgentNetworkProxy, request: String): String =
        Socket("127.0.0.1", proxy.port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray())
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    private fun auth(proxy: AgentNetworkProxy) =
        "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("dsh:${proxy.token}".toByteArray())

    @Test
    fun allowedHostIsTunnelledAndOthersAreRefused() {
        val target = upstream()
        val blocked = mutableListOf<String>()
        val proxy = AgentNetworkProxy(
            decide = { hostMatches(it, setOf("github.com")) },
            onBlocked = { blocked += it },
            connect = { _, _ -> Socket("127.0.0.1", target.localPort) },
        )
        proxy.start()
        try {
            val ok = exchange(proxy, "CONNECT api.github.com:443 HTTP/1.1\r\nHost: api.github.com\r\n${auth(proxy)}\r\n\r\nGET /x HTTP/1.1\r\n\r\n")
            assertTrue(ok, ok.startsWith("HTTP/1.1 200 Connection Established"))
            assertTrue(ok, ok.contains("GET /x HTTP/1.1"))

            val denied = exchange(proxy, "CONNECT evil.example:443 HTTP/1.1\r\n${auth(proxy)}\r\n\r\n")
            assertTrue(denied, denied.startsWith("HTTP/1.1 403"))
            assertEquals(listOf("evil.example"), blocked)
        } finally {
            proxy.stop()
            target.close()
        }
    }

    @Test
    fun plainHttpIsForwardedInOriginForm() {
        val target = upstream()
        val proxy = AgentNetworkProxy(decide = { true }, connect = { _, _ -> Socket("127.0.0.1", target.localPort) })
        proxy.start()
        try {
            val reply = exchange(proxy, "GET http://deb.debian.org/debian/dists?x=1 HTTP/1.1\r\nHost: deb.debian.org\r\n${auth(proxy)}\r\n\r\n")
            assertTrue(reply, reply.endsWith("GET /debian/dists?x=1 HTTP/1.1"))
        } finally {
            proxy.stop()
            target.close()
        }
    }

    @Test
    fun withoutPasswordNothingPasses() {
        var connected = false
        val proxy = AgentNetworkProxy(decide = { true }, connect = { _, _ -> connected = true; Socket() })
        proxy.start()
        try {
            val reply = exchange(proxy, "CONNECT github.com:443 HTTP/1.1\r\n\r\n")
            assertTrue(reply, reply.startsWith("HTTP/1.1 407"))
            val wrong = exchange(proxy, "CONNECT github.com:443 HTTP/1.1\r\nProxy-Authorization: Basic " +
                Base64.getEncoder().encodeToString("dsh:nope".toByteArray()) + "\r\n\r\n")
            assertTrue(wrong, wrong.startsWith("HTTP/1.1 407"))
            assertFalse(connected)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun hostRulesMatchSubdomainsOnly() {
        assertTrue(hostMatches("api.github.com", setOf("github.com")))
        assertTrue(hostMatches("GitHub.com.", setOf("github.com")))
        assertFalse(hostMatches("evilgithub.com", setOf("github.com")))
        assertFalse(hostMatches("github.com.evil.io", setOf("github.com")))
        assertEquals("api.moonshot.cn", hostOf("https://api.moonshot.cn/v1"))
        assertEquals(null, hostOf("not a host"))
    }
}
