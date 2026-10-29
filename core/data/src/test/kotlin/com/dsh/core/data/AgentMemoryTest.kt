package com.dsh.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentMemoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun proposalsAreParsedAndStripped() {
        val reply = "Хорошо, учту.\n<dsh-memory>Деплой — только по пятницам</dsh-memory>\n<dsh-memory>  Пишет на   Kotlin </dsh-memory>"
        assertEquals(listOf("Деплой — только по пятницам", "Пишет на Kotlin"), AgentMemory.proposals(reply))
        assertEquals("Хорошо, учту.", AgentMemory.strip(reply))
        // Ответ ещё идёт: незакрытый тег и его начало не показываются.
        assertEquals("Готово.", AgentMemory.strip("Готово.\n<dsh-memory>Пишет на Ko"))
        assertEquals("Готово.", AgentMemory.strip("Готово.\n<dsh-me"))
        assertEquals("a < b", AgentMemory.strip("a < b"))
        assertEquals(emptyList<String>(), AgentMemory.proposals("без тегов"))
    }

    @Test
    fun secretsAreRefused() {
        val facts = emptyList<MemoryFact>()
        assertEquals(MemoryIssue.SECRET, AgentMemory.issue("Токен: ghp_abcdefghijklmnopqrstuvwx123", facts))
        assertEquals(MemoryIssue.SECRET, AgentMemory.issue("мой ключ sk-proj-abcdefghijklmnop", facts))
        assertEquals(MemoryIssue.SECRET, AgentMemory.issue("пароль от wifi: hunter22", facts))
        assertNull(AgentMemory.issue("Пароли храню в менеджере паролей", facts))
        assertNull(AgentMemory.issue("Пишет на Kotlin", facts))
        assertEquals(MemoryIssue.EMPTY, AgentMemory.issue("   ", facts))
        assertEquals(MemoryIssue.TOO_LONG, AgentMemory.issue("x".repeat(501), facts))
        assertEquals(MemoryIssue.DUPLICATE, AgentMemory.issue("пишет на kotlin", listOf(MemoryFact(1, "Пишет на Kotlin", 0))))
        assertNull(AgentMemory.issue("пишет на kotlin", listOf(MemoryFact(1, "Пишет на Kotlin", 0)), except = 1))
    }

    @Test
    fun blockKeepsForeignTextAndDisappearsWhenOff() {
        val block = AgentMemory.render(MemoryState(true, listOf(MemoryFact(1, "Пишет на Kotlin", 0))))
        assertTrue(block.contains("- Пишет на Kotlin"))
        assertTrue(block.startsWith(AgentMemory.BEGIN) && block.endsWith(AgentMemory.END))
        val withOwn = AgentMemory.merge("# Мои правила\n", block)!!
        assertTrue(withOwn.startsWith("# Мои правила\n\n" + AgentMemory.BEGIN))
        // Повторный вывод заменяет блок, а не дописывает второй.
        val again = AgentMemory.merge(withOwn, AgentMemory.render(MemoryState(true, emptyList())))!!
        assertEquals(1, Regex(Regex.escape(AgentMemory.BEGIN)).findAll(again).count())
        assertFalse(again.contains("Пишет на Kotlin"))
        assertEquals("# Мои правила\n", AgentMemory.merge(again, AgentMemory.render(MemoryState(false))))
        assertNull(AgentMemory.merge(block, ""))
    }

    @Test
    fun storeWritesAgentsFileAndPersists() {
        val state = tmp.newFile("memory.json").also { it.delete() }
        val agents = java.io.File(tmp.root, "rootfs/root/.agents/AGENTS.md")
        AgentMemory.init(state, agents)
        AgentMemory.clear(); AgentMemory.setOn(true)
        assertNull(AgentMemory.add("Пишет на Kotlin", fromChat = true))
        assertEquals(MemoryIssue.DUPLICATE, AgentMemory.add("пишет на  kotlin", fromChat = false))
        assertTrue(agents.readText().contains("- Пишет на Kotlin"))
        assertTrue(AgentMemory.contains("Пишет на Kotlin"))
        AgentMemory.init(state, agents)
        assertEquals(listOf("Пишет на Kotlin"), AgentMemory.state.value.facts.map { it.text })
        AgentMemory.setOn(false)
        assertFalse(agents.exists())
        AgentMemory.setOn(true)
        val id = AgentMemory.state.value.facts.single().id
        assertNull(AgentMemory.edit(id, "Пишет на Kotlin и Compose"))
        AgentMemory.remove(id)
        assertTrue(AgentMemory.state.value.facts.isEmpty())
    }
}
