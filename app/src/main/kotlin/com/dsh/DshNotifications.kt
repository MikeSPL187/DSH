package com.dsh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object DshNotifications {
    private const val CHANNEL_ID = "dsh_session_events"
    private var nextId = 2000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notify_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = context.getString(R.string.notify_channel_desc) }
                )
            }
        }
    }

    fun notify(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // Нажатие открывает приложение (раньше уведомление никуда не вело).
            .setContentIntent(
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    android.app.PendingIntent.getActivity(
                        context,
                        0,
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                },
            )
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(nextId++, notification)
        }
    }

    /** Своё место для «уснёт через минуту»: одно уведомление, не пересекается с id выше (2000+) и службой Host (1001). */
    private const val IDLE_WARNING_ID = 1900

    /** «Host уснёт через минуту» с кнопкой «Не усыплять» (G-13). */
    fun idleWarning(context: Context) {
        ensureChannel(context)
        val keep = android.app.PendingIntent.getBroadcast(
            context,
            IDLE_WARNING_ID,
            android.content.Intent(context, KeepAwakeReceiver::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.idle_warning_title))
            .setContentText(context.getString(R.string.idle_warning_text))
            .setAutoCancel(true)
            .setTimeoutAfter(IDLE_WARNING_MS)
            .addAction(0, context.getString(R.string.idle_warning_keep), keep)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(IDLE_WARNING_ID, notification) }
    }

    fun cancelIdleWarning(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(IDLE_WARNING_ID) }
    }
}
