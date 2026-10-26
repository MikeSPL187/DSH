package com.deepseek.dsh.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket

/** Audit F-06/N-04 (+ T08): the loopback bridge must not be exhaustible before authentication. */
class BridgeServerLimitsTest {

    private fun server(limits: BridgeServer.Limits): Pair<BridgeServer, Int> {
        val tokenFile = File.createTempFile("bridge", ".token").apply { delete() }
        val server = BridgeServer(context = android.app.Application(), tokenFile = tokenFile, limits = limits)
        return server to server.start()
    }

    private fun connect(port: Int) = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5_000 }

    /** Когда сервер закрыл соединение, read возвращает -1 (или соединение сброшено). */
    private fun closedByServer(socket: Socket): Boolean =
        runCatching { socket.getInputStream().read() == -1 }.getOrDefault(true)

    @Test
    fun silentClientIsDisconnectedAfterFirstLineTimeout() {
        val (server, port) = server(BridgeServer.Limits(firstLineTimeoutMs = 300))
        try {
            connect(port).use { socket ->
                val started = System.currentTimeMillis()
                assertTrue(closedByServer(socket))
                assertTrue(System.currentTimeMillis() - started < 4_000)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun oversizedLineIsRejected() {
        val (server, port) = server(BridgeServer.Limits(maxLineBytes = 1024))
        try {
            connect(port).use { socket ->
                runCatching { socket.getOutputStream().write(ByteArray(64 * 1024) { 'a'.code.toByte() }) }
                assertTrue(closedByServer(socket))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun unauthenticatedGarbageClosesConnection() {
        val (server, port) = server(BridgeServer.Limits())
        try {
            connect(port).use { socket ->
                socket.getOutputStream().write("not json\n".toByteArray())
                assertTrue(closedByServer(socket))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionsBeyondLimitAreClosedImmediately() {
        val (server, port) = server(BridgeServer.Limits(maxClients = 2, firstLineTimeoutMs = 10_000))
        val held = List(2) { connect(port) }
        try {
            Thread.sleep(200)
            connect(port).use { extra -> assertTrue(closedByServer(extra)) }
        } finally {
            held.forEach { it.close() }
            server.stop()
        }
    }

    @Test
    fun processOutputIsBounded() {
        val (server, _) = server(BridgeServer.Limits(maxOutputChars = 1000))
        try {
            val result = server.runProcess(
                ProcessBuilder("sh", "-c", "head -c 5000000 /dev/zero | tr '\\0' x"),
                timeoutMs = 20_000,
            )
            val stdout = (result["stdout"] as kotlinx.serialization.json.JsonPrimitive).content
            assertTrue(stdout.length < 1_200)
            assertEquals("true", result["truncated"].toString())
            assertEquals("0", result["exitCode"].toString())
        } finally {
            server.stop()
        }
    }
}
