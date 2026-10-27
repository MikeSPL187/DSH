package com.dsh.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsRepositoryTest {
    @Test
    fun `skills of a session without skills replace the previous ones`() = runBlocking {
        var skills = """{"skills":[{"name":"review","description":"Review the diff","modelInvocable":true}]}"""
        val host = FakeHost { method, _ -> if (method == "skills/list") hostOk(skills) else null }
        val commands = CommandsRepository(HostCalls(host.client))

        commands.refreshHostSkills("local", "s1")
        assertEquals(listOf("review"), commands.hostSkillsFlow.value.map { it.name })
        assertTrue(commands.hostSkillsFlow.value.single().modelInvocable)

        skills = """{"skills":[]}"""
        commands.refreshHostSkills("local", "s2")
        assertTrue(commands.hostSkillsFlow.value.isEmpty())
    }

    @Test
    fun `failed read keeps the skills already shown`() = runBlocking {
        var online = true
        val host = FakeHost { method, _ ->
            if (online && method == "skills/list") hostOk("""{"skills":[{"name":"plan"}]}""") else null
        }
        val commands = CommandsRepository(HostCalls(host.client))
        commands.refreshHostSkills("local", "s1")
        online = false
        commands.refreshHostSkills("local", "s1")
        assertEquals(listOf("plan"), commands.hostSkillsFlow.value.map { it.name })
    }

    @Test
    fun `commands are parsed and unknown command returns null`() = runBlocking {
        val host = FakeHost { method, _ ->
            when (method) {
                "commands/list" -> hostOk("""[{"name":"goal","description":"Set a goal","input":{"hint":"objective","attachments":true}},{"description":"no name"}]""")
                "commands/execute" -> hostOk("""{"result":{"kind":"text","text":"Goal set"}}""")
                else -> null
            }
        }
        val commands = CommandsRepository(HostCalls(host.client))
        commands.refreshHostCommands("local", "s1")
        val goal = commands.hostCommandsFlow.value.single()
        assertEquals("objective", goal.inputHint)
        assertTrue(goal.acceptsAttachments)
        assertEquals("Goal set", commands.executeHostCommand("local", "s1", "/goal ship")?.text)

        val offline = CommandsRepository(HostCalls(FakeHost { _, _ -> null }.client))
        assertNull(offline.executeHostCommand("local", "s1", "/unknown"))
    }
}
