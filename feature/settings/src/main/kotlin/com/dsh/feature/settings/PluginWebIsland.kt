package com.dsh.feature.settings

import com.dsh.core.designsystem.kit.*
import androidx.compose.ui.unit.sp
import android.annotation.SuppressLint
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.plugin.runtimeweb.WebIslandContainer

/**
 * «Полный интерфейс» плагина: веб-клиент Host в островке WebView.
 *
 * Статическая копия даёт лишь **объявленную структуру**; список сервисов, вход, живое потребление,
 * вход, выход и обновление токенов требуют JS плагина и его RPC к Host.
 * Вместо имитации здесь работает настоящий интерфейс: островок загружает тот же Host
 * с тем же cookie сессии, поэтому действия в нём настоящие, как в браузере.
 *
 * Компонент отвечает только за **один WebView**: размер даёт вызывающий (высота должна быть ограничена,
 * не внутри `verticalScroll`), загрузка и ошибка уходят через [onLoadingChanged] / [onFailed].
 *
 * WebView обёрнут в `FrameLayout`: исправлению вьюпорта WebView нужен настоящий
 * перерасчёт раскладки родителя (`nudgeViewport` в [WebIslandContainer]), а Compose
 * `AndroidView` гарантирует размер только своего прямого View; LayoutParams самого
 * WebView измерение Compose не учитывает.
 *
 * @param port порт loopback локального Host; 0 — Host не запущен.
 * @param token токен запуска: в WebView он меняется на cookie сессии.
 * @param reloadKey изменился — перезагрузить (кнопка обновления в шапке).
 * @param focusSettingsLabels подписи раздела настроек, который открыть сразу.
 * @param onLoadingChanged начало и конец загрузки основного фрейма.
 * @param onFailed основной фрейм не загрузился или веб-клиент не запустился.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebIsland(
    port: Int,
    token: String?,
    modifier: Modifier = Modifier,
    reloadKey: Int = 0,
    focusSettingsLabels: List<String> = emptyList(),
    focusSectionId: String? = null,
    onLoadingChanged: (Boolean) -> Unit = {},
    onFailed: (String) -> Unit = {},
) {
    if (port <= 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = androidx.compose.ui.res.stringResource(com.dsh.feature.settings.R.string.pwi_host_not_running),
                style = com.dsh.core.designsystem.kit.body(14.5.sp, 21.sp),
                color = com.dsh.core.designsystem.Dsh.colors.t3,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    // factory у AndroidView выполняется один раз, дальше состояние меняет только update. Запоминаем
    // применённые reloadKey и подписи и перезагружаем, только если они действительно изменились.
    val currentReloadKey = rememberUpdatedState(reloadKey)
    val currentFocusLabels = rememberUpdatedState(focusSettingsLabels)
    val currentFocusSectionId = rememberUpdatedState(focusSectionId)
    val appliedReloadKey = remember { intArrayOf(reloadKey) }
    val appliedFocusLabels = remember { androidx.compose.runtime.mutableStateOf(focusSettingsLabels) }
    val appliedFocusSectionId = remember { androidx.compose.runtime.mutableStateOf(focusSectionId) }
    val loadingSink = rememberUpdatedState(onLoadingChanged)
    val failedSink = rememberUpdatedState(onFailed)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val frame = FrameLayout(context)
            val web = WebIslandContainer(
                context = context,
                allowedPort = port,
                onLoadingChanged = { loading -> loadingSink.value(loading) },
                onFailed = { reason -> failedSink.value(reason) },
            )
            frame.addView(
                web,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            web.apply {
                // Сразу к разделу настроек этого плагина в веб-клиенте; не вышло — остаёмся на клиенте.
                focusSettingsSection(focusSettingsLabels, focusSectionId)
                // Загрузка без изоляции: страница плагина — слой веб-клиента без своего маршрута;
                // островок даёт весь интерфейс, пользователь идёт в Настройки → раздел плагина.
                loadSurface(
                    surfaceSlot = "settings.section",
                    pluginId = "",
                    isolated = false,
                    token = token,
                )
            }
            frame
        },
        update = { frame ->
            val view = frame.getChildAt(0) as? WebIslandContainer ?: return@AndroidView
            val target = currentReloadKey.value
            if (appliedReloadKey[0] != target) {
                appliedReloadKey[0] = target
                view.reloadLast()
            }
            val labels = currentFocusLabels.value
            val focusId = currentFocusSectionId.value
            if (labels != appliedFocusLabels.value || focusId != appliedFocusSectionId.value) {
                appliedFocusLabels.value = labels
                appliedFocusSectionId.value = focusId
                view.focusSettingsSection(labels, focusId)
            }
        },
        onRelease = { frame ->
            (frame.getChildAt(0) as? WebIslandContainer)?.let { web ->
                runCatching { web.destroy() }
            }
        },
    )
}
