package com.dsh

import com.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Последнее взаимодействие пользователя с приложением (касание, ввод).
 * Обновляется из [MainActivity.onUserInteraction].
 */
internal object UserActivity {
    private val _lastInteraction = MutableStateFlow(System.currentTimeMillis())
    val lastInteraction: StateFlow<Long> = _lastInteraction

    fun touch(now: Long = System.currentTimeMillis()) {
        _lastInteraction.value = now
    }

    /** Видна ли сейчас Activity (между onResume и onPause). */
    @Volatile
    var isForeground: Boolean = false
}

/**
 * Настройка «Авто-сон при простое»: раньше она только сохранялась, а Host с proot и
 * Node работал бесконечно и разряжал батарею. Останавливаем контейнер, только когда он
 * запущен, ничего не занято ([busy]: ход агента, команда терминала, фоновый процесс, ожидающее
 * одобрение или вопрос — G-13) и пользователь не трогал приложение [idleMinutes] минут.
 */
internal fun shouldIdleStopHost(
    idleMinutes: Int,
    phase: RuntimePhase,
    busy: Boolean,
    lastInteractionAt: Long,
    now: Long,
): Boolean =
    idleMinutes > 0 &&
        phase == RuntimePhase.RUNNING &&
        !busy &&
        now - lastInteractionAt >= idleMinutes * 60_000L

/** Пора предупредить «уснёт через минуту»: до сна осталось не больше минуты (и сон вообще будет). */
internal fun idleWarningDue(
    idleMinutes: Int,
    phase: RuntimePhase,
    busy: Boolean,
    lastInteractionAt: Long,
    now: Long,
): Boolean =
    idleMinutes > 0 && phase == RuntimePhase.RUNNING && !busy &&
        now - lastInteractionAt >= idleMinutes * 60_000L - IDLE_WARNING_MS &&
        now - lastInteractionAt < idleMinutes * 60_000L

internal const val IDLE_WARNING_MS = 60_000L

/** «Не усыплять» из уведомления: считается действием пользователя — отсчёт простоя начинается заново. */
class KeepAwakeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        UserActivity.touch()
        DshNotifications.cancelIdleWarning(context)
    }
}
