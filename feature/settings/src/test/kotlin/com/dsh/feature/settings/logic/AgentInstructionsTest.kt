package com.dsh.feature.settings.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentInstructionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `absent files point at AGENTS names, an existing CLAUDE file is edited in place`() {
        val home = tmp.newFolder("home"); val ws = tmp.newFolder("ws")
        val empty = AgentInstructions.files(home, ws).associateBy { it.scope }
        assertEquals("AGENTS.md", empty.getValue(InstructionScope.PROJECT).file.name)
        assertEquals("AGENTS.local.md", empty.getValue(InstructionScope.LOCAL).file.name)
        assertNull(empty.getValue(InstructionScope.GLOBAL).bytes)

        ws.resolve("CLAUDE.md").writeText("# hi")
        val claude = AgentInstructions.files(home, ws).associateBy { it.scope }.getValue(InstructionScope.PROJECT)
        assertEquals("CLAUDE.md", claude.file.name)
        assertEquals(4L, claude.bytes)

        ws.resolve("AGENTS.md").writeText("# agents")
        assertEquals("AGENTS.md", AgentInstructions.files(home, ws).first { it.scope == InstructionScope.PROJECT }.file.name)
    }

    @Test
    fun `write replaces the whole file, read and delete round trip, total sums existing files`() {
        val home = tmp.newFolder("home2"); val ws = tmp.newFolder("ws2")
        val global = AgentInstructions.files(home, ws).first { it.scope == InstructionScope.GLOBAL }.file
        assertTrue(AgentInstructions.write(global, "Отвечай по-русски.\n"))
        assertEquals("Отвечай по-русски.\n", AgentInstructions.read(global).getOrThrow())
        assertFalse(home.listFiles()!!.any { it.name.endsWith(".dsh-tmp") })
        assertEquals(global.length(), AgentInstructions.total(AgentInstructions.files(home, ws)))
        assertTrue(AgentInstructions.delete(global))
        assertNull(AgentInstructions.read(global).getOrThrow())
        assertTrue(AgentInstructions.delete(global))
    }

    @Test
    fun `a file Host would not read is not opened`() {
        val f = tmp.newFile("AGENTS.md").apply { writeBytes(ByteArray((AgentInstructions.MAX_SOURCE + 1).toInt()) { 'a'.code.toByte() }) }
        assertTrue(AgentInstructions.read(f).isFailure)
    }
}
