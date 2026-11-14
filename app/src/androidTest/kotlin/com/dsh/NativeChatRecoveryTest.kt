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
            while (app.chatRunJournal.records.value.none { it.text == text && it.partial == "Native " }) delay(100)
        }
        // The provider cannot send another fragment until a distinct prefix checkpoint exists.
        withContext(Dispatchers.IO) {
            val peer = java.net.URL("http://127.0.0.1:38889/qa/recovery-tail")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            try {
                peer.requestMethod = "POST"
                peer.connectTimeout = 10_000
                peer.readTimeout = 10_000
                peer.doOutput = true
                peer.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                assertEquals(200, peer.responseCode)
                peer.inputStream.use { assertTrue(it.readBytes().toString(Charsets.UTF_8).contains("true")) }
            } finally { peer.disconnect() }
        }
        withTimeout(90_000) {
            while (app.chatRunJournal.records.value.none { it.text == text && it.partial.contains("Native partial response") }) delay(100)
        }
        val record = app.chatRunJournal.records.value.single { it.text == text }
        val disk = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<List<SavedPrompt>>(File(app.filesDir, "chat-outbox-v1.json").readText())
            .single { it.id == record.id }
        assertEquals("Native partial response", disk.partial)
        File(app.cacheDir, "qa-native-checkpoint.json").writeText(
            """{"prefixDurable":true,"tailDurableWithoutNextDelta":true}""")
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
            File(app.cacheDir, "qa-native-recovery-state.json").writeText(kotlinx.serialization.json.buildJsonObject {
                put("partialBeforeReplay", kotlinx.serialization.json.JsonPrimitive(record.partial))
                put("partialAfterReplay", kotlinx.serialization.json.JsonPrimitive(interrupted.partial))
                put("status", kotlinx.serialization.json.JsonPrimitive(interrupted.status.name))
            }.toString())
            assertTrue("Partial lost while replaying Host history: ${interrupted.partial}", interrupted.partial.contains("Native partial response"))
            assertTrue("Missing interruption status: ${interrupted.status}", interrupted.status == RunStatus.INTERRUPTED || interrupted.status == RunStatus.CANCELLED)
            assertTrue(interrupted.error.orEmpty().contains("stopped", ignoreCase = true))
            val reason = hasText(interrupted.error!!)
            compose.waitUntil(60_000) { compose.onAllNodes(reason).fetchSemanticsNodes().isNotEmpty() }
            // Keep the interruption and partial answer in view if a previous chat restored IME focus.
            compose.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(30_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
            }
            compose.onNode(reason).performScrollTo().assertIsDisplayed()
            val retry = compose.onAllNodesWithText(app.getString(com.dsh.feature.chat.R.string.chat_retry_send)).onFirst()
            retry.performScrollTo().assertIsDisplayed()
            // Compose can expose nodes from the preceding frame while a startup screen is
            // replacing it. Require the actual Android accessibility window to show both.
            fun visibleText(node: android.view.accessibility.AccessibilityNodeInfo, value: String): Boolean {
                if (node.isVisibleToUser && (node.text?.contains(value) == true || node.contentDescription?.contains(value) == true)) return true
                return (0 until node.childCount).any { node.getChild(it)?.let { child -> visibleText(child, value) } == true }
            }
            var visibleSince = 0L
            val retryLabel = app.getString(com.dsh.feature.chat.R.string.chat_retry_send)
            try {
                compose.waitUntil(60_000) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val visible = root != null && visibleText(root, interrupted.error!!) && visibleText(root, retryLabel) &&
                        visibleText(root, "Native partial response")
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!visible) visibleSince = 0L else if (visibleSince == 0L) visibleSince = now
                    visible && now - visibleSince >= 500
                }
            } finally {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val tree = StringBuilder()
                fun dump(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    tree.appendLine("${" ".repeat(depth)}${node.packageName} visible=${node.isVisibleToUser} bounds=$bounds text=${node.text} desc=${node.contentDescription}")
                    for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1) }
                }
                root?.let { dump(it, 0) }
                File(app.cacheDir, "qa-native-recovery-a11y.txt").writeText(tree.toString())
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.cacheDir, "qa-native-interrupted-chat.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
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
