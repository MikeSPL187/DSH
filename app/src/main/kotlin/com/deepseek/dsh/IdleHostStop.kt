package com.deepseek.dsh

import com.deepseek.dsh.runtime.host.RuntimePhase
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
}

/**
 * Настройка «Авто-сон при простое»: раньше она только сохранялась, а Host с proot и
 * Node работал бесконечно и разряжал батарею. Останавливаем контейнер, только когда он
 * запущен, ни одна задача агента не выполняется и пользователь не трогал приложение
 * [idleMinutes] минут.
 */
internal fun shouldIdleStopHost(
    idleMinutes: Int,
    phase: RuntimePhase,
    anySessionRunning: Boolean,
    lastInteractionAt: Long,
    now: Long,
): Boolean =
    idleMinutes > 0 &&
        phase == RuntimePhase.RUNNING &&
        !anySessionRunning &&
        now - lastInteractionAt >= idleMinutes * 60_000L
