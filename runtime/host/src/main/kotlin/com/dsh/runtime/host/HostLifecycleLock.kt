package com.dsh.runtime.host

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запуск и остановка Host по очереди (G-08). Раньше `stop()` не ждал идущего запуска: остановка во время прогрева
 * гасила мост и «завершала» ещё не запущенный процесс, после чего запуск всё равно поднимал Host — восстановление
 * копии и авто-сон писали в файлы живого Host.
 *
 * Остановка сразу помечается ([stopRequested]): идущий запуск видит пометку на своих шагах и сворачивается,
 * а сама остановка выполняется после него, под тем же замком.
 */
internal class HostLifecycleLock {
    private val mutex = Mutex()
    private val pendingStops = AtomicInteger(0)

    /** Ждёт ли остановка: запуску пора прекратить и ничего не поднимать. */
    val stopRequested: Boolean get() = pendingStops.get() > 0

    suspend fun <T> starting(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> stopping(block: suspend () -> T): T {
        pendingStops.incrementAndGet()
        try {
            return mutex.withLock { block() }
        } finally {
            pendingStops.decrementAndGet()
        }
    }
}
