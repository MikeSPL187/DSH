package com.dsh

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AppLanguageConfigurationTest {
    @Test fun applicationStringsRetainTheSelectedLocaleAfterDeviceConfigurationChanges() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val before = Configuration(app.resources.configuration)
        val previous = Locale.getDefault()
        app.getSharedPreferences("app_locale", 0).edit().putString("selection", "ru").commit()
        try {
            val changed = Configuration(before).apply {
                setLocale(Locale.ENGLISH)
                fontScale = 1.3f
                orientation = Configuration.ORIENTATION_LANDSCAPE
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(changed, app.resources.displayMetrics)
            AppLanguage.refreshLegacyResources(app)
            val actual = app.resources.configuration
            assertEquals("ru", actual.locales[0].language)
            assertEquals(1.3f, actual.fontScale, 0.001f)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, actual.orientation)
            assertEquals(Configuration.UI_MODE_NIGHT_YES, actual.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            Locale.setDefault(Locale.ENGLISH)
            AppLanguage.refreshLegacyResources(app)
            assertEquals("ru", Locale.getDefault().language)
        } finally {
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(before, app.resources.displayMetrics)
            Locale.setDefault(previous)
            app.getSharedPreferences("app_locale", 0).edit().clear().commit()
        }
    }

    @Test fun legacyLocaleLeavesDeviceFontOrientationAndNightModeUnspecified() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val previous = Locale.getDefault()
        app.getSharedPreferences("app_locale", 0).edit().putString("selection", "en").commit()
        var captured: Configuration? = null
        val context = object : ContextWrapper(app) {
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                captured = Configuration(overrideConfiguration)
                return app
            }
        }
        try {
            AppLanguage.wrap(context)
            val localeOverride = requireNotNull(captured)
            assertEquals("en", localeOverride.locales[0].language)
            assertEquals(0f, localeOverride.fontScale, 0f)
            assertEquals(Configuration.ORIENTATION_UNDEFINED, localeOverride.orientation)
            assertEquals(Configuration.UI_MODE_NIGHT_UNDEFINED, localeOverride.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertEquals(Configuration.SCREEN_WIDTH_DP_UNDEFINED, localeOverride.screenWidthDp)
            assertEquals(Configuration.SCREEN_HEIGHT_DP_UNDEFINED, localeOverride.screenHeightDp)
        } finally {
            Locale.setDefault(previous)
            app.getSharedPreferences("app_locale", 0).edit().clear().commit()
        }
    }
}
