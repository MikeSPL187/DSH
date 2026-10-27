package com.dsh.feature.settings.logic

import com.dsh.core.designsystem.kit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BalanceLedgerTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `spending counts decreases and ignores top-ups`() {
        val samples = listOf(at(1, 9) to 20.0, at(10, 9) to 15.0, at(10, 12) to 30.0, at(24, 8) to 13.0, at(24, 18) to 12.4)
        val s = BalanceLedger.summarize(samples, 12.4, at(24, 18), zone)
        assertEquals(0.6, s.today, 1e-9)
        assertEquals(5.0 + 17.0 + 0.6, s.month, 1e-9)
    }

    @Test
    fun `no spending means no forecast`() {
        val s = BalanceLedger.summarize(listOf(at(24, 8) to 10.0), 10.0, at(24, 9), zone)
        assertEquals(0.0, s.today, 1e-9)
        assertNull(s.daysLeft)
    }
}
