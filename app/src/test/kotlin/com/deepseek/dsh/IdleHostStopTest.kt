package com.deepseek.dsh

import com.deepseek.dsh.runtime.host.RuntimePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleHostStopTest {
    private val minute = 60_000L

    @Test
    fun stopsRunningHostAfterIdleTimeout() {
        assertTrue(shouldIdleStopHost(5, RuntimePhase.RUNNING, false, lastInteractionAt = 0, now = 5 * minute))
    }

    @Test
    fun keepsHostWhileUserIsActiveOrTaskRuns() {
        assertFalse(shouldIdleStopHost(5, RuntimePhase.RUNNING, false, lastInteractionAt = 0, now = 4 * minute))
        assertFalse(shouldIdleStopHost(5, RuntimePhase.RUNNING, true, lastInteractionAt = 0, now = 60 * minute))
    }

    @Test
    fun disabledSettingOrStoppedHostNeverStops() {
        assertFalse(shouldIdleStopHost(0, RuntimePhase.RUNNING, false, lastInteractionAt = 0, now = 60 * minute))
        assertFalse(shouldIdleStopHost(5, RuntimePhase.ERROR, false, lastInteractionAt = 0, now = 60 * minute))
        assertFalse(shouldIdleStopHost(5, RuntimePhase.STARTING, false, lastInteractionAt = 0, now = 60 * minute))
    }

    /** G-13: занятый Host (терминал, фоновый процесс, ожидающее одобрение) не усыпляется; за минуту — предупреждение. */
    @Test
    fun busyHostIsKeptAndWarnedAMinuteBefore() {
        assertFalse(shouldIdleStopHost(5, RuntimePhase.RUNNING, busy = true, lastInteractionAt = 0, now = 60 * minute))
        assertTrue(idleWarningDue(5, RuntimePhase.RUNNING, busy = false, lastInteractionAt = 0, now = 4 * minute + 10_000))
        assertFalse(idleWarningDue(5, RuntimePhase.RUNNING, busy = false, lastInteractionAt = 0, now = 3 * minute))
        assertFalse(idleWarningDue(5, RuntimePhase.RUNNING, busy = true, lastInteractionAt = 0, now = 4 * minute + 10_000))
        assertFalse(idleWarningDue(0, RuntimePhase.RUNNING, busy = false, lastInteractionAt = 0, now = 4 * minute + 10_000))
    }
}
