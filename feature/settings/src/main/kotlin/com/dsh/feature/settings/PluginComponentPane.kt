package com.dsh.feature.settings

import com.dsh.core.designsystem.kit.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.core.bridge.BridgeRegistry
import kotlinx.coroutines.flow.SharedFlow

/**
 * Страница интерфейса плагина: запускает **полный веб-интерфейс** самого плагина.
 *
 * Нативная статическая копия удалена вместе с `plugin/mobile-ui`: с тем же Host и тем же cookie
 * сессии список сервисов плагина, вход, живое потребление, вход и выход —
 * всё происходит в настоящем веб-клиенте; нативной копии «объявленной структуры» больше нет.
 *
 * Это единственная шапка «Назад» интерфейса плагина: корень настроек свою шапку при этом не рисует.
 *
 * @param componentId компонент вида `<package>#<id>` (по нему веб-клиент находит settings.section).
 * @param title заголовок страницы (и кандидат подписи раздела веб-клиента).
 * @param registry мост; null — «Мост не подключён».
 * @param onBack назад.
 * @param refreshEvents id компонентов, которые плагин просит обновить (`client/refresh`); совпадение — перезагрузка.
 * @param hostPort порт loopback локального Host.
 * @param hostToken токен запуска Host.
 * @param hostPortProvider взять порт Host заново: Host готовится асинхронно, при композиции может быть 0.
 */
@Composable
fun PluginComponentPane(
    componentId: String,
    title: String,
    registry: BridgeRegistry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    refreshEvents: SharedFlow<String>? = null,
    hostPort: Int = 0,
    hostToken: String? = null,
    hostPortProvider: (() -> Int)? = null,
    /** Почему нативная страница не подхватила (компаньон не загружен / нет страницы настроек / id не совпал); null — нативной нет. */
    nativeFallbackReason: String? = null,
    /** false — встроено в настройки: шапку «Назад» даёт корень настроек. */
    showHeader: Boolean = true,
) {
    var webReloadKey by remember { mutableIntStateOf(0) }
    var webLoading by remember { mutableStateOf(true) }
    var webFailed by remember { mutableStateOf<String?>(null) }

    // Host готовится асинхронно: при композиции порт может быть 0. Веб-интерфейс загрузится, когда он появится.
    val availablePort: Int = hostPort.takeIf { it > 0 }
        ?: hostPortProvider?.invoke()?.takeIf { it > 0 }
        ?: 0
    val webAvailable = availablePort > 0
    var livePortValue by remember { mutableIntStateOf(0) }

    LaunchedEffect(webAvailable) {
        if (webAvailable && livePortValue == 0) {
            livePortValue = availablePort
            webFailed = null
            webLoading = true
            webReloadKey += 1
        }
    }

    // Обновление по событию: плагин ответил в Host, мост ставит client/refresh —
    // если это наш компонент, страница перезагружается и получает свежее состояние.
    LaunchedEffect(refreshEvents, componentId) {
        refreshEvents?.collect { refreshed ->
            if (refreshed == componentId) {
                webFailed = null
                webLoading = true
                webReloadKey += 1
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val c = com.dsh.core.designsystem.Dsh.colors
        val reload = {
            webFailed = null
            webLoading = true
            webReloadKey += 1
        }
        if (showHeader) {
            com.dsh.core.designsystem.kit.SBar(
                title = title,
                subtitle = androidx.compose.ui.res.stringResource(
                    when {
                        registry == null -> com.dsh.feature.settings.R.string.pcp_bridge_disconnected
                        webFailed != null -> com.dsh.feature.settings.R.string.pcp_full_ui_failed
                        else -> com.dsh.feature.settings.R.string.pcp_full_ui_web
                    },
                ),
                onBack = onBack,
                backDescription = androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_back),
            ) {
                com.dsh.core.designsystem.kit.IconBtn(
                    com.dsh.core.designsystem.DshGlyphs.Redo,
                    androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_refresh),
                    onClick = reload,
                )
            }
        }
        if (nativeFallbackReason != null) {
            // Компаньон установлен, но открылась веб-страница: показываем, на каком шаге не сошлось.
            Text(
                text = nativeFallbackReason,
                style = com.dsh.core.designsystem.kit.small(12.5.sp, 17.sp),
                color = c.danger,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (registry == null) {
                com.dsh.core.designsystem.kit.FsEmpty(
                    androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_ui_unavailable),
                    androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_bridge_retry),
                )
            } else {
                PluginWebIsland(
                    port = livePortValue.takeIf { it > 0 } ?: availablePort,
                    token = hostToken,
                    reloadKey = webReloadKey,
                    // Внутри той же оболочки веб-клиента: по подписи на языке интерфейса и id регистрации
                    // он сам переходит к settings.section этого плагина.
                    focusSettingsLabels = remember(componentId, title) {
                        listOf(title, componentId.substringAfter('#'))
                            .filter { it.isNotBlank() }
                            .distinct()
                    },
                    focusSectionId = componentId.substringAfter('#').takeIf { it.isNotBlank() },
                    modifier = Modifier.fillMaxSize(),
                    onLoadingChanged = { webLoading = it },
                    onFailed = { message -> webFailed = message },
                )
                if (webLoading && webFailed == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.dsh.core.designsystem.DshSpinner(c.accent, 28.dp)
                    }
                }
                // Веб-клиент не поднялся: понятная причина и «Повторить», чтобы сбой Host не выглядел как «так и задумано».
                webFailed?.let { reason ->
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(c.dangerSoft).padding(16.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_full_ui_start_failed),
                            style = com.dsh.core.designsystem.kit.body(15.sp, 20.sp, FontWeight.SemiBold), color = c.danger,
                        )
                        Text(reason.take(320), style = com.dsh.core.designsystem.kit.small(13.sp, 18.sp), color = c.t2, modifier = Modifier.padding(top = 4.dp))
                        com.dsh.core.designsystem.kit.KBtn(
                            androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pcp_retry), reload, Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
