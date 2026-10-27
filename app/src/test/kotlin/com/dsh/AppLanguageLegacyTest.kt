package com.dsh

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 8–12: выбор языка применяется через attachBaseContext. Регрессия: applySaved()
 * из onCreate вызывал recreate(), и Activity пересоздавалась бесконечно (чёрный экран).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = android.app.Application::class)
class AppLanguageLegacyTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun saveRussian() {
        app.getSharedPreferences("app_locale", Context.MODE_PRIVATE).edit()
            .putString("selection", "ru").commit()
    }

    @Test
    fun applySavedOnStartDoesNotRecreateActivity() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        AppLanguage.applySaved(activity)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("activity was recreated on start", activity.isDestroyed)
    }

    @Test
    fun wrapAppliesSavedLanguage() {
        val wrapped = AppLanguage.wrap(app)
        assertEquals("ru", wrapped.resources.configuration.locales[0].language)
    }
}
