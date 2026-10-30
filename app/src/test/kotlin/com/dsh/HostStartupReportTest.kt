package com.dsh

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsh.runtime.host.HostProcessRunner
import com.dsh.runtime.host.RuntimePhase
import com.dsh.runtime.host.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostStartupReportTest {
    @Test
    fun `report contains the device and full sanitized log without the runtime token`() {
        val status = RuntimeStatus(
            phase = RuntimePhase.ERROR,
            token = "private-runtime-token",
            lastError = "Host exited (exit=3)",
            logTail = (1..120).map { "sanitized line $it" },
        )
        val report = hostStartupReport(status)
        assertTrue(report.startsWith("DSH Host startup failed\ndevice:"))
        assertTrue(report.contains("\nandroid:"))
        assertTrue(report.contains("\nabi:"))
        assertTrue(report.contains("error: Host exited (exit=3)"))
        assertTrue(report.contains("sanitized line 1\n"))
        assertTrue(report.contains("sanitized line 120\n"))
        assertFalse(report.contains(status.token!!))
    }

    @Test
    fun `copy uses sanitized runner error and shows confirmation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = HostProcessRunner()
        runner.markError("connect http://127.0.0.1:8080/?token=private123")
        val status = runner.statusFlow.value

        copyHostStartupReport(context, status)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(hostStartupReport(status), copied)
        assertTrue(copied.contains("REDACTED"))
        assertFalse(copied.contains("private123"))
        assertEquals(context.getString(R.string.host_startup_copied), ShadowToast.getTextOfLatestToast())
    }
}
