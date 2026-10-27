package com.dsh.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLimitsTest {
    private val describe = """{"namespaces":[
        {"ns":"web-fetch","revision":1,"value":{"timeoutMs":30000}},
        {"ns":"pwsh-local","revision":2,"value":{"timeoutMs":1,"maxTimeoutMs":2,"maxOutputBytes":3}},
        {"ns":"bash-local","revision":5,"value":{"timeoutMs":300000,"maxTimeoutMs":600000,"maxOutputBytes":64000},
         "base":{"timeoutMs":120000,"maxTimeoutMs":600000,"maxOutputBytes":64000},"user":{"timeoutMs":300000}},
        {"ns":"tool-subagent","revision":1,"value":{"maxDepth":"provider-managed"}},
        {"ns":"subagent","revision":3,"value":{"maxDepth":2,"maxActiveSubagents":8},"base":{"maxDepth":1,"maxActiveSubagents":8},"user":{"maxDepth":2}},
        {"ns":"agent-loop","value":{"maxParallelToolCalls":10}}
    ]}"""

    private fun parse() = parseAgentLimits(hostJson.parseToJsonElement(describe)).associateBy { it.limit }

    @Test
    fun `namespaces are found by fields, bash wins over pwsh and lookalikes`() {
        val l = parse()
        assertEquals("bash-local", l.getValue(AgentLimit.COMMAND_TIME).ns)
        assertEquals("bash-local", l.getValue(AgentLimit.COMMAND_OUTPUT).ns)
        assertEquals("subagent", l.getValue(AgentLimit.SUBAGENT_DEPTH).ns)
        assertEquals("agent-loop", l.getValue(AgentLimit.PARALLEL_CALLS).ns)
    }

    @Test
    fun `value, base, user mark and ceiling`() {
        val time = parse().getValue(AgentLimit.COMMAND_TIME)
        assertEquals(300_000L, time.value)
        assertEquals(120_000L, time.base)
        assertTrue(time.user)
        assertEquals(600_000L, time.ceiling)
        assertEquals(5, time.revision)
        val out = parse().getValue(AgentLimit.COMMAND_OUTPUT)
        assertFalse(out.user)
        assertNull(out.ceiling)
        val depth = parse().getValue(AgentLimit.SUBAGENT_DEPTH)
        assertEquals(2L, depth.value)
        assertEquals(1L, depth.base)
        assertTrue(depth.user)
    }

    @Test
    fun `missing base falls back to the plugin default, missing namespace is skipped`() {
        val parallel = parse().getValue(AgentLimit.PARALLEL_CALLS)
        assertEquals(10L, parallel.base)
        assertFalse(parallel.user)
        assertNull(parallel.revision)
        assertTrue(parseAgentLimits(hostJson.parseToJsonElement("""{"namespaces":[]}""")).isEmpty())
        assertTrue(parseAgentLimits(null).isEmpty())
    }

    @Test
    fun `set and unset go to settings mutate with the revision`() = runBlocking {
        val host = FakeHost { method, _ -> if (method == "settings/mutate") """{"result":{"ok":true}}""" else null }
        val repo = HostSettingsRepository(host.client, HostCalls(host.client))
        val time = parse().getValue(AgentLimit.COMMAND_TIME)
        assertTrue(repo.setAgentLimit("local", time, 60_000))
        assertTrue(repo.setAgentLimit("local", time, null))
        val (set, unset) = host.calls.map { hostJson.parseToJsonElement(it.second).jsonObject }
        assertEquals("bash-local", set["ns"]!!.jsonPrimitive.content)
        assertEquals(5, set["expectedRevision"]!!.jsonPrimitive.content.toInt())
        val op = set["ops"]!!.jsonArray.single().jsonObject
        assertEquals("set", op["op"]!!.jsonPrimitive.content)
        assertEquals(listOf("timeoutMs"), op["path"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(60_000L, op["value"]!!.jsonPrimitive.content.toLong())
        val op2 = unset["ops"]!!.jsonArray.single().jsonObject
        assertEquals("unset", op2["op"]!!.jsonPrimitive.content)
        assertNull(op2["value"])
    }
}
