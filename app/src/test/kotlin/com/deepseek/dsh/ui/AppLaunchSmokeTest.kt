package com.deepseek.dsh.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.deepseek.dsh.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class AppLaunchSmokeTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(StorageGrantRule()).around(compose)

    @Test
    fun launchesAndRendersFirstScreen() {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(3_000)
        ScreenShots.capture(compose, "01_launch_ru", compose.activity)
    }
}
