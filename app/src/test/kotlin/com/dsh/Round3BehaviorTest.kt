package com.dsh

import android.app.Application
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.model.*
import com.dsh.feature.settings.logic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Round3BehaviorTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pluginReturnPreservesOriginAndSession() {
        val ui = AppUiState(AppSettings(), app.filesDir, true)
        ui.selectedSessionId = "chat-1"
        ui.activePage = ActivePage.SETTINGS
        ui.activePage = ActivePage.PLUGINS
        ui.closePlugins()
        assertEquals(ActivePage.SETTINGS, ui.activePage)
        assertEquals("chat-1", ui.selectedSessionId)
        ui.activePage = ActivePage.CHAT
        ui.activePage = ActivePage.PLUGINS
        ui.closePlugins()
        assertEquals(ActivePage.CHAT, ui.activePage)
    }

    @Test fun wifiMeansWifiIncludingMeteredWifi() {
        AutomationStore.init(app)
        val js = app.getSystemService(JobScheduler::class.java)
        js.cancelAll()
        AutomationScheduler.schedule(app, Automation("wifi", "Wifi", wifi = true, charge = true))
        val job = js.allPendingJobs.single()
        assertTrue(job.isRequireCharging)
        assertTrue(job.requiredNetwork!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(job.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    @Test fun interruptedOccurrenceCanRetryButCompletedOccurrenceCannot() {
        assertTrue(AutomationScheduler.claimOccurrence(app, "once", 100))
        assertFalse(AutomationScheduler.claimOccurrence(app, "once", 100))
        AutomationScheduler.releaseOccurrence(app, "once", 100)
        assertTrue(AutomationScheduler.claimOccurrence(app, "once", 100))
        AutomationScheduler.completeOccurrence(app, "once", 100)
        AutomationScheduler.releaseOccurrence(app, "once", 100)
        assertFalse(AutomationScheduler.claimOccurrence(app, "once", 100))
        assertTrue(AutomationScheduler.claimOccurrence(app, "once", 200))
    }

    @Test fun defaultModelIsResolvedOnlyWhenUnambiguous() {
        val model = ModelItem("flash-id", "Flash")
        val catalog = ModelCatalogInfo(groups = listOf(ModelProviderGroup("deepseek", "DeepSeek", listOf(model))))
        val blank = Automation("a", "A")
        assertNull(resolveAutomationModel(blank, catalog, "" to 0))
        val chosen = resolveAutomationModel(blank, catalog, "Flash" to 0)!!
        assertEquals("flash-id", chosen.modelId)
        assertEquals("deepseek", chosen.providerId)
        assertEquals("Flash", chosen.model)
        val ambiguous = catalog.copy(groups = catalog.groups + ModelProviderGroup("custom", "Custom", listOf(model)))
        assertNull(resolveAutomationModel(blank, ambiguous, "Flash" to 0))
        assertEquals("deepseek", resolveAutomationModel(chosen, ambiguous, "Other" to 0)!!.providerId)
    }

    @Test fun archivePreparationSurvivesScreenConsumerAndIsDeliveredOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = BackupExportController(scope)
        val release = CompletableDeferred<Unit>()
        var count = 0
        val file = File(app.cacheDir, "backup.zip")
        try {
            controller.start { count++; release.await(); Result.success(file) }
            controller.start { error("Duplicate export") }
            assertTrue(controller.state.value.busy)
            release.complete(Unit)
            withTimeout(5000) { controller.state.first { it.result != null } }
            assertEquals(file, controller.consume()!!.getOrThrow())
            assertNull(controller.consume())
            assertEquals(1, count)
        } finally { scope.cancel() }
    }

    @Test fun exportedZipHasReadableContentUriAndClipData() {
        val file = File(app.filesDir, "backups/round3.zip")
        file.parentFile!!.mkdirs()
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        shareArchive(app, Result.success(file), "Backup", "Failed")
        val chooser = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("application/zip", send.type)
        assertEquals("content", send.clipData!!.getItemAt(0).uri.scheme)
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        app.contentResolver.openInputStream(send.clipData!!.getItemAt(0).uri)!!.use { input ->
            java.util.zip.ZipInputStream(input).use { assertEquals("manifest.json", it.nextEntry.name) }
        }
    }

    @Test fun stockholmFallBackDoesNotRepeatDailyOccurrence() {
        val zone = ZoneId.of("Europe/Stockholm")
        val a = Automation("dst", "DST", time = "02:30")
        val first = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, zone).withEarlierOffsetAtOverlap()
        val next = AutomationStore.nextRun(a, first.toInstant().toEpochMilli() + 1, zone)!!
        assertEquals(LocalDate.of(2026, 10, 26), Instant.ofEpochMilli(next).atZone(zone).toLocalDate())
    }
}
