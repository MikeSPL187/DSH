package com.dsh.plugin.nativehost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.host.PluginStorage

/**
 * Собрать экземпляр [PluginHostApi].
 *
 * **Минимальная рабочая** сборка общего моста хоста: dispatch — через мост, openUrl — в системный
 * браузер, storage — пространства в памяти (постоянное хранение можно заменить на DataStore). id плагина и
 * объявленные возможности подставляются из descriptor при загрузке компаньона; здесь — экземпляр «уровня шины»,
 * с которым места вставки безопасны и без конкретного плагина (dispatch уходит прямо в Host).
 *
 * @param dispatcher передача dispatchEvent в операцию `dispatch` моста.
 */
@Composable
fun rememberNativeHostApi(
    dispatcher: suspend (componentId: String, event: String, payload: kotlinx.serialization.json.JsonObject) -> Unit,
): PluginHostApi {
    val theme = rememberPluginTheme()
    return remember(dispatcher) {
        PluginHostApiImpl(
            pluginId = "",
            declaredCapabilities = setOf(
                com.deepseek.dsh.plugin.nativesdk.contract.PluginCapabilities.OPEN_URL,
                com.deepseek.dsh.plugin.nativesdk.contract.PluginCapabilities.STORAGE,
            ),
            themeProvider = { theme },
            dispatcher = dispatcher,
            urlOpener = { /* системный браузер подставляет приложение */ },
            urlAllowlist = { true },
            storageBackend = InMemoryPluginStorage,
            audit = { _, _ -> },
        )
    }
}

/** Хранилище ключ-значение в памяти процесса; постоянное — позже через DataStore. */
private object InMemoryPluginStorage : PluginStorage {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
