package com.dsh.core.protocol

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * Контракт с Host 0.2.x (rootfs-v2.0.0): маршрут, имена аргументов и строгая проверка подтверждения.
 * Отчёт QA DSH 1.1.0: DSH-01 (список сессий), DSH-02 (права), DSH-03 (подтверждение), DSH-08 ($events).
 */
class HostContractTest {
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    /** Клиент, которому Host отвечает [reply] (HTTP 200) на любой запрос. */
    private fun host(reply: String) = RemoteClient("http://127.0.0.1:1", OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            bodies += chain.request().body?.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }.orEmpty()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build())

    private fun args() = Json.parseToJsonElement(bodies.single()).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject

    @Test
    fun `session list uses its own route and the _request argument`() = runBlocking {
        val result = host(
            """{"type":"server-response","result":{"ok":true,"value":{"items":[
                {"sessionId":"s1","updatedAt":5,"running":true,"blank":false,"cwd":"/root/p",
                 "projections":{"kind":"cached","asOfSeq":1,"values":{"title":"План","permissions":{"currentValue":"read-only"}}}},
                {"sessionId":"s2","updatedAt":6,"running":false,"blank":true},
                {"sessionId":"child","updatedAt":7,"running":false,"blank":false,"origin":"subagent","parentSessionId":"s1"}
            ]}}}""",
        ).listSessions("local-host")
        assertEquals("/api/session/list", requests.single().url.encodedPath)
        assertEquals(setOf("_request"), args().keys)
        val sessions = (result as RemoteResult.Success).data
        assertEquals(listOf("s1", "s2"), sessions.map { it.sessionId })
        with(sessions[0]) {
            assertEquals("local-host", hostId)
            assertEquals("План", title)
            assertEquals("/root/p", workspacePath)
            assertEquals("running", status)
            assertEquals(5L, updatedAt)
            assertEquals("read-only", permissionPreset)
        }
        assertEquals("", sessions[1].title)
    }

    @Test
    fun `rejected or malformed session list is a failure`() = runBlocking {
        assertTrue(host("{}").listSessions("h") is RemoteResult.Failure)
        assertTrue(host("""{"result":{"ok":false,"error":{"code":"gateway/arguments-invalid","message":"x"}}}""").listSessions("h") is RemoteResult.Failure)
    }

    @Test
    fun `permission preset is switched with the permission command`() = runBlocking {
        val result = host("""{"result":{"ok":true,"value":{"commandId":"c1","result":{"kind":"success","text":"preset read-only"}}}}""")
            .selectPermissionPreset("local-host", "s1", "read-only")
        assertTrue(result is RemoteResult.Success)
        assertEquals("/api/commands/execute", requests.single().url.encodedPath)
        assertEquals("s1", args()["agentId"]!!.jsonPrimitive.content)
        assertEquals("/permission read-only", args()["line"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed permission reply must not grant success`() = runBlocking {
        assertTrue(host("{}").selectPermissionPreset("local-host", "s1", "read-only") is RemoteResult.Failure)
        // Команды /permission на Host нет: ok без результата команды.
        assertTrue(host("""{"result":{"ok":true}}""").selectPermissionPreset("local-host", "s1", "read-only") is RemoteResult.Failure)
        // Host не знает пресет.
        val unknown = host("""{"result":{"ok":true,"value":{"result":{"kind":"error","text":"unknown preset \"x\""}}}}""")
            .selectPermissionPreset("local-host", "s1", "x")
        assertTrue(unknown is RemoteResult.Failure)
        assertTrue(host("""{"result":{"ok":"true","value":{"result":{"kind":"success"}}}}""").selectPermissionPreset("h", "s1", "read-only") is RemoteResult.Failure)
    }

    @Test
    fun `preset with command syntax is refused before reaching the Host`() = runBlocking {
        val result = host("""{"result":{"ok":true,"value":{"result":{"kind":"success"}}}}""")
            .selectPermissionPreset("local-host", "s1", "read-only\n/permission danger-full-access")
        assertTrue(result is RemoteResult.Failure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cancel wraps the session in the request argument`() = runBlocking {
        assertTrue(host("""{"result":{"ok":true,"value":{"accepted":true}}}""").cancelSession("local-host", "s1"))
        assertEquals("/api/session/cancel", requests.single().url.encodedPath)
        assertEquals("s1", args()["request"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancel requires an explicit confirmation`() = runBlocking {
        assertFalse(host("{}").cancelSession("local-host", "s1"))
        assertFalse(host("""{"result":{"ok":false,"error":{"code":"session/not-found"}}}""").cancelSession("local-host", "s1"))
    }

    @Test
    fun `forwarded events reconnect after a graceful Host restart`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1001, "Host restart") }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val client = RemoteClient(server.url("/").toString().trimEnd('/'), http)
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { client.forwardedHostEvents("local-host").collect {} }
        try {
            val deadline = System.currentTimeMillis() + 8_000
            while (server.requestCount < 2 && System.currentTimeMillis() < deadline) delay(100)
            assertTrue("only ${server.requestCount} WebSocket upgrade(s) after close 1001", server.requestCount >= 2)
        } finally {
            job.cancelAndJoin()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            runCatching { server.close() }
        }
    }

    @Test
    fun `rejected account lookup is not reported as signed out`() = runBlocking {
        // R2-05: конверт с ошибкой разбирался как AccountView без status и становился «signed-out».
        val rejected = host("""{"result":{"ok":false,"error":{"code":"account/backend-unavailable","message":"down"}}}""").getAccountState("h")
        assertTrue("$rejected", rejected is RemoteResult.Failure)
        assertEquals("account/backend-unavailable", (rejected as RemoteResult.Failure).code)
        assertTrue(host("{}").getAccountState("h") is RemoteResult.Failure)
        assertTrue(host("""{"result":{"ok":true,"value":{}}}""").getAccountState("h") is RemoteResult.Failure)
        val signedOut = host("""{"result":{"ok":true,"value":{"status":"signed-out","attempt":null}}}""").getAccountState("h")
        assertEquals("signed-out", (signedOut as RemoteResult.Success).data.status)
    }

    @Test
    fun `account profile and balance accept an empty Host value`() = runBlocking {
        assertEquals(null, (host("""{"result":{"ok":true,"value":null}}""").getAccountProfile("h") as RemoteResult.Success).data)
        assertTrue(host("""{"result":{"ok":false,"error":{"code":"x"}}}""").getAccountProfile("h") is RemoteResult.Failure)
    }

    @Test
    fun `team tasks failure is not an empty list`() = runBlocking {
        // R2-04: 404 и отказ Host раньше превращались в Success(emptyList()).
        assertTrue(host("""{"result":{"ok":false,"error":{"code":"gateway/not-found"}}}""").getTeamTasks("h") is RemoteResult.Failure)
        assertTrue(host("{}").getTeamTasks("h") is RemoteResult.Failure)
        assertTrue(host("""{"result":{"ok":false}}""").getAutomationTasks("h") is RemoteResult.Failure)
        assertTrue(host("""{"result":{"ok":false}}""").getAgentTeam("h", "s1") is RemoteResult.Failure)
        val tasks = host("""{"result":{"ok":true,"value":[{"id":"t1","subject":"Ship"}]}}""").getTeamTasks("h")
        assertEquals(listOf("t1"), (tasks as RemoteResult.Success).data.map { it.id })
        assertEquals(emptyList<Any>(), (host("""{"result":{"ok":true,"value":[]}}""").getAutomationTasks("h") as RemoteResult.Success).data)
    }
}
