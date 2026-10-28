package com.dsh.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsTest {
    private fun describe(provider: String = "deepseek-official", send: Boolean = true) = hostJson.parseToJsonElement(
        """{"namespaces":[
            {"ns":"web","revision":4,"value":{"searchProvider":"$provider","fetchProvider":"http"},
             "base":{"searchProvider":"deepseek-official","fetchProvider":"http"}},
            {"ns":"web-search-deepseek","revision":2,"value":{"model":"deepseek-v4-flash","apiVersion":"2023-06-01","maxTokens":4096,"maxUses":5},"base":{"maxUses":5}},
            {"ns":"web-deepseek-api","revision":7,"value":{"count":10,"sendOriginalQuestion":$send},"base":{"count":10,"sendOriginalQuestion":true}}
        ]}""",
    )

    private fun ops(o: JsonObject) = o["ops"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `state and web limits are read by fields`() {
        val state = parseWebSearch(describe())
        assertTrue(state.apiAvailable)
        assertFalse(state.useApi)
        assertEquals(true, state.sendQuestion)
        assertTrue(parseWebSearch(describe("deepseek-search-api")).useApi)
        val limits = parseAgentLimits(describe()).associateBy { it.limit }
        assertEquals("web-deepseek-api", limits.getValue(AgentLimit.WEB_RESULTS).ns)
        assertEquals(50L, limits.getValue(AgentLimit.WEB_RESULTS).ceiling)
        assertEquals("web-search-deepseek", limits.getValue(AgentLimit.WEB_SEARCHES).ns)
    }

    @Test
    fun `without the web namespaces the switch is unavailable`() {
        val state = parseWebSearch(hostJson.parseToJsonElement("""{"namespaces":[]}"""))
        assertFalse(state.apiAvailable)
        assertNull(state.sendQuestion)
        assertNull(webApiMutation(state, true))
        assertNull(sendQuestionMutation(state, false))
    }

    @Test
    fun `switch sets both providers, switching back to the base unsets them`() {
        val on = webApiMutation(parseWebSearch(describe()), true)!!
        assertEquals("web", on["ns"]!!.jsonPrimitive.content)
        assertEquals(4, on["expectedRevision"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            listOf("searchProvider" to "deepseek-search-api", "fetchProvider" to "deepseek-fetch-api"),
            ops(on).map { it["path"]!!.jsonArray.single().jsonPrimitive.content to it["value"]!!.jsonPrimitive.content },
        )
        val off = webApiMutation(parseWebSearch(describe("deepseek-search-api")), false)!!
        assertEquals(listOf("unset", "unset"), ops(off).map { it["op"]!!.jsonPrimitive.content })
        assertTrue(ops(off).all { it["value"] == null })
    }

    @Test
    fun `send question writes false and unsets back to the default`() = runBlocking {
        val host = FakeHost { method, _ -> if (method == "settings/mutate") """{"result":{"ok":true}}""" else null }
        val repo = HostSettingsRepository(host.client, HostCalls(host.client))
        assertTrue(repo.setWebSendQuestion("local", parseWebSearch(describe()), false))
        assertTrue(repo.setWebSendQuestion("local", parseWebSearch(describe(send = false)), true))
        val (off, on) = host.calls.map { hostJson.parseToJsonElement(it.second).jsonObject }
        assertEquals("web-deepseek-api", off["ns"]!!.jsonPrimitive.content)
        assertEquals("set", ops(off).single()["op"]!!.jsonPrimitive.content)
        assertEquals("false", ops(off).single()["value"]!!.jsonPrimitive.content)
        assertEquals("unset", ops(on).single()["op"]!!.jsonPrimitive.content)
    }
}
