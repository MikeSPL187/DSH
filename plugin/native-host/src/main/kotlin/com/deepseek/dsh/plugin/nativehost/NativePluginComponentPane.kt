package com.deepseek.dsh.plugin.nativehost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import com.deepseek.dsh.plugin.nativesdk.pages.PageContent
import com.deepseek.dsh.plugin.nativesdk.pages.PageLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * Full-page host for a native settings section. Unlike the Web fallback, this composable
 * never creates a WebView. Events and refresh handling stay with the caller, which owns
 * the Host connection and can supply the latest JSON-only page spec.
 */
@Composable
fun NativePluginComponentPane(
    page: PageContent,
    componentId: String,
    spec: JsonObject,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pluginRpc: (suspend (method: String, payload: JsonObject) -> Result<String>)? = null,
    dispatchEvent: (suspend (componentId: String, event: String, payload: JsonObject) -> Unit)? = null,
    refreshEvents: SharedFlow<String>? = null,
    /** false — встроено в настройки: шапку «Назад» даёт корень настроек. */
    showHeader: Boolean = true,
) {
    val host = NativeHost.LocalHost.current
    val theme = NativeHost.LocalTheme.current
    val context = LocalContext.current
    val pageHost = remember(host, componentId, pluginRpc, dispatchEvent, context) {
        if (host == null) null else NativePageHost(
            componentId = componentId,
            delegate = host,
            pluginRpc = pluginRpc ?: { _, _ -> Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Host RPC is not connected", "Host RPC не подключён"))) },
            dispatch = dispatchEvent ?: { _, _, _ -> },
        )
    }
    val mounted = remember { mutableStateOf(false) }
    DisposableEffect(componentId) {
        mounted.value = true
        onDispose { mounted.value = false }
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader) {
            com.deepseek.dsh.core.designsystem.DshTopBar(
                title = page.entry.title,
                onBack = onBack,
                backDescription = androidx.compose.ui.res.stringResource(com.deepseek.dsh.plugin.nativehost.R.string.native_back),
            )
        }
        if (pageHost != null && theme != null) {
            CompositionLocalProvider(PageLifecycle.LocalIsActive provides { mounted.value }) {
                androidx.compose.runtime.key(componentId) {
                    page.Content(spec = spec, theme = theme, host = pageHost, refreshEvents = refreshEvents)
                }
            }
        }
    }
}
