package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.runtime.KeyCheck
import com.dsh.feature.runtime.OnboardingScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ключ своего API уходит только на адрес, введённый до конца, а не на недонабранный. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class KeyCheckTargetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun customKeyIsCheckedOnlyAgainstCommittedUrl() {
        val checkedUrls = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme {
                OnboardingScreen(
                    install = com.dsh.feature.runtime.LinuxInstall(),
                    sharedAccessGranted = false,
                    checkKey = { _, url, _ -> checkedUrls += url; KeyCheck.Ok() },
                    onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                    notify = true, onNotifyChange = {}, language = "ru", onLanguage = {},
                    onFinish = {}, onRestoreBackup = {},
                )
            }
        }
        fun tap(text: String) { compose.onAllNodes(hasText(text))[0].performClick(); compose.mainClock.advanceTimeBy(1_200) }
        tap("Начать")
        tap("Свой API")
        val fields = compose.onAllNodes(hasSetTextAction())
        // Ключ уже введён, адрес набирается и замирает на полпути — на чужом домене.
        fields[1].performTextInput("sk-7f3a9c2e41b84d0e9a65")
        compose.mainClock.advanceTimeBy(1_500)
        fields[0].performTextInput("https://openai.co")
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(emptyList<String>(), checkedUrls)

        fields[0].performTextInput("m/v1")
        fields[0].performImeAction()
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf("https://openai.com/v1"), checkedUrls)
    }
}
