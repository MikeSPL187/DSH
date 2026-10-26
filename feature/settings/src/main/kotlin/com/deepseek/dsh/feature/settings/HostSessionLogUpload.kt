package com.deepseek.dsh.feature.settings

import com.deepseek.dsh.core.designsystem.kit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Переключатель выгрузки журналов сессий (общая настройка `ui-settings-session-log`).
 *
 * Пространство Host `session-log-deepseek`, поле `enabled`. Значение читается из
 * `settings/describe`, запись — `settings/mutate` с revision. Приложение один раз вызывает
 * [wire], как только Host доступен; настройки читают и пишут состояние здесь.
 */
object HostSessionLogUpload {
    /** null — ещё не прочитано (переключатель не рисуется, чтобы не мигнуть значением по умолчанию). */
    var enabled by mutableStateOf<Boolean?>(null)
        private set

    private var writer: ((Boolean) -> Unit)? = null
    private var reader: (() -> Unit)? = null

    /** Чтение и запись даёт приложение. */
    fun wire(repository: DshRepository, hostId: String, scope: CoroutineScope) {
        reader = {
            scope.launch {
                enabled = runCatching { repository.hostSettings.fetchSessionLogUploadEnabled(hostId) }.getOrNull()
            }
        }
        writer = { value ->
            scope.launch {
                runCatching { repository.hostSettings.setSessionLogUploadEnabled(hostId, value) }
                enabled = runCatching { repository.hostSettings.fetchSessionLogUploadEnabled(hostId) }.getOrNull()
            }
        }
        reader?.invoke()
    }

    /** Прочитать, когда настройки открылись впервые. */
    fun refresh() {
        reader?.invoke()
    }

    /** Записать значение (затем перечитать фактическое значение Host). */
    fun set(value: Boolean) {
        writer?.invoke(value)
    }
}
