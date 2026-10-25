package com.deepseek.dsh.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Тема и размер текста: настройка `ui-theme` в Host.
 *
 * Тема и размер шрифта хранятся в Host (`ui-theme.preference` / `fontSize`),
 * поэтому телефон и браузер одного аккаунта выглядят одинаково. Приложение рисует тему по этому состоянию,
 * выбор пишет в Host; без моста — как в системе.
 */
object HostTheme {
    /** `light` / `dark` / `system`; по умолчанию как в системе. */
    var preference by mutableStateOf("system")
        private set

    /** Размер основного текста (`ui-theme.fontSize` Host); null — ещё не прочитан. */
    var fontSize by mutableStateOf<Int?>(null)
        private set

    /** Последняя прочитанная revision настроек — expectedRevision при записи. */
    private var revision: Int = 0

    private var reader: (() -> Unit)? = null
    private var writer: ((String) -> Unit)? = null

    fun wire(repository: DshRepository, hostId: String, scope: CoroutineScope) {
        reader = { scope.launch { load(repository, hostId) } }
        writer = { value ->
            scope.launch {
                runCatching { mutate(repository, hostId, value) }
                load(repository, hostId)
            }
        }
        reader?.invoke()
    }

    fun refresh() = reader?.invoke()

    fun setPreference(value: String) = writer?.invoke(value)

    private suspend fun load(repository: DshRepository, hostId: String) {
        val doc = runCatching { repository.hostSettings.namespace(hostId, NS) }.getOrNull() ?: return
        revision = doc.revision ?: revision
        (doc.value["preference"] as? JsonPrimitive)?.contentOrNull?.let { preference = it }
        fontSize = (doc.value["fontSize"] as? JsonPrimitive)?.intOrNull
    }

    private suspend fun mutate(repository: DshRepository, hostId: String, value: String) {
        repository.hostSettings.setSettings(hostId, NS, listOf(listOf("preference") to JsonPrimitive(value)), revision)
    }

    private const val NS = "ui-theme"
}
