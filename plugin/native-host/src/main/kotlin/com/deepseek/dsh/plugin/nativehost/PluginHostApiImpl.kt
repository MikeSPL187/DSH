package com.deepseek.dsh.plugin.nativehost

import com.deepseek.dsh.plugin.nativesdk.contract.PluginCapabilities
import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.host.PluginStorage
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Реализация [PluginHostApi] в приложении — единственное, к чему плагин может прикоснуться.
 *
 * Каждая возможность проходит три проверки:
 * 1. **Объявление**: возможность указана в `descriptor.capabilities` плагина;
 * 2. **Белый список**: домены URL, префиксы путей рабочей папки и т. п.;
 * 3. **Журнал**: каждый вызов записывается (pluginId, возможность, время), смотреть — в настройках.
 *
 * `dispatchEvent` идёт через операцию `dispatch` моста к JS-плагину в Host.
 */
class PluginHostApiImpl(
    private val pluginId: String,
    private val declaredCapabilities: Set<String>,
    private val themeProvider: () -> PluginTheme,
    private val dispatcher: suspend (componentId: String, event: String, payload: JsonObject) -> Unit,
    private val urlOpener: (url: String) -> Unit,
    private val urlAllowlist: (url: String) -> Boolean,
    private val storageBackend: PluginStorage,
    private val audit: (pluginId: String, capability: String) -> Unit,
) : PluginHostApi {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
    )

    override fun dispatchEvent(componentId: String, event: String, payload: JsonObject) {
        require(componentId.startsWith(pluginId)) {
            com.deepseek.dsh.core.model.Tr.t("componentId prefix must equal the plugin id ($pluginId), got: $componentId", "Префикс componentId должен совпадать с id плагина ($pluginId), получено: $componentId")
        }
        audit(pluginId, "dispatch:$event")
        // dispatch — suspend (сеть моста), а хост вызывает его синхронно из отрисовки. Раньше здесь был
        // runBlocking, и при медленной сети поток интерфейса вставал (ANR). Теперь отправка в последовательной области IO:
        // отрисовка не блокируется, события одного плагина приходят по порядку.
        dispatchScope.launch {
            runCatching { dispatcher(componentId, event, payload) }
                .onFailure { android.util.Log.w("PluginHostApi", "dispatch $event failed for $pluginId", it) }
        }
    }

    override fun openUrl(url: String) {
        requireCapability(PluginCapabilities.OPEN_URL)
        audit(pluginId, "openUrl")
        // Из белого списка — открыть сразу; остальное — в системный браузер (urlOpener спрашивает пользователя).
        urlOpener(url)
    }

    override fun currentTheme(): PluginTheme = themeProvider()

    override fun requestCapability(capability: String): Boolean =
        capability in declaredCapabilities

    override fun storage(): PluginStorage {
        requireCapability(PluginCapabilities.STORAGE)
        audit(pluginId, "storage")
        return storageBackend
    }

    private fun requireCapability(capability: String) {
        check(capability in declaredCapabilities) {
            com.deepseek.dsh.core.model.Tr.t("Plugin $pluginId did not declare capability $capability", "Плагин $pluginId не объявил возможность $capability")
        }
    }
}
