package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.runtime.KeyCheck
import com.dsh.feature.runtime.OnboardingScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA F004: после «Показать ключ» ключ виден на экране, но службы специальных возможностей получают только точки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class SecretA11yTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shownKeyIsNotExposedToAccessibility() {
        val key = "sk-7f3a9c2e41b84d0e9a65aa"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme {
                OnboardingScreen(
                    install = com.dsh.feature.runtime.LinuxInstall(),
                    sharedAccessGranted = false,
                    checkKey = { _, _, _ -> KeyCheck.Ok() },
                    onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                    notify = true, onNotifyChange = {}, language = "ru", onLanguage = {},
                    onFinish = {}, onRestoreBackup = {},
                )
            }
        }
        compose.onAllNodes(hasText("Начать"))[0].performClick(); compose.mainClock.advanceTimeBy(1_200)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(key)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(hasContentDescription("Показать ключ")).performClick()
        compose.mainClock.advanceTimeBy(800)
        val node = compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode()
        val exposed = if (node.config.contains(SemanticsProperties.EditableText)) node.config[SemanticsProperties.EditableText].text else ""
        assertFalse("ключ ушёл в службы специальных возможностей: $exposed", exposed.contains("7f3a9c2e"))
        assertTrue(node.config.contains(SemanticsProperties.Password))
    }
}
