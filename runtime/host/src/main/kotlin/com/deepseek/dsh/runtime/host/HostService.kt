package com.deepseek.dsh.runtime.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class HostService : Service() {

    companion object {
        const val CHANNEL_ID = "dsh_host_runtime_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.deepseek.dsh.runtime.host.START"
        const val ACTION_STOP = "com.deepseek.dsh.runtime.host.STOP"
        /** 通知栏「停止」按钮：停止 Host（随后 manager 会发 [ACTION_STOP]）。 */
        const val ACTION_USER_STOP = "com.deepseek.dsh.runtime.host.USER_STOP"

        private const val PREFS = "dsh_host_service"
        private const val KEY_KEEP_ALIVE = "keep_alive_on_task_removed"

        /**
         * 同步设置里的「有任务时保持后台运行」（审计 F-10/N-18）。服务可能在 App UI
         * 不在时被系统重建，所以存一份到 SharedPreferences，而不是只放内存。
         */
        fun setKeepAliveOnTaskRemoved(context: Context, keepAlive: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_KEEP_ALIVE, keepAlive).apply()
        }

        private fun keepAliveOnTaskRemoved(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ALIVE, true)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Обязательство startForegroundService выполняем сразу при создании: на медленном
        // устройстве onStartCommand может прийти позже лимита в 5–10 секунд.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 12+ может запретить foreground из фона: не роняем процесс, сервис
            // повторит startForeground в onStartCommand.
            runCatching {
                startForeground(NOTIFICATION_ID, buildForegroundNotification(getString(R.string.host_service_running)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // stopSelf(startId), а не stopSelf(): если за STOP уже стоит новый
                // startForegroundService (быстрый перезапуск Host), безусловная остановка
                // убивала сервис до его startForeground — система роняла процесс
                // RemoteServiceException (воспроизведено на эмуляторе Android 8).
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                return START_NOT_STICKY
            }
            ACTION_USER_STOP -> {
                stopHostAndSelf(startId)
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildForegroundNotification(getString(R.string.host_service_running))
                startForeground(NOTIFICATION_ID, notification)
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户在设置里打开了「有任务时保持后台运行」：划掉界面不停 Host，前台通知
        // 一直在，可以随时点「停止」。关闭时按旧行为：连同 Host 一起停掉，不在后台
        // 留下 proot/Node 进程。（manifest 里 stopWithTask=false，否则这个回调根本不会触发。）
        if (!keepAliveOnTaskRemoved(this)) {
            stopHostAndSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopHostAndSelf(startId: Int? = null) {
        val manager = HostRuntimeHolder.manager
        Thread {
            if (manager != null) {
                kotlinx.coroutines.runBlocking { runCatching { manager.stop() } }
            }
        }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.host_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.host_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HostService::class.java).setAction(ACTION_USER_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.host_service_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.host_service_stop), stop)
            .build()
    }
}


