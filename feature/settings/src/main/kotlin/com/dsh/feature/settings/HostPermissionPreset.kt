package com.dsh.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Вариант режима доступа (элемент `permissionPresets/catalog` Host). */
data class PermissionPresetOption(val value: String, val name: String)

/**
 * Режим доступа по умолчанию: варианты — из `permissionPresets/catalog` Host, текущее значение —
 * `permission.defaultPreset` из `settings/describe`, запись — через `settings/mutate`.
 *
 * Список не зашит в клиент: появится в Host новый режим (например, auto review) —
 * он сразу будет в настройках, без нового APK.
 */
object HostPermissionPreset {
    /** Все режимы Host; пусто — ещё не прочитаны. */
    var options by mutableStateOf<List<PermissionPresetOption>>(emptyList())
        private set

    /** Текущий режим по умолчанию; null — ещё не прочитан. */
    var current by mutableStateOf<String?>(null)
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

    fun set(value: String) = writer?.invoke(value)

    private suspend fun load(repository: DshRepository, hostId: String) {
        val settings = repository.hostSettings
        val parsedOptions = runCatching { settings.permissionPresets(hostId) }.getOrDefault(emptyList())
            .map { (value, name) -> PermissionPresetOption(value, name) }
        val doc = runCatching { settings.namespace(hostId, NS) }.getOrNull()
        doc?.revision?.let { revision = it }
        if (parsedOptions.isNotEmpty()) options = parsedOptions
        current = (doc?.value?.get("defaultPreset") as? JsonPrimitive)?.contentOrNull
    }

    private suspend fun mutate(repository: DshRepository, hostId: String, value: String) {
        repository.hostSettings.setSettings(hostId, NS, listOf(listOf("defaultPreset") to JsonPrimitive(value)), revision)
    }

    private const val NS = "permission"
}
