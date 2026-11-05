package com.dsh.core.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SnapshotRecoveryTest {
    private fun replay(records: String, active: String = "null"): List<RemoteEvent> = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"item","value":{"type":"snapshot","records":$records,"assistantStream":{"activeAttempt":$active}}}""")
            }
        }))
        server.start()
        try {
            val client = RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { client.subscribeEvents("local-host").takeWhile { it !is RemoteEvent.SessionSnapshot }.toList() }
            }
            client.followSession("local-host", "s1")
            events.await()
        } finally { server.shutdown() }
    }

    @Test fun historySettlesBeforeCurrentStreamAndBaselineReplacesPartial() {
        val events = replay("""[{"type":"event","event":{"type":"turn/end","seq":2,"time":100,"data":{"kind":"completed"}}}]""",
            """{"stream":[{"chunk":{"type":"text-delta","text":"current"}}]}""")
        val baseline = events.filterIsInstance<RemoteEvent.MessageDelta>().single()
        assertTrue(baseline.baseline)
        assertEquals("current", baseline.delta)
        assertTrue(events.indexOf(baseline) > events.indexOfFirst { it is RemoteEvent.SessionRunning && !it.running })
    }

    @Test fun structuredSandboxErrorAndConsentDenialHaveDistinctFailures() {
        for ((detail, code) in listOf("no read-only sandbox backend" to "sandbox/read-only-unavailable", "[android-consent-required] Not approved" to "approval/required")) {
            val events = replay("""[{"type":"event","event":{"type":"tool/result","seq":3,"time":100,"data":{"callId":"c1","error":{"message":"$detail"}}}}]""")
            assertEquals(code, events.filterIsInstance<RemoteEvent.TurnCompleted>().single().state.errorCode)
        }
    }
}
