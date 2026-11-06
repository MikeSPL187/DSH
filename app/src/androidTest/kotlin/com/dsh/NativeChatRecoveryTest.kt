package com.dsh

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.core.model.AppSettings
import com.dsh.core.model.RunStatus
import com.dsh.feature.settings.ModelProviderUi
import com.dsh.feature.settings.logic.ApiKeyVault
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Two instrumentation invocations separated by a real adb force-stop from the QA driver. */
class NativeChatRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DshApplication
    private val ref = "QA_RECOVERY_API_KEY"
    private val text = "Native interrupted request: keep the partial response and offer Retry"
    private val provider = ModelProviderUi("qa-recovery", "QA recovery", listOf("qa-recovery"), ref, false,
        settingsNs = "llm-pi-ai", settingsPath = listOf("providers", "qa-recovery"),
        baseUrl = "http://127.0.0.1:38889/v1", declared = false, configured = false)

    private suspend fun bind() {
        val workspace = StorageAccess.workspaceRoot(app).absolutePath
        val status = withContext(Dispatchers.IO) { app.runtimeManager.start(workspace).getOrThrow() }
        val accepted = app.bindLocalHost(requireNotNull(status.port), status.token)
        if (!accepted) {
            val diagnosis = app.defaultClient.authenticate("http://127.0.0.1:${status.port}", status.token)
            fail("Host bind failed: phase=${status.phase}, port=${status.port}, tokenPresent=${!status.token.isNullOrBlank()}, auth=$diagnosis")
        }
        app.dshRepository.subscribeRemoteEvents("local-host")
        app.dshRepository.refreshSessions("local-host")
    }

    @Test fun startInterruptedRequest(): Unit = runBlocking {
        bind()
        assertTrue(withContext(Dispatchers.Main) {
            ModelProviders(app.dshRepository).save("local-host", provider, "qa-runtime-synthetic-secret", provider.baseUrl, provider.models)
        })
        val sender = ChatSender(app, app.applicationScope, app.dshRepository,
            AppUiState(AppSettings(), app.filesDir, true), AppRuntimeUiState(false, 0f to ""),
            app.runtimeManager, mutableStateListOf(), { StorageAccess.workspaceRoot(app) })
        withContext(Dispatchers.Main) { sender.send(text, ModelChoice("qa-recovery", "qa-recovery")) }
        withTimeout(90_000) {
            while (app.chatRunJournal.records.value.none { it.text == text && it.partial.contains("Native partial response") }) delay(100)
        }
        val record = app.chatRunJournal.records.value.single { it.text == text }
        assertNotNull(record.sessionId)
        assertTrue(record.status in ChatRunJournal.ACTIVE)
        compose.activityRule.scenario.recreate()
        delay(1500)
        assertTrue("Activity recreation interrupted an application-owned request",
            app.chatRunJournal.records.value.single { it.id == record.id }.status in ChatRunJournal.ACTIVE)
        File(app.cacheDir, "qa-native-recreation.json").writeText("""{"activityRecreated":true,"requestStillActive":true}""")
        File(app.cacheDir, "qa-native-recovery-ready.txt").writeText(record.id)
        // Deliberately remain inside instrumentation until the driver kills the real process.
        // Teardown cannot run in a killed process; the second invocation removes this provider.
        awaitCancellation()
    }

    @Test fun recoverInterruptedRequestAndRetryFromChat(): Unit = runBlocking {
        try {
            bind()
            val id = File(app.cacheDir, "qa-native-recovery-ready.txt").readText()
            val record = app.chatRunJournal.records.value.single { it.id == id }
            assertEquals(text, record.text)
            assertTrue("Partial text lost on disk", record.partial.contains("Native partial response"))
            app.dshRepository.watchSession("local-host", requireNotNull(record.sessionId))
            withTimeout(90_000) {
                while (app.chatRunJournal.records.value.single { it.id == id }.status in ChatRunJournal.ACTIVE) delay(100)
            }
            val interrupted = app.chatRunJournal.records.value.single { it.id == id }
            assertTrue("Missing interruption status: ${interrupted.status}", interrupted.status == RunStatus.INTERRUPTED || interrupted.status == RunStatus.CANCELLED)
            assertTrue(interrupted.error.orEmpty().contains("stopped", ignoreCase = true))
            val reason = hasText(interrupted.error!!)
            compose.waitUntil(60_000) { compose.onAllNodes(reason).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(reason).performScrollTo().assertIsDisplayed()
            val retry = compose.onAllNodesWithText(app.getString(com.dsh.feature.chat.R.string.chat_retry_send)).onFirst()
            retry.performScrollTo().assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.cacheDir, "qa-native-interrupted-chat.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            retry.performClick()
            withTimeout(120_000) { while (app.chatRunJournal.records.value.any { it.text == text }) delay(100) }
            File(app.cacheDir, "qa-native-recovery.json").writeText("""{"forceStop":true,"partialRetained":true,"interruptionVisible":true,"retry":"ok"}""")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                ModelProviders(app.dshRepository).remove("local-host", provider)
                ApiKeyVault.delete(ref)
            }
        }
    }
}
