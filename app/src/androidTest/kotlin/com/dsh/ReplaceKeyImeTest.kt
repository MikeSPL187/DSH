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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Native IME + physical screen coordinates: Save must not target a keyboard key. */
class ReplaceKeyImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveAboveSoftKeyboardKeepsTheExactValidatedKey() {
        val fixture = "qa-round4-fake-key-0123456789"
        val checked = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        val saveSucceeded = AtomicReference<Boolean?>(null)
        val saveCalled = AtomicBoolean(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = com.dsh.feature.settings.logic.ApiKeyVault
        val ref = "QA_IME_KEY"
        vault.init(context)
        vault.delete(ref)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = requireNotNull(automation.serviceInfo).apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent {
            DshTheme {
                SettingsHomeScreen(SettingsUi(), {}, initialSheet = "key", actions = object : SettingsActions {
                    override suspend fun checkKey(baseUrl: String?, key: String): KeyState { checked.set(key); return KeyState.Ok }
                    override suspend fun saveKey(key: String): Boolean {
                        saveCalled.set(true)
                        val ok = vault.save(ref, key)
                        if (ok) saved.set(vault.use(ref) { it })
                        saveSucceeded.set(ok)
                        return ok
                    }
                })
            }
        }
        val field = hasSetTextAction() and hasContentDescription(context.getString(com.dsh.feature.settings.R.string.st_key_field))
        compose.waitUntil(60_000) { compose.activity.hasWindowFocus() }
        compose.onNode(field).performClick()
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        instrumentation.sendStringSync(fixture)
        compose.waitUntil(60_000) { checked.get() == fixture }
        val view = compose.activity.window.decorView
        var roots: List<android.view.accessibility.AccessibilityNodeInfo> = emptyList()
        compose.waitUntil(30_000) {
            roots = automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }
            roots.isNotEmpty()
        }
        var passwordFieldObserved = false
        fun inspect(node: android.view.accessibility.AccessibilityNodeInfo) {
            assertFalse("Key exposed in accessibility", node.text?.let { it.contains(fixture) || it.contains(fixture.takeLast(10)) } == true)
            if (node.isEditable && node.isPassword) passwordFieldObserved = true
            for (i in 0 until node.childCount) node.getChild(i)?.let(::inspect)
        }
        roots.forEach(::inspect)
        assertTrue("Password field absent from accessibility observation", passwordFieldObserved)
        // Show changes only visual rendering: Android's real a11y tree must stay masked.
        compose.onNodeWithContentDescription(context.getString(com.dsh.feature.settings.R.string.st_key_show)).performClick()
        compose.onNodeWithContentDescription(context.getString(com.dsh.feature.settings.R.string.st_key_hide)).assertExists()
        passwordFieldObserved = false
        compose.waitUntil(30_000) {
            roots = automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }
            roots.isNotEmpty()
        }
        roots.forEach(::inspect)
        assertTrue("Revealed key must retain protected accessibility", passwordFieldObserved)
        compose.onNodeWithContentDescription(context.getString(com.dsh.feature.settings.R.string.st_key_hide)).performClick()
        // Showing/hiding can move the sheet or restart IME layout. Measure the actual tap afterwards.
        compose.onNode(field).performClick()
        var lastIme = -1
        var settledAt = SystemClock.uptimeMillis()
        compose.waitUntil(30_000) {
            val insets = ViewCompat.getRootWindowInsets(view)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            if (bottom != lastIme) { lastIme = bottom; settledAt = SystemClock.uptimeMillis() }
            bottom > 0 && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && SystemClock.uptimeMillis() - settledAt >= 300
        }
        val save = compose.onNodeWithText(context.getString(com.dsh.feature.settings.R.string.st_save)).performScrollTo()
        save.assertIsEnabled()
        val bounds = save.fetchSemanticsNode().boundsInRoot
        val ime = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime())
        assertTrue("Save overlaps IME", bounds.bottom <= view.height - ime.bottom)
        val image = requireNotNull(automation.takeScreenshot()) { "Screenshot unavailable" }
        File(context.cacheDir, "round4-ime-save.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val down = SystemClock.uptimeMillis()
        val location = IntArray(2); view.getLocationOnScreen(location)
        val x = bounds.center.x + location[0]; val y = bounds.center.y + location[1]
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Touch injection rejected", automation.injectInputEvent(event, true))
            event.recycle()
        }
        try {
            compose.waitUntil(60_000) { saveSucceeded.get() != null }
        } finally {
            File(context.cacheDir, "round4-ime-state.json").writeText("""{"saveCalled":${saveCalled.get()},"saveSucceeded":${saveSucceeded.get()},"exactValidated":${checked.get() == fixture},"exactStored":${saved.get() == fixture},"buttonBottom":${bounds.bottom},"imeTop":${view.height - ime.bottom}}""")
        }
        assertEquals("Android Keystore rejected Save", true, saveSucceeded.get())
        assertEquals(fixture, saved.get())
        assertEquals(fixture, checked.get())
        vault.delete(ref)
    }
}
