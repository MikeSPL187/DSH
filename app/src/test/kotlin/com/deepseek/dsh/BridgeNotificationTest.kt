package com.deepseek.dsh

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deepseek.dsh.runtime.bridge.BridgeRequest
import com.deepseek.dsh.runtime.bridge.BridgeServer
import com.deepseek.dsh.runtime.bridge.MAX_NOTIFICATIONS
import com.deepseek.dsh.runtime.host.HostService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Уведомления `android_notify` от плагинов Host не заменяют уведомления DSH и не копятся без предела. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BridgeNotificationTest {
    @Test
    fun pluginNotificationsKeepDshNotificationsAndStayBounded() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        // Уведомление службы Host уже на экране.
        DshNotifications.ensureChannel(context)
        manager.notify(
            HostService.NOTIFICATION_ID,
            androidx.core.app.NotificationCompat.Builder(context, "dsh_session_events")
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Host").build(),
        )
        val tokenFile = File(context.cacheDir, "bridge.token")
        val server = BridgeServer(context, tokenFile)
        server.start()
        try {
            val token = tokenFile.readText()
            // Плагин шлёт уведомления подряд: прежние id «время % 10000» каждый раз новые и рано или поздно попадают на 1001 и 2000+.
            repeat(60) { i ->
                val response = server.dispatchRequest(
                    BridgeRequest(
                        id = "n$i",
                        method = "android_notify",
                        params = buildJsonObject { put("title", "x".repeat(10_000)); put("content", "Agent waits #$i") },
                        auth = token,
                    ),
                )
                assertNull(response.error)
                Thread.sleep(2)
            }
        } finally {
            server.stop()
        }
        val posted = shadowOf(manager).allNotifications
        val host = posted.filter { it.extras.getString("android.title") == "Host" }
        assertEquals("уведомление службы Host на месте", 1, host.size)
        assertTrue("уведомлений плагинов не больше $MAX_NOTIFICATIONS: ${posted.size}", posted.size <= MAX_NOTIFICATIONS + 1)
        assertTrue(posted.all { (it.extras.getCharSequence("android.title")?.length ?: 0) <= 200 })
    }
}
