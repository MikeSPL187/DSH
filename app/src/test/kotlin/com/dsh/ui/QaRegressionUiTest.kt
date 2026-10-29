package com.dsh.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
