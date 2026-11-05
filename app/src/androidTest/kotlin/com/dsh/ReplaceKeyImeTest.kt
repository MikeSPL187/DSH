package com.dsh

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.settings.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Native IME + physical screen coordinates: Save must not target a keyboard key. */
class ReplaceKeyImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveAboveSoftKeyboardKeepsTheExactValidatedKey() {
        val fixture = "qa-round4-fake-key-0123456789"
        var checked: String? = null
        var saved: String? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent {
            DshTheme {
                SettingsHomeScreen(SettingsUi(), {}, initialSheet = "key", actions = object : SettingsActions {
                    override suspend fun checkKey(baseUrl: String?, key: String): KeyState { checked = key; return KeyState.Ok }
                    override suspend fun saveKey(key: String): Boolean { saved = key; return true }
                })
            }
        }
        val field = hasSetTextAction() and hasContentDescription(context.getString(com.dsh.feature.settings.R.string.st_key_field))
        compose.waitUntil(60_000) { compose.activity.hasWindowFocus() }
        compose.onNode(field).performClick()
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        instrumentation.sendStringSync(fixture)
        compose.waitUntil(60_000) { checked == fixture }
        val save = compose.onNodeWithText(context.getString(com.dsh.feature.settings.R.string.st_save)).performScrollTo()
        save.assertIsEnabled()
        val bounds = save.fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val ime = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime())
        assertTrue("Save overlaps IME", bounds.bottom <= view.height - ime.bottom)
        var root: android.view.accessibility.AccessibilityNodeInfo? = null
        compose.waitUntil(30_000) {
            root = instrumentation.uiAutomation.rootInActiveWindow
            root != null
        }
        fun inspect(node: android.view.accessibility.AccessibilityNodeInfo) {
            assertFalse("Key exposed in accessibility", node.text?.contains(fixture) == true)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::inspect)
        }
        inspect(requireNotNull(root))
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        File(context.cacheDir, "round4-ime-save.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val down = SystemClock.uptimeMillis()
        val location = IntArray(2); view.getLocationOnScreen(location)
        val x = bounds.center.x + location[0]; val y = bounds.center.y + location[1]
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        compose.waitUntil(60_000) { saved != null }
        assertEquals(fixture, saved)
        assertEquals(fixture, checked)
    }
}
