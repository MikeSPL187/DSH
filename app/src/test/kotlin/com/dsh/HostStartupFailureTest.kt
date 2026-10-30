package com.dsh

import com.dsh.feature.runtime.LinuxInstall
import com.dsh.feature.runtime.R as RuntimeR
import com.dsh.runtime.host.RuntimePhase
import com.dsh.runtime.host.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStartupFailureTest {
    @Test
    fun `startup detail preserves the reason and only the last twenty log lines`() {
        val status = RuntimeStatus(
            phase = RuntimePhase.ERROR,
            lastError = "Host exited (exit=3)",
            logTail = (1..25).map { "line $it" },
        )

        assertEquals("Host exited (exit=3)\n\n" + (6..25).joinToString("\n") { "line $it" }, hostFailureDetail(status))
        assertEquals(status.lastError, hostFailureDetail(status, includeTail = false))
    }

    @Test
    fun `empty and blank startup diagnostics have no detail`() {
        assertNull(hostFailureDetail(RuntimeStatus(RuntimePhase.ERROR)))
        assertNull(hostFailureDetail(RuntimeStatus(RuntimePhase.ERROR, lastError = " \n", logTail = listOf("", " "))))
    }

    @Test
    fun `identical reason and log are shown once`() {
        val status = RuntimeStatus(RuntimePhase.ERROR, lastError = "boom", logTail = listOf("boom"))
        assertEquals("boom", hostFailureDetail(status))
    }

    @Test
    fun `retry ignores the previous error until a fresh status is published`() {
        val previous = RuntimeStatus(RuntimePhase.ERROR, lastError = "previous", updatedAtMillis = 99)
        assertTrue(isPreviousHostFailure(previous, startedAt = 100))
        val starting = previous.copy(phase = RuntimePhase.STARTING, lastError = null, updatedAtMillis = 100)
        assertFalse(isPreviousHostFailure(starting, startedAt = 100))
        val fresh = previous.copy(lastError = "fresh", logTail = listOf("log"), updatedAtMillis = 100)
        assertFalse(isPreviousHostFailure(fresh, startedAt = 100))
        assertEquals("fresh", hostFailureDetail(fresh, includeTail = false))
    }

    @Test
    fun `host connect error is not classified as an offline install`() {
        val raw = "Host exited (exit=1): connect ECONNREFUSED 127.0.0.1"
        var requestedString = 0
        val friendly = friendlyLinuxInstall(LinuxInstall(stage = 2, hostFailed = true, error = raw)) {
            requestedString = it
            "friendly host error"
        }

        assertEquals(RuntimeR.string.ob_err_host, requestedString)
        assertEquals("friendly host error", friendly.error)
        assertEquals(raw, friendly.errorDetail)
        assertTrue(friendly.hostFailed)
    }

    @Test
    fun `download connect error still uses the offline install message`() {
        var requestedString = 0
        friendlyLinuxInstall(LinuxInstall(error = "connect failed")) {
            requestedString = it
            "offline"
        }
        assertEquals(RuntimeR.string.ob_err_offline, requestedString)
    }
}
