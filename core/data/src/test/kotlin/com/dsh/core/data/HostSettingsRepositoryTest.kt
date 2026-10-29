package com.dsh.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSettingsRepositoryTest {
    private val describe = hostOk(
        """{"namespaces":[
            {"ns":"ui-theme","revision":7,"value":{"preference":"dark","fontSize":15}},
            {"ns":"session-log-deepseek","revision":3,"value":{"enabled":true}}
        ]}""",
    )

    private fun repository(host: FakeHost) = HostSettingsRepository(host.client, HostCalls(host.client))

    @Test
    fun `namespace reads value and revision`() = runBlocking {
        val host = FakeHost { method, _ -> if (method == "settings/describe") describe else null }
        val theme = repository(host).namespace("local", "ui-theme")!!
        assertEquals(7, theme.revision)
        assertEquals("dark", theme.value["preference"]!!.jsonPrimitive.content)
        assertNull(repository(host).namespace("local", "missing"))
    }

    @Test
    fun `setSettings sends one set op per field with the expected revision`() = runBlocking {
        val host = FakeHost { method, _ -> if (method == "settings/mutate") """{"result":{"ok":true}}""" else null }
        val ok = repository(host).setSettings(
            "local",
            "llm-pi-ai",
            listOf(listOf("providers", "x", "baseURL") to JsonPrimitive("https://api"), listOf("providers", "x", "api") to JsonPrimitive("openai-completions")),
            expectedRevision = 4,
        )
        assertTrue(ok)
        val args = hostJson.parseToJsonElement(host.calls.single().second).jsonObject
        assertEquals("llm-pi-ai", args["ns"]!!.jsonPrimitive.content)
        assertEquals(4, args["expectedRevision"]!!.jsonPrimitive.content.toInt())
        val ops = args["ops"]!!.jsonArray
        assertEquals(2, ops.size)
        assertEquals(listOf("providers", "x", "baseURL"), ops[0].jsonObject["path"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("https://api", ops[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unconditional writes omit expectedRevision instead of sending null`() = runBlocking {
        // DSH-04: Host принимает число или отсутствие поля; JSON null он отклоняет (gateway/input-invalid).
        val host = FakeHost { method, _ -> if (method == "settings/mutate") """{"result":{"ok":true}}""" else null }
        val repository = repository(host)
        assertTrue(repository.setSettings("local", "llm-pi-ai", listOf(listOf("x") to JsonPrimitive(1)), expectedRevision = null))
        assertTrue(repository.unsetSettings("local", "llm-pi-ai", listOf(listOf("x"))))
        host.calls.forEach { (_, args) ->
            assertFalse(args, "expectedRevision" in hostJson.parseToJsonElement(args).jsonObject)
        }
    }

    @Test
    fun `session log toggle writes with the namespace revision`() = runBlocking {
        val host = FakeHost { method, _ ->
            when (method) {
                "settings/describe" -> describe
                "settings/mutate" -> """{"result":{"ok":true}}"""
                else -> null
            }
        }
        val repository = repository(host)
        assertEquals(true, repository.fetchSessionLogUploadEnabled("local"))
        assertTrue(repository.setSessionLogUploadEnabled("local", false))
        val mutate = hostJson.parseToJsonElement(host.calls.last().second).jsonObject
        assertEquals(3, mutate["expectedRevision"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", mutate["ops"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider ids and permission presets are parsed`() = runBlocking {
        val host = FakeHost { method, _ ->
            when (method) {
                "llm/listProviders" -> hostOk("""[{"id":"deepseek-official"},{"name":"no id"},{"id":"kimi"}]""")
                "permissionPresets/catalog" -> hostOk("""{"options":[{"value":"read_only","name":"Read only"},{"value":"auto"}]}""")
                else -> null
            }
        }
        val repository = repository(host)
        assertEquals(listOf("deepseek-official", "kimi"), repository.registeredProviderIds("local"))
        assertEquals(listOf("read_only" to "Read only", "auto" to "auto"), repository.permissionPresets("local"))
    }

    @Test
    fun `host failure gives empty results instead of throwing`() = runBlocking {
        val repository = repository(FakeHost { _, _ -> null })
        assertNull(repository.namespace("local", "ui-theme"))
        assertEquals(emptyList<String>(), repository.registeredProviderIds("local"))
        assertNull(repository.fetchSessionLogUploadEnabled("local"))
    }
}
