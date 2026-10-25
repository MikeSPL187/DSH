package com.deepseek.dsh.feature.settings

import com.deepseek.dsh.core.designsystem.kit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

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
        val catalog = runCatching {
            repository.rpcRaw(hostId, "permissionPresets/catalog", "{}").getOrNull()
        }.getOrNull()
        val settings = runCatching {
            repository.rpcRaw(hostId, "settings/describe", "{}").getOrNull()
        }.getOrNull()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val parsedOptions = runCatching {
            val root = json.parseToJsonElement(catalog.orEmpty())
            val value = ((root as? JsonObject)?.get("result") as? JsonObject)?.get("value") as? JsonObject
            ((value?.get("options") as? JsonArray).orEmpty()).mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val optionValue = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: optionValue
                PermissionPresetOption(optionValue, name)
            }
        }.getOrDefault(emptyList())
        val parsedCurrent = runCatching {
            val root = json.parseToJsonElement(settings.orEmpty())
            val value = ((root as? JsonObject)?.get("result") as? JsonObject)?.get("value") as? JsonObject
            val namespaces = value?.get("namespaces") as? JsonArray ?: return@runCatching null
            namespaces.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                if ((obj["ns"] as? JsonPrimitive)?.contentOrNull != "permission") return@mapNotNull null
                val docValue = obj["value"] as? JsonObject ?: return@mapNotNull null
                revision = (obj["revision"] as? JsonPrimitive)?.intOrNull ?: revision
                (docValue["defaultPreset"] as? JsonPrimitive)?.contentOrNull
            }.firstOrNull()
        }.getOrNull()
        if (parsedOptions.isNotEmpty()) options = parsedOptions
        current = parsedCurrent
    }

    private suspend fun mutate(repository: DshRepository, hostId: String, value: String) {
        val args = buildJsonObject {
            put("ns", "permission")
            put("ops", buildJsonArray {
                add(buildJsonObject {
                    put("op", "set")
                    put("path", buildJsonArray { add(JsonPrimitive("defaultPreset")) })
                    put("value", value)
                })
            })
            put("expectedRevision", revision)
        }
        repository.rpcRaw(hostId, "settings/mutate", args.toString())
    }
}
