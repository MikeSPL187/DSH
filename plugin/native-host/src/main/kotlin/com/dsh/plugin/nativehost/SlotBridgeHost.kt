package com.dsh.plugin.nativehost

import com.dsh.core.protocol.parseHostJson
import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.host.PluginModelHost
import com.deepseek.dsh.plugin.nativesdk.host.PluginRpcHost
import com.deepseek.dsh.plugin.nativesdk.host.PluginStorage
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Обёртка моста возможностей для вкладов в места: к обычному [PluginHostApi] добавляется [PluginRpcHost].
 *
 * Вкладам в слоты (док поля ввода, места в настройках и т. п.), как и нативным страницам, нужно читать
 * существующее состояние Host. Реализация RPC одна — [NativeHost.rpcHandler] (pluginRpc от приложения),
 * нового протокола нет; без связи с Host — понятная ошибка.
 */
internal class SlotBridgeHost(
    private val delegate: PluginHostApi,
) : PluginHostApi, PluginRpcHost, PluginModelHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun selectModel(
        provider: String,
        model: String,
        reasoningEffort: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        scope.launch {
            val handler = NativeHost.modelSelectHandler
            if (handler == null) {
                onResult(Result.failure(IllegalStateException(com.dsh.core.model.Tr.t("This page does not support switching models", "На этой странице нельзя сменить модель"))))
                return@launch
            }
            onResult(handler(provider, model, reasoningEffort))
        }
    }

    override fun callRpc(method: String, payload: JsonObject, onResult: (Result<JsonElement>) -> Unit) {
        scope.launch {
            val handler = NativeHost.rpcHandler
            if (handler == null) {
                onResult(Result.failure(IllegalStateException(com.dsh.core.model.Tr.t("Host is not connected", "Host не подключён"))))
                return@launch
            }
            onResult(handler(method, payload).map { Json.parseHostJson(it) })
        }
    }

    override fun dispatchEvent(componentId: String, event: String, payload: JsonObject) =
        delegate.dispatchEvent(componentId, event, payload)

    override fun openUrl(url: String) = delegate.openUrl(url)
    override fun currentTheme(): PluginTheme = delegate.currentTheme()
    override fun requestCapability(capability: String): Boolean = delegate.requestCapability(capability)
    override fun storage(): PluginStorage = delegate.storage()
}
