package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.protocol.ForwardedEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsRepositoryTest {
    private fun plugins(host: FakeHost) = PluginsRepository(host.client, HostCalls(host.client))

    @Test
    fun `credential status never needs the secret value`() = runBlocking {
        val host = FakeHost { method, _ ->
            if (method == "credentials/describe") {
                hostOk("""{"DEEPSEEK_API_KEY":{"configured":true,"writable":false,"source":"env"}}""")
            } else null
        }
        val status = plugins(host).credentialStatus("local", "DEEPSEEK_API_KEY")!!
        assertTrue(status.configured)
        assertFalse(status.writable)
        assertEquals("env", status.source)
        assertNull(plugins(host).credentialStatus("local", "OTHER_KEY"))
    }

    @Test
    fun `set and unset credential report what host accepted`() = runBlocking {
        val host = FakeHost { method, _ ->
            when (method) {
                "credentials/set" -> """{"result":{"ok":true}}"""
                "credentials/unset" -> """{"result":{"ok":false}}"""
                else -> null
            }
        }
        val plugins = plugins(host)
        assertTrue(plugins.setCredential("local", "K", "sk-1234"))
        assertFalse(plugins.unsetCredential("local", "K"))
        val set = hostJson.parseToJsonElement(host.calls.first().second).jsonObject
        assertEquals("K", set["ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bundle change outcome carries restart reason and warnings`() = runBlocking {
        val host = FakeHost { method, _ ->
            if (method == "pluginManager/setBundleEnabled") {
                hostOk("""{"application":"applied","restartReason":"no-hmr","warnings":["Reload the page"]}""")
            } else null
        }
        val outcome = plugins(host).setBundleEnabled("local", "@x/bundle", true).getOrThrow()
        assertTrue(outcome.restartRequired)
        assertEquals(listOf("Reload the page"), outcome.warnings)
    }

    @Test
    fun `rejected bundle change is a failure with the host code`() = runBlocking {
        val host = FakeHost { _, _ -> """{"result":{"ok":false,"error":{"code":"plugin/not-found"}}}""" }
        val result = plugins(host).setBundleEnabled("local", "@x/bundle", false)
        assertTrue(result.isFailure)
        assertEquals("plugin/not-found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `install progress follows only the current request`() = runBlocking {
        val host = FakeHost { _, _ -> null }
        val plugins = plugins(host)
        // Без идущей установки события пропускаются.
        plugins.onEventPayload(event("plugin-manager/install-state", """{"phase":"resolving"}"""))
        assertNull(plugins.installProgressFlow.value)
    }

    private fun event(name: String, payload: String) =
        ForwardedEvent(event = name, args = JsonArray(listOf(hostJson.parseToJsonElement(payload))))
}
