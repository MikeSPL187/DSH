package com.dsh

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshTheme
import com.dsh.core.designsystem.kit.StatRow
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.feature.chat.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Regression for screenshots 2190/2191: actual Android text layouts, not just text presence. */
class ChatTextLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val model = "DeepSeek-V4.1-Flash · High"
    private val amounts = listOf("89,53 CNY", "≈ 0.115–0.23 CNY", "≈ 0.115–0.23 CNY · ≈ 1 USD")
    private data class Frame(val width: Int, val scale: Float, val dark: Boolean = true)

    @Composable
    private fun Frame(frame: Frame, content: @Composable () -> Unit) {
        val context = LocalContext.current
        val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("ru")) }
        val localized = remember(context) { context.createConfigurationContext(config) }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
            LocalDensity provides Density(LocalDensity.current.density, frame.scale)) {
            DshTheme(darkTheme = frame.dark) {
                Box(Modifier.width(frame.width.dp).fillMaxHeight().background(Dsh.colors.bg).statusBarsPadding().testTag("text-viewport")) { content() }
            }
        }
    }

    private fun fullText(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Missing text layout for $text", 1, layouts.size)
        val layout = layouts.single()
        val diagnostic = "text=$text size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} lines=${layout.lineCount} " +
            "constraints=${layout.layoutInput.constraints} maxLines=${layout.layoutInput.maxLines} softWrap=${layout.layoutInput.softWrap} " +
            (0 until layout.lineCount).joinToString { "line$it=${layout.getLineStart(it)}..${layout.getLineEnd(it)} right=${layout.getLineRight(it)} bottom=${layout.getLineBottom(it)}" }
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-text-layout.txt").appendText(diagnostic + "\n")
        if (layout.hasVisualOverflow) screenshot("overflow-" + if (text == model) "model" else "amount")
        assertFalse("Overflow: $diagnostic", layout.hasVisualOverflow)
        repeat(layout.lineCount) { assertFalse("Ellipsis for $text", layout.isLineEllipsized(it)) }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("text-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Text outside horizontal viewport: $text", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "qa-text-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun amountsAndModelStayCompleteAtNarrowWidthsAndLargeFonts() {
        var frame by mutableStateOf(Frame(320, 1f))
        compose.setContent {
            Frame(frame) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    StatRow(amounts.zip(listOf("баланс", "сегодня", "за месяц")) { amount, label -> Triple(amount, label, false) })
                    StRow("Модели и ключи", icon = DshGlyphs.Cpu, value = model, valueBelow = true, chevron = true)
                }
            }
        }
        for (width in listOf(320, 360)) for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { frame = Frame(width, scale) }
            // The repeated estimate is intentionally different from the other cells.
            amounts.forEach(::fullText)
            fullText(model)
            screenshot("settings-$width-$scale")
        }
        compose.runOnIdle { frame = Frame(320, 1.3f, false) }
        amounts.forEach(::fullText); fullText(model)
        screenshot("settings-light")
    }

    @Test fun composerWrapsAllChipsAndKeepsPermissionClickable() {
        var frame by mutableStateOf(Frame(320, 1f))
        var permissionClicks = 0
        compose.setContent {
            Frame(frame) {
                ChatComposer("", {}, "Спросить DSH", SendMode.Stop, {}, {}, Modifier.padding(10.dp), chipsOpen = true,
                    chips = {
                        ComposerChip(model, DshGlyphs.Cpu, accent = true, chevron = true) {}
                        ComposerChip("Только чтение", DshGlyphs.Shield) { permissionClicks++ }
                        ComposerChip("Default", DshGlyphs.Spark) {}
                    })
            }
        }
        for (width in listOf(320, 360)) for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { frame = Frame(width, scale) }
            listOf(model, "Только чтение", "Default").forEach(::fullText)
            compose.onNodeWithText("Только чтение").performClick()
            screenshot("composer-$width-$scale")
        }
        compose.runOnIdle { assertEquals(4, permissionClicks) }
    }

    @Test fun reasoningAndFinishedToolsDoNotShowDoneUntilTheRunEnds() {
        var active by mutableStateOf(true)
        val messages = listOf(
            ChatMessage("local", "s", "q", MessageRole.USER, "Расскажи про архитектуру приложения", createdAt = 1000),
            ChatMessage("local", "s", "t", MessageRole.TOOL, listOf("", "", "call", "read_file", "done", "source", "{}").joinToString("\u0002"), createdAt = 2000),
            ChatMessage("local", "s", "a", MessageRole.ASSISTANT, "Продолжаю изучать архитектуру\u0001", createdAt = 3000),
        )
        compose.setContent { Frame(Frame(360, 1.3f)) { ModernChatScreen(messages, {}, isThinking = active) } }
        compose.onAllNodes(hasText("Готово", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Продолжаю изучать архитектуру", substring = true).assertIsDisplayed()
        screenshot("reasoning-active")
        compose.runOnIdle { active = false }
        compose.onNode(hasText("Готово", substring = true)).assertIsDisplayed()
        screenshot("reasoning-finished")
    }
}
