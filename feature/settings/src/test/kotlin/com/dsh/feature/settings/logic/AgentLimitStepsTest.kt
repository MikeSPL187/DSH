package com.dsh.feature.settings.logic

import com.dsh.core.data.AgentLimit
import com.dsh.core.data.AgentLimitValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLimitStepsTest {
    private fun v(limit: AgentLimit, value: Long, base: Long = limit.default, ceiling: Long? = null) =
        AgentLimitValue(limit, "ns", 1, value, base, user = value != base, ceiling = ceiling)

    @Test
    fun `steps keep an off-grid value and the base`() {
        val steps = AgentLimitSteps.of(v(AgentLimit.SUBAGENT_ACTIVE, value = 7, base = 3))
        assertEquals(listOf(1L, 2, 3, 4, 6, 7, 8, 12, 16), steps)
    }

    @Test
    fun `command time is capped by the Host ceiling`() {
        val steps = AgentLimitSteps.of(v(AgentLimit.COMMAND_TIME, 120_000, ceiling = 300_000))
        assertEquals(listOf(10_000L, 30_000, 60_000, 120_000, 300_000), steps)
    }

    @Test
    fun `next moves one step and stops at the edges`() {
        val s = listOf(1L, 2, 4, 8)
        assertEquals(4L, AgentLimitSteps.next(s, 2, +1))
        assertEquals(1L, AgentLimitSteps.next(s, 2, -1))
        assertEquals(1L, AgentLimitSteps.next(s, 1, -1))
        assertEquals(8L, AgentLimitSteps.next(s, 8, +1))
        // Значение между шагами: «+» — к ближайшему большему, «−» — к меньшему.
        assertEquals(4L, AgentLimitSteps.next(s, 3, +1))
        assertEquals(2L, AgentLimitSteps.next(s, 3, -1))
    }
}
