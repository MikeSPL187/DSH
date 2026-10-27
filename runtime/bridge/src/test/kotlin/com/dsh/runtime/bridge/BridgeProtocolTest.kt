package com.dsh.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeProtocolTest {

    @Test
    fun dispatchRejectsRequestsWithoutValidToken() {
        val tokenFile = File.createTempFile("bridge", ".token")
        tokenFile.delete()
        val server = BridgeServer(
            context = android.app.Application(),
            tokenFile = tokenFile,
        )
        server.start()
        try {
            val token = tokenFile.readText()
            assertTrue(token.isNotBlank())

            // Нет токена: отказ до любого метода устройства.
            val missing = server.dispatchRequest(
                BridgeRequest(id = "req_1", method = BridgeMethod.EXEC_SHELL.methodName)
            )
            assertEquals(BridgeAuth.UNAUTHORIZED_CODE, missing.error?.code)

            // Неверный токен: то же.
            val wrong = server.dispatchRequest(
                BridgeRequest(id = "req_2", method = BridgeMethod.EXEC_SHELL.methodName, auth = "wrong-token")
            )
            assertEquals(BridgeAuth.UNAUTHORIZED_CODE, wrong.error?.code)

            // Только после авторизации — разбор метода: незнакомый метод даёт -32601, а не -32001.
            val authorized = server.dispatchRequest(
                BridgeRequest(id = "req_3", method = "non_existent_method", auth = token)
            )
            assertEquals(-32601, authorized.error?.code)
        } finally {
            server.stop()
            tokenFile.delete()
        }
    }

    @Test
    fun clipboardReadIsDeniedUnlessUserAllowsIt() {
        val tokenFile = File.createTempFile("bridge", ".token")
        tokenFile.delete()
        val server = BridgeServer(context = android.app.Application(), tokenFile = tokenFile)
        server.start()
        try {
            val response = server.dispatchRequest(
                BridgeRequest(id = "clip", method = BridgeMethod.GET_CLIPBOARD.methodName, auth = tokenFile.readText()),
            )
            assertEquals(CLIPBOARD_DENIED_CODE, response.error?.code)
        } finally {
            server.stop()
            tokenFile.delete()
        }
    }

    @Test
    fun bridgeAuthUsesExactTokenMatch() {
        val token = BridgeAuth.newToken()
        assertTrue(BridgeAuth.verify(token, token))
        assertFalse(BridgeAuth.verify(token, "$token-x"))
        assertFalse(BridgeAuth.verify(token, token.dropLast(1)))
        assertFalse(BridgeAuth.verify(token, null))
        assertFalse(BridgeAuth.verify(null, token))
        assertFalse(BridgeAuth.verify("", ""))
    }

    @Test
    fun socketTransportRejectsUnauthenticatedRequest() {
        val tokenFile = File.createTempFile("bridge", ".token")
        tokenFile.delete()
        val server = BridgeServer(
            context = android.app.Application(),
            tokenFile = tokenFile,
        )
        server.start()
        try {
            val token = tokenFile.readText()
            val denied = sendLine(
                server.localPort,
                """{"jsonrpc":"2.0","id":"req_1","method":"android_exec_shell"}""",
            )
            assertTrue("expected -32001, got: $denied", denied.contains("-32001"))

            val authorized = sendLine(
                server.localPort,
                """{"jsonrpc":"2.0","id":"req_2","method":"non_existent_method","auth":"$token"}""",
            )
            assertTrue("expected -32601, got: $authorized", authorized.contains("-32601"))
        } finally {
            server.stop()
            tokenFile.delete()
        }
    }

    /** Отправить строку JSON-RPC мосту и прочитать строку ответа; при ошибке авторизации мост сам рвёт соединение. */
    private fun sendLine(port: Int, payload: String): String =
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("$payload\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine().orEmpty()
        }
}
