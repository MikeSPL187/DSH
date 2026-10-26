package com.deepseek.dsh.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Держать подписку на поток Host (WebSocket `remote.mux`) живой.
 *
 * Поток кончается исключением при любом обрыве (перезапуск Host, авто-сон, новый порт):
 * клиент делает `close(t)`, а подписки запущены в области приложения без обработчика.
 * Раньше «Connection reset» ронял процесс, а EOF глушился, но подписка умирала навсегда —
 * меню, «Агент работает» и баланс переставали обновляться до перезапуска приложения.
 * Теперь обрыв, ошибка обработчика кадра или конец потока — переподключение с растущей
 * паузой ([streamRetryDelayMs]); отмена области — выход.
 */
internal suspend fun keepSubscribed(
    pauseMs: (attempt: Int) -> Long = ::streamRetryDelayMs,
    collect: suspend () -> Unit,
) {
    var failures = 0
    while (true) {
        val startedAt = System.currentTimeMillis()
        try {
            collect()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Обрыв соединения или битый кадр: переподключимся после паузы.
        }
        // Соединение жило долго — значит, Host был доступен: снова короткая пауза.
        failures = if (System.currentTimeMillis() - startedAt > 10_000L) 1 else failures + 1
        delay(pauseMs(failures))
    }
}

/** Пауза перед попыткой [attempt] (с 1): 2, 4, 8, 16, 30, 30… секунд. */
internal fun streamRetryDelayMs(attempt: Int): Long =
    (2_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(30_000L)
