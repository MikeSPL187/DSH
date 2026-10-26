package com.deepseek.dsh.feature.settings.logic

import com.deepseek.dsh.core.designsystem.kit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AutomationStoreTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val sat = at(24, 12, 38) // суббота

    @Test
    fun `daily runs tomorrow when today's time has passed`() =
        assertEquals(at(25, 3), AutomationStore.nextRun(Automation("a", "x", freq = AutoFreq.Daily, time = "03:00"), sat, zone))

    @Test
    fun `weekdays skip the weekend`() =
        assertEquals(at(26, 8, 45), AutomationStore.nextRun(Automation("a", "x", freq = AutoFreq.Work, time = "08:45"), sat, zone))

    @Test
    fun `weekly picks the chosen day`() =
        assertEquals(at(28, 9), AutomationStore.nextRun(Automation("a", "x", freq = AutoFreq.Week, time = "09:00", days = listOf(2)), sat, zone))

    @Test
    fun `hourly runs at the next full hour`() =
        assertEquals(at(24, 13), AutomationStore.nextRun(Automation("a", "x", freq = AutoFreq.Hour), sat, zone))

    @Test
    fun `turned off has no next run`() =
        assertNull(AutomationStore.nextRun(Automation("a", "x", on = false), sat, zone))
}
