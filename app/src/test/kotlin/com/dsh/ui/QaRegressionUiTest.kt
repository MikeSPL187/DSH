package com.dsh.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.chat.ModernChatScreen
import com.dsh.feature.files.FilesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Экранные сценарии отчёта QA DSH 1.1.0: черновик чата (DSH-10) и действия с подпапкой (DSH-11). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ru-w360dp-h740dp-xxhdpi")
class QaRegressionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(700)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    @Test
    fun unsentChatDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DshTheme { ModernChatScreen(messages = emptyList(), onSendMessage = {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("AUDIT DRAFT 123")
        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithText("AUDIT DRAFT 123").assertExists()
    }

    @Test
    fun subfolderHasActionsForRenameAndDelete() {
        val root = File(compose.activity.cacheDir, "qa-folders").apply { deleteRecursively(); mkdirs() }
        File(root, "nested-folder").mkdirs()
        File(root, "readme.md").writeText("# QA")
        compose.setContent {
            DshTheme {
                FilesScreen(
                    root = root, rootLabel = "QA Project", refreshKey = 0, git = null, onClose = {}, onAttach = {}, onAsk = {},
                    onReview = {}, commitMessage = null, onShare = {}, onImport = {}, onToast = {},
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("nested-folder").fetchSemanticsNodes().isNotEmpty() }
        settle()
        ScreenShots.capture(compose, "qa-folder-actions", compose.activity)
        // «Действия» — у файла и у папки.
        compose.onAllNodesWithContentDescription("Действия").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Действия")[0].performClick()
        settle()
        ScreenShots.capture(compose, "qa-folder-actions-sheet", compose.activity)
        compose.onNodeWithText("Переименовать").assertExists()
        compose.onNodeWithText("Удалить").assertExists()
    }
    @Test
    fun revealedSecretNeverEntersMergedOrUnmergedSemanticsAndRemainsEditable() {
        var key by mutableStateOf("")
        var shown by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                com.dsh.core.designsystem.ProtectedTextField(key, { key = it }, revealed = shown, label = "API key")
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("qa-fake-secret-12345")
        compose.runOnIdle { shown = true }
        settle()
        for (unmerged in listOf(false, true)) {
            val nodes = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = unmerged).fetchSemanticsNodes()
            assertFalse(nodes.any { it.config.toString().contains("qa-fake-secret-12345") })
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { action ->
            action(androidx.compose.ui.text.AnnotatedString("replacement"))
        }
        compose.runOnIdle { assertEquals("replacement", key) }
        ScreenShots.capture(compose, "qa-round2-secret-revealed", compose.activity)
    }

    @Test
    fun appearancePageSurvivesActivityStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DshTheme { com.dsh.feature.settings.ui.SettingsHomeScreen(com.dsh.feature.settings.ui.SettingsUi(), {}) }
        }
        settle()
        compose.onNodeWithText("Оформление").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Фарфор · Светлая").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithText("Фарфор · Светлая").assertExists()
        compose.onNodeWithText("Графит · Тёмная").assertExists()
        ScreenShots.capture(compose, "qa-round2-appearance-restored", compose.activity)
    }

    @Test
    fun darkThemeAppliesImmediatelyWithoutHostAndSurvivesReload() {
        val theme = com.dsh.feature.settings.HostTheme
        theme.init(compose.activity)
        theme.setPreference("system")
        var observedDark = false
        compose.setContent {
            DshTheme(darkTheme = theme.preference == "dark") {
                val dark = com.dsh.core.designsystem.Dsh.colors.isDark
                androidx.compose.runtime.SideEffect { observedDark = dark }
                com.dsh.feature.settings.ui.SettingsHomeScreen(
                    com.dsh.feature.settings.ui.SettingsUi(theme = theme.preference), {},
                    actions = object : com.dsh.feature.settings.ui.SettingsActions {
                        override fun setTheme(value: String) { theme.setPreference(value) }
                    }, initialPage = com.dsh.feature.settings.ui.StPage.Look,
                )
            }
        }
        settle()
        compose.onNodeWithText("Графит · Тёмная").performClick()
        settle()
        compose.runOnIdle { assertEquals("dark", theme.preference); assertEquals(true, observedDark) }
        theme.init(compose.activity)
        assertEquals("dark", theme.preference)
        ScreenShots.capture(compose, "qa-round2-appearance-dark", compose.activity)
        compose.runOnIdle { theme.setPreference("system") }
    }

    @Test fun continueAfterImeDoneKeepsExactlyTheValidatedKey() {
        val key = "qa-only-secret-123456789"
        var validated = ""
        var finished: String? = null
        val ctx = compose.activity
        fun label(id: Int) = ctx.getString(id)
        compose.setContent {
            DshTheme {
                com.dsh.feature.runtime.OnboardingScreen(
                    install = com.dsh.feature.runtime.LinuxInstall(done = true), sharedAccessGranted = true,
                    checkKey = { _, _, value -> validated = value; com.dsh.feature.runtime.KeyCheck.Ok() },
                    onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                    notify = false, onNotifyChange = {}, language = "ru", onLanguage = {},
                    onFinish = { finished = it?.key },
                )
            }
        }
        settle()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_start)).performClick()
        settle()
        compose.onNode(hasSetTextAction()).performTextInput(key)
        settle()
        compose.onNode(hasSetTextAction()).performImeAction()
        settle()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_continue)).performClick()
        settle()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_continue)).performClick()
        settle()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_begin)).performClick()
        compose.runOnIdle { assertEquals(key, validated); assertEquals(key, finished) }
    }

    @Test fun settingsActionsHaveAccessibleNames() {
        compose.setContent { DshTheme { com.dsh.feature.settings.ui.SettingsHomeScreen(com.dsh.feature.settings.ui.SettingsUi(), {}) } }
        settle()
        compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val c = node.config
            val description = c.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
            val text = c.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString()
            org.junit.Assert.assertTrue("Unnamed action: ${node.id}", description.isNotBlank() || text.isNotBlank())
        }
    }

}
