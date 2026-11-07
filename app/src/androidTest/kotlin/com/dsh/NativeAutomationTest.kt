package com.dsh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.mutableStateListOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.feature.settings.ModelProviderUi
import com.dsh.feature.settings.logic.ApiKeyVault
import com.dsh.feature.settings.logic.Automation
import com.dsh.feature.settings.logic.AutomationStore
import com.dsh.core.model.AppSettings
import com.dsh.core.model.RunStatus
import com.dsh.runtime.host.RuntimePhase
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Actual installed Linux/Host, Android Keystore, HTTP model and JobScheduler; no real API key. */
class NativeAutomationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DshApplication
    private val workspace get() = StorageAccess.workspaceRoot(app).absolutePath
    private val ids = listOf("qa-native-scheduled", "qa-native-failed", "qa-native-layout", "qa-native-readonly")

    private suspend fun withProvider(block: suspend () -> Unit) {
        var localAttempted = false
        var localSaved = false
        val manager = ModelProviders(app.dshRepository) { ref, key ->
            localAttempted = true
            ApiKeyVault.save(ref, key).also { localSaved = it }
        }
        val ref = "QA_RUNTIME_API_KEY"
        val fixture = "qa-runtime-synthetic-secret"
        val provider = ModelProviderUi("qa-runtime", "QA runtime", listOf("qa-model", "qa-fail", "qa-readonly"), ref, false,
            settingsNs = "llm-pi-ai", settingsPath = listOf("providers", "qa-runtime"),
            baseUrl = "http://127.0.0.1:38889/v1", declared = false, configured = false)
        val originalDefault = AutomationStore.defaultModel
        try {
            val status = withContext(Dispatchers.IO) { app.runtimeManager.start(workspace).getOrThrow() }
            val accepted = app.bindLocalHost(requireNotNull(status.port), status.token)
            if (!accepted) {
                val diagnosis = app.defaultClient.authenticate("http://127.0.0.1:${status.port}", status.token)
                fail("Host bind failed: phase=${status.phase}, port=${status.port}, tokenPresent=${!status.token.isNullOrBlank()}, auth=$diagnosis")
            }
            val saved = withContext(Dispatchers.Main) {
                manager.save("local-host", provider, fixture, provider.baseUrl, provider.models)
            }
            if (!saved) {
                val credential = app.defaultClient.rpcRaw("local-host", "credentials/set", """{"ref":"$ref","value":"$fixture"}""")
                val ops = buildJsonArray {
                    for ((field, value) in listOf("apiKeyEnv" to JsonPrimitive(ref), "api" to JsonPrimitive("openai-completions"),
                        "baseURL" to JsonPrimitive(provider.baseUrl), "models" to JsonArray(provider.models.map { buildJsonObject { put("id", it); put("name", it) } }))) {
                        add(buildJsonObject { put("op", "set"); put("path", JsonArray((provider.settingsPath + field).map(::JsonPrimitive))); put("value", value) })
                    }
                }
                val settings = app.defaultClient.rpcRaw("local-host", "settings/mutate", buildJsonObject { put("ns", provider.settingsNs); put("ops", ops) }.toString())
                val diagnosis = buildJsonObject {
                    put("localAttempted", localAttempted); put("localSaved", localSaved)
                    put("credentials", credential.toString().replace(fixture, "[fixture]"))
                    put("settings", settings.toString().replace(fixture, "[fixture]"))
                }
                File(app.cacheDir, "qa-native-provider-save-diagnostic.json").writeText(diagnosis.toString())
                fail("Provider save failed: $diagnosis")
            }
            assertEquals(fixture, ApiKeyVault.use(ref) { it })
            block()
        } catch (error: Throwable) {
            runCatching { screenshot("qa-native-integration-failure.png") }
            File(app.cacheDir, "qa-native-integration-state.txt").writeText(ids.joinToString("\n") { id ->
                AutomationStore.get(id)?.let { "$id: ${AutomationScheduler.status(app, it)}; history=${it.history}" } ?: "$id: absent"
            })
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                for (id in ids) {
                    AutomationStore.get(id)?.let { AutomationStore.upsert(it.copy(on = false)) }
                    AutomationScheduler.cancel(app, id)
                    AutomationStore.delete(id)
                }
                AutomationStore.setDefaultModel(originalDefault.first, originalDefault.second)
                manager.remove("local-host", provider)
                ApiKeyVault.delete(ref)
            }
        }
    }

    @Test fun scheduledFireInheritsDefaultModel(): Unit = runBlocking {
        withProvider {
            // Wait for a real future wall-clock occurrence. No Run now, forced job or clock jump.
            val next = LocalDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
            val a = Automation(ids[0], "Native scheduled ping", time = next.format(DateTimeFormatter.ofPattern("HH:mm")),
                project = workspace, prompt = "Reply ping.")
            withContext(Dispatchers.Main) {
                AutomationStore.setDefaultModel("qa-model", 0)
                AutomationStore.upsert(a)
                AutomationScheduler.schedule(app, a)
            }
            compose.onNodeWithContentDescription(app.getString(com.dsh.feature.chat.R.string.ck_menu)).performClick()
            compose.onNodeWithText("Settings").performClick()
            withTimeout(240_000) {
                while (AutomationStore.get(a.id)?.history?.firstOrNull()?.status !in setOf("ok", "err")) {
                    assertEquals("Host stopped while Settings remained visible", RuntimePhase.RUNNING, app.runtimeManager.getStatus().phase)
                    delay(250)
                }
            }
            val completed = requireNotNull(AutomationStore.get(a.id))
            assertEquals("Scheduled run: " + completed.history.first().result, "ok", completed.history.first().status)
            assertEquals("qa-model", completed.modelId)
            assertEquals("qa-runtime", completed.providerId)
            delay(1500)
            assertEquals("Scheduled occurrence was duplicated", 1, AutomationStore.get(a.id)!!.history.size)
            report("automation", """{"scheduled":"ok","historyCount":1,"model":"qa-model","idleHost":"running"}""")
        }
    }

    @Test fun providerFailureReachesTerminalHistory(): Unit = runBlocking {
        withProvider {
            val fail = Automation(ids[1], "Native refused model", project = workspace, model = "qa-fail",
                modelId = "qa-fail", providerId = "qa-runtime", prompt = "Return a controlled error.")
            withContext(Dispatchers.Main) { AutomationStore.upsert(fail) }
            assertTrue(withTimeout(120_000) { AutomationRunner.run(app, fail.id) })
            val run = AutomationStore.get(fail.id)!!.history.first()
            assertEquals("Error left automation running: " + run.result, "err", run.status)
            assertTrue("Error explanation absent", run.result.isNotBlank())
            report("provider-error", """{"providerFailure":"err"}""")
        }
    }

    @Test fun realChatLastReplyStaysAboveImeAndRetainsDraftOnRotation(): Unit = runBlocking {
        withProvider {
            val prompt = ("Reply ping. " + "Check rotation and the right edge. ".repeat(4)).trim()
            val a = Automation(ids[2], "Native chat layout", project = workspace, model = "qa-model",
                modelId = "qa-model", providerId = "qa-runtime", prompt = prompt)
            withContext(Dispatchers.Main) { AutomationStore.upsert(a) }
            assertTrue(withTimeout(120_000) { AutomationRunner.run(app, a.id) })
            val run = AutomationStore.get(a.id)!!.history.first()
            assertEquals("Chat fixture: " + run.result, "ok", run.status)
            assertNotNull(run.sessionId)
            compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription(app.getString(com.dsh.feature.chat.R.string.ck_menu)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(app.getString(com.dsh.feature.chat.R.string.ck_menu)).performClick()
            compose.onNodeWithText(app.getString(com.dsh.feature.sessions.R.string.dw_automation)).performClick()
            compose.onNodeWithText(a.name).performScrollTo().performClick()
            val history = hasClickAction() and hasText(run.result, substring = true) and hasText("Today", substring = true)
            compose.onNode(history).performScrollTo().performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("chat-thread").fetchSemanticsNodes().isNotEmpty() }
            val answer = hasText("ping") and hasAnyAncestor(hasTestTag("chat-thread"))
            compose.waitUntil(60_000) { compose.onAllNodes(answer).fetchSemanticsNodes().isNotEmpty() }
            val oldScale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
            val oldOrientation = compose.activity.requestedOrientation
            try {
                shell("settings put system font_scale 1.3")
                compose.waitUntil(30_000) { compose.activity.resources.configuration.fontScale > 1.29f }
                val editor = hasSetTextAction() and hasAnyAncestor(hasTestTag("chat-composer"))
                val draft = "Keep this draft through portrait and landscape."
                compose.onNode(editor).performClick().performTextInput(draft)
                compose.waitUntil(30_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                compose.waitUntil(30_000) {
                    val bottom = compose.onNode(answer).fetchSemanticsNode().boundsInRoot.bottom
                    val top = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top
                    bottom > 0 && bottom <= top
                }
                screenshot("qa-native-chat-ime-font13.png")
                shell("input keyevent 4")
                compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(30_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                compose.onNode(editor).assertTextContains(draft)
                compose.onNode(hasText(prompt) and hasAnyAncestor(hasTestTag("chat-thread"))).performScrollTo().assertIsDisplayed()
                screenshot("qa-native-chat-landscape-font13.png")
                report("chat-layout", """{"imeAboveComposer":true,"fontScale":1.3,"landscapeDraftRetained":true}""")
            } finally {
                shell("settings put system font_scale $oldScale")
                compose.runOnUiThread { compose.activity.requestedOrientation = oldOrientation }
            }
        }
    }

    @Test fun offlineSendKeepsTheReasonAndRetriesAfterNetworkReturns(): Unit = runBlocking {
        withProvider {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            fun connected(): Boolean = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val originalWifi = shell("settings get global wifi_on").trim()
            val originalData = shell("settings get global mobile_data").trim()
            val text = "Native offline request with an explicit reason"
            try {
                shell("svc wifi disable")
                shell("svc data disable")
                withTimeout(30_000) { while (connected()) delay(100) }
                val sender = ChatSender(app, app.applicationScope, app.dshRepository,
                    AppUiState(AppSettings(), app.filesDir, true), AppRuntimeUiState(false, 0f to ""),
                    app.runtimeManager, mutableStateListOf(), { StorageAccess.workspaceRoot(app) })
                withContext(Dispatchers.Main) { sender.send(text, ModelChoice("qa-model", "qa-runtime")) }
                withTimeout(30_000) { while (app.chatRunJournal.records.value.none { it.text == text && it.status == RunStatus.FAILED }) delay(100) }
                val failed = app.chatRunJournal.records.value.single { it.text == text }
                assertEquals("No network connection. Connect and retry.", failed.error)
                assertNull("An offline request should not create a Host session", failed.sessionId)
                if (originalWifi != "0") shell("svc wifi enable")
                if (originalData == "1") shell("svc data enable")
                withTimeout(30_000) { while (!connected()) delay(100) }
                withContext(Dispatchers.Main) { sender.retry(failed.id) }
                withTimeout(120_000) { while (app.chatRunJournal.records.value.any { it.text == text }) delay(100) }
                report("offline", """{"reason":"offline","createdSessionWhileOffline":false,"retry":"ok"}""")
            } finally {
                shell("svc wifi " + if (originalWifi == "0") "disable" else "enable")
                shell("svc data " + if (originalData == "1") "enable" else "disable")
            }
        }
    }

    @Test fun readOnlyShellDenialFinishesAutomationWithAnExplanation(): Unit = runBlocking {
        withProvider {
            val a = Automation(ids[3], "Native read-only shell", project = workspace, model = "qa-readonly",
                modelId = "qa-readonly", providerId = "qa-runtime", perm = "read", prompt = "Read kernel information and report; do not change files.")
            withContext(Dispatchers.Main) { AutomationStore.upsert(a) }
            assertTrue(withTimeout(120_000) { AutomationRunner.run(app, a.id) })
            val run = AutomationStore.get(a.id)!!.history.first()
            assertTrue("Read-only shell left automation running", run.status in setOf("ok", "err"))
            assertTrue("No explicit read-only denial: " + run.result,
                Regex("sandbox|denied|not permitted", RegexOption.IGNORE_CASE).containsMatchIn(run.result))
            report("readonly", """{"shellDenied":true,"terminalHistory":true}""")
        }
    }

    private fun report(name: String, json: String) = File(app.cacheDir, "qa-native-$name.json").writeText(json)
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
}
