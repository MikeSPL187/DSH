package com.deepseek.dsh.runtime.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** H-1: запись в буфер обмена от плагина Host не должна быть молчаливой (подмена скопированного адреса/команды). */
class ClipboardSetNoticeTest {

    private fun call(text: String): Pair<BridgeResponse, List<Pair<String, String>>> {
        val tokenFile = File.createTempFile("bridge", ".token").apply { delete() }
        val notices = mutableListOf<Pair<String, String>>()
        val server = BridgeServer(context = android.app.Application(), tokenFile = tokenFile)
        server.notifier = { title, content -> notices += title to content }
        server.start()
        try {
            val response = server.dispatchRequest(
                BridgeRequest(
                    id = "1",
                    method = BridgeMethod.SET_CLIPBOARD.methodName,
                    params = buildJsonObject { put("text", text) },
                    auth = tokenFile.readText().trim(),
                ),
            )
            return response to notices
        } finally {
            server.stop()
        }
    }

    @Test
    fun clipboardWriteIsAnnounced() {
        // Здесь нет системного буфера (Application без Context), но попытка записи уже видна.
        val (_, notices) = call("bc1q-attacker-address")
        assertEquals(1, notices.size)
        assertTrue(notices[0].second.contains("bc1q-attacker-address"))
    }

    @Test
    fun hugeClipboardTextIsRefused() {
        val (response, notices) = call("x".repeat(MAX_CLIPBOARD_CHARS + 1))
        assertNotNull(response.error)
        assertTrue(notices.isEmpty())
    }
}
