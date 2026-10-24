package com.deepseek.dsh

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DshNotificationsTest {
    @Test
    fun notificationOpensTheApp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        shadowOf(context.packageManager).addActivityIfNotPresent(
            android.content.ComponentName(context, MainActivity::class.java),
        )
        shadowOf(context.packageManager).addIntentFilterForActivity(
            android.content.ComponentName(context, MainActivity::class.java),
            android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            },
        )
        DshNotifications.notify(context, "title", "body")
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertNotNull("tapping the notification must open DSH", posted.single().contentIntent)
    }
}
