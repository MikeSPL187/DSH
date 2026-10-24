package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deepseek.dsh.runtime.host.HostProcessRunner
import com.deepseek.dsh.runtime.host.HostRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// Эффекты жизненного цикла Host вынесены из MainAppHost (аудит F-13): основной метод
// и так близок к лимиту размера, а эти блоки независимы от остального UI.

/**
 * Android 13+: без runtime-разрешения POST_NOTIFICATIONS система молча блокирует все
 * уведомления DSH (готово, ошибка, запрос одобрения). Спрашиваем один раз, пока
 * уведомления включены в настройках.
 */
@Composable
internal fun NotificationPermissionEffect(notificationsEnabled: Boolean) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    LaunchedEffect(notificationsEnabled) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (!notificationsEnabled) return@LaunchedEffect
        val prefs = context.getSharedPreferences("dsh_permissions", android.content.Context.MODE_PRIVATE)
        val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("notifications_asked", false)) {
            prefs.edit().putBoolean("notifications_asked", true).apply()
            runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

/**
 * «Авто-сон при простое»: останавливает локальный Host после [idleMinutes] минут без
 * действий (если не идёт задача агента) и снова запускает его при следующем касании.
 */
@Composable
internal fun IdleHostStopEffect(
    runtimeManager: HostRuntimeManager,
    runner: HostProcessRunner,
    idleMinutes: Int,
    anySessionRunning: Boolean,
    workspace: File,
) {
    val minutes by rememberUpdatedState(idleMinutes)
    val running by rememberUpdatedState(anySessionRunning)
    val currentWorkspace by rememberUpdatedState(workspace)
    var stoppedForIdle by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (shouldIdleStopHost(
                    idleMinutes = minutes,
                    phase = runner.statusFlow.value.phase,
                    anySessionRunning = running,
                    lastInteractionAt = UserActivity.lastInteraction.value,
                    now = System.currentTimeMillis(),
                )
            ) {
                stoppedForIdle = true
                withContext(Dispatchers.IO) { runCatching { runtimeManager.stop() } }
            }
        }
    }
    LaunchedEffect(Unit) {
        UserActivity.lastInteraction.collect {
            if (stoppedForIdle) {
                stoppedForIdle = false
                withContext(Dispatchers.IO) { runCatching { runtimeManager.start(currentWorkspace.absolutePath) } }
            }
        }
    }
}

/**
 * Агент ждёт решения, а приложение свёрнуто: без уведомления запрос молча истекал,
 * и задача завершалась отказом.
 */
@Composable
internal fun AgentWaitingNotificationsEffect(
    approvalIds: List<String>,
    questionIds: List<String>,
    notificationsEnabled: Boolean,
) {
    val context = LocalContext.current
    val notified = remember { mutableSetOf<String>() }
    LaunchedEffect(approvalIds, questionIds) {
        val waiting = approvalIds.map { it to true } + questionIds.map { it to false }
        waiting.forEach { (eventId, isApproval) ->
            if (notified.add(eventId) && !UserActivity.isForeground && notificationsEnabled) {
                DshNotifications.notify(
                    context,
                    context.getString(if (isApproval) R.string.approval_request_title else R.string.question_request_title),
                    context.getString(R.string.notify_agent_waiting_body),
                )
            }
        }
    }
}
