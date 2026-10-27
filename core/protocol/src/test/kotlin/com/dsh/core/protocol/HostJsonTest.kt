package com.dsh.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Слишком глубокий JSON от Host — ошибка разбора, а не StackOverflowError, который роняет процесс. */
class HostJsonTest {
    @Test
    fun deeplyNestedHostResponseIsAFailureNotAStackOverflow() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[".repeat(50_000) + "]".repeat(50_000)))
        server.start()
        try {
            val client = RemoteClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
            // Раньше StackOverflowError проходил мимо `catch (_: Exception)` и выходил из вызова.
            val result = runBlocking { client.getSessionStats("local-host", "s1") }
            assertTrue(result is RemoteResult.Failure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun depthCountsOnlyBracketsOutsideStrings() {
        assertFalse(jsonDepthExceeds("""{"a":"[[[[[[","b":[1,{"c":"\"]]]"}]}""", 3))
        assertTrue(jsonDepthExceeds("[[[[1]]]]", 3))
        val normal = """{"result":{"ok":true,"value":{"items":[{"id":"a","tags":["x"]}]}}}"""
        assertEquals(Json.parseToJsonElement(normal), Json.parseHostJson(normal))
    }
}
