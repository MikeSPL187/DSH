package com.dsh

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.runtime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production onboarding UI, physical IME and physical Continue tap. Network is controlled. */
class OnboardingImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun continueWhileImeIsOpenKeepsValidatedInput() {
        val fixture = "qa-onboarding-fake-key-0123456789"
        var checked: String? = null
        var finished: String? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun label(id: Int) = instrumentation.targetContext.getString(id)
        compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent { DshTheme {
            OnboardingScreen(install = LinuxInstall(done = true), sharedAccessGranted = true,
                checkKey = { _, _, key -> checked = key; KeyCheck.Ok() },
                onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                notify = false, onNotifyChange = {}, language = "en", onLanguage = {},
                onFinish = { finished = it?.key })
        } }
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_start)).performClick()
        compose.onNode(hasSetTextAction()).performClick()
        val view = compose.activity.window.decorView
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        instrumentation.sendStringSync(fixture)
        compose.waitUntil(60_000) { checked == fixture }
        val action = compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_continue))
        action.assertIsEnabled()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val ime = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime())
        assertTrue("Continue overlaps keyboard", bounds.bottom <= view.height - ime.bottom)
        val location = IntArray(2); view.getLocationOnScreen(location)
        val down = SystemClock.uptimeMillis()
        for (a in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), a, bounds.center.x + location[0], bounds.center.y + location[1], 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_continue)).performClick()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_begin)).performClick()
        compose.runOnIdle { assertEquals(fixture, checked); assertEquals(fixture, finished) }
    }
    @Test fun skipWithImeAndBackClosesInstallerDetailsFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun label(id: Int) = instrumentation.targetContext.getString(id)
        compose.setContent { DshTheme {
            OnboardingScreen(install = LinuxInstall(stage = 2, hostFailed = true, error = "Host startup failed", errorDetail = "qa-install-detail"),
                sharedAccessGranted = true, checkKey = { _, _, _ -> KeyCheck.Ok() },
                onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                notify = false, onNotifyChange = {}, language = "en", onLanguage = {}, onFinish = {})
        } }
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_start)).performClick()
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_skip_key)).performClick()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_continue)).performClick()
        compose.onNodeWithText(label(com.dsh.feature.runtime.R.string.ob_more)).performScrollTo().performClick()
        compose.onNodeWithText("qa-install-detail").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("qa-install-detail").assertDoesNotExist()
        compose.onNodeWithText("Host startup failed").assertExists()
    }

}
