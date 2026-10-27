package com.dsh.plugin.nativehost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme

/**
 * Фасад хоста нативных плагинов: всё, что приложение даёт плагинам.
 *
 * Доступен местам вставки через CompositionLocal [LocalNativeHost], чтобы host и theme не
 * передавать параметрами по всей цепочке. Приложение задаёт его один раз в корне (или на странице),
 * а [NativeSlotHost] любого места читает напрямую.
 */
object NativeHost {
    /** Действующий мост возможностей; не задан — null (места вставки не рисуются). */
    val LocalHost = staticCompositionLocalOf<PluginHostApi?> { null }

    /** Снимок текущей темы; не задан — null. */
    val LocalTheme = staticCompositionLocalOf<PluginTheme?> { null }

    /**
     * Вход в собственные RPC-маршруты плагинов на уровне процесса (задаётся при запуске приложения и смене Host).
     *
     * Нативные страницы идут через NativePageHost, но вкладам в слоты (док поля ввода и т. п.) тоже нужно
     * читать состояние плагина (например, лимиты). Хост слота оборачивает обычный PluginHostApi
     * в PluginRpcHost с той же реализацией pluginRpc.
     */
    @Volatile
    var rpcHandler: (suspend (method: String, payload: kotlinx.serialization.json.JsonObject) -> Result<String>)? = null

    /** Выбор модели и уровня рассуждений текущей сессии; задаёт страница чата. */
    @Volatile
    var modelSelectHandler:
        (suspend (provider: String, model: String, reasoningEffort: String?) -> Result<Unit>)? = null
}

/**
 * Короткий вход для места вставки: host и theme из CompositionLocal; нет любого — ничего не рисуется.
 *
 * Месту вставки достаточно одной строки:
 *
 * ```kotlin
 * NativeSlot(slot = NativeSlot.COMPOSER_DOCK, spec = currentSpec)
 * ```
 */
@Composable
fun NativeSlot(
    slot: com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot,
    spec: kotlinx.serialization.json.JsonObject,
    modifier: Modifier = Modifier,
) {
    val host = NativeHost.LocalHost.current ?: return
    val theme = NativeHost.LocalTheme.current ?: return
    NativeSlotHost(slot = slot, spec = spec, theme = theme, host = host, modifier = modifier)
}

/**
 * Компактный вариант того же места в одну строку — для строки состояния под полем ввода и т. п.
 *
 * Вклады идут элементами Row и не занимают всю строку; без регистраций места не занимают.
 */
@Composable
fun NativeSlotInline(
    slot: com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot,
    spec: kotlinx.serialization.json.JsonObject,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    val host = NativeHost.LocalHost.current ?: return
    val theme = NativeHost.LocalTheme.current ?: return
    NativeSlotHost(
        slot = slot,
        spec = spec,
        theme = theme,
        host = host,
        modifier = modifier,
        inline = true,
        scrollable = scrollable,
    )
}
