package com.dsh.core.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy

/**
 * Те же вызовы против настоящего Host (DSH 0.2.x из rootfs). Запускается, только если задан адрес
 * запуска Host с токеном, например:
 *
 *   DSH_LIVE_HOST='http://127.0.0.1:19347/?token=…' DSH_LIVE_WORKSPACE=/tmp/qa-project ./gradlew :core:protocol:test
 *
 * Host — отдельный, с пустым DSH_HOME: тест создаёт в нём сессии.
 */
class LiveHostContractTest {
    private val launchUrl: String? = System.getenv("DSH_LIVE_HOST")?.takeIf { it.isNotBlank() }

    private fun live(): RemoteClient = runBlocking {
        assumeTrue("DSH_LIVE_HOST is not set", launchUrl != null)
        val url = launchUrl!!.toHttpUrl()
        val base = "${url.scheme}://${url.host}:${url.port}"
        val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(HostCookieJar()).build()
        RemoteClient(base, http).also {
            assertTrue(it.authenticate(base, url.queryParameter("token")) is RemoteResult.Success)
        }
    }

    private fun RemoteClient.newSession(): String = runBlocking {
        val created = createSession("local-host", "QA", System.getenv("DSH_LIVE_WORKSPACE"), null)
        assertTrue("session/create: $created", created is RemoteResult.Success)
        (created as RemoteResult.Success).data.sessionId
    }

    @Test
    fun `created session is listed`() = runBlocking {
        val client = live()
        val id = client.newSession()
        val list = client.listSessions("local-host")
        assertTrue("session/list: $list", list is RemoteResult.Success)
        assertTrue(id in (list as RemoteResult.Success).data.map { it.sessionId })
    }

    @Test
    fun `permission preset is switched and reported back by the list`() = runBlocking {
        val client = live()
        val id = client.newSession()
        val switched = client.selectPermissionPreset("local-host", id, "read-only")
        assertTrue("permission: $switched", switched is RemoteResult.Success)
        val row = (client.listSessions("local-host") as RemoteResult.Success).data.single { it.sessionId == id }
        assertEquals("read-only", row.permissionPreset)
        assertTrue(client.selectPermissionPreset("local-host", id, "no-such-preset") is RemoteResult.Failure)
    }

    @Test
    fun `cancel is accepted for an existing session and refused for a missing one`() = runBlocking {
        val client = live()
        val id = client.newSession()
        assertTrue(client.cancelSession("local-host", id))
        assertFalse(client.cancelSession("local-host", "session-does-not-exist"))
    }

    /**
     * Сквозной чат через клиент приложения: свой провайдер на локальной OpenAI-совместимой заглушке
     * (настройки без expectedRevision — DSH-04), сессия, права, модель, сообщение и ответ модели.
     * Платные API не вызываются: модель — MockWebServer на этом же компьютере.
     */
    @Test
    fun `chat round trip through a custom provider`() = runBlocking {
        val client = live()
        val model = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "GET") {
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"data":[{"id":"qa-model","object":"model"}]}""")
                    } else {
                        val chunk = { delta: String, finish: String? ->
                            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"qa-model","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}""" + "\n\n"
                        }
                        MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(chunk("""{"role":"assistant","content":"QA LOCAL MODEL OK"}""", null) + chunk("{}", "stop") + "data: [DONE]\n\n")
                    }
            }
            start()
        }
        try {
            val ok = """"ok":true"""
            val key = client.rpcRaw("local-host", "credentials/set", """{"ref":"QA_SYNTHETIC_API_KEY","value":"QA-NOT-A-REAL-KEY"}""")
            assertTrue("credentials/set: $key", (key as RemoteResult.Success).data.contains(ok))
            val provider = """{"api":"openai-completions","baseURL":"${model.url("/v1").toString().trimEnd('/')}","apiKeyEnv":"QA_SYNTHETIC_API_KEY","models":[{"id":"qa-model","name":"QA Model"}]}"""
            // Те же аргументы, что строит HostSettingsRepository.setSettings(…, expectedRevision = null).
            val settings = client.rpcRaw("local-host", "settings/mutate", """{"ns":"llm-pi-ai","ops":[{"op":"set","path":["providers","qa-local"],"value":$provider}]}""")
            assertTrue("settings/mutate: $settings", (settings as RemoteResult.Success).data.contains(ok))

            val id = client.newSession()
            assertTrue(client.selectPermissionPreset("local-host", id, "read-only") is RemoteResult.Success)
            val selected = client.selectModel("local-host", id, "qa-model", "qa-local", null)
            assertTrue("selectModel: $selected", selected is RemoteResult.Success)

            val reply = async {
                withTimeout(60_000) {
                    client.subscribeEvents("local-host").filter {
                        it is RemoteEvent.MessageDone && it.sessionId == id && it.message.content.contains("QA LOCAL MODEL OK")
                    }.first()
                }
            }
            kotlinx.coroutines.delay(300)
            val sent = client.sendMessage("local-host", id, "Reply with the fixed QA marker.")
            assertTrue("session/prompt: $sent", sent is RemoteResult.Success)
            reply.await()
            assertTrue("the model stub was not called", model.requestCount > 0)
        } finally {
            model.shutdown()
        }
    }
}
