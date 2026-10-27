package com.dsh

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.data.DshRepository
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshDialog
import com.dsh.core.designsystem.DshDialogButton
import com.dsh.core.model.HostDescriptor
import com.dsh.feature.chat.ckBody
import com.dsh.runtime.host.HostProcessRunner
import com.dsh.runtime.host.HostRuntimeHolder
import com.dsh.runtime.host.HostRuntimeManager
import com.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Экспериментальные bundle, включённые пользователем через приложение.
 *
 * Профиль на устройстве пересоздаётся из шаблона при каждом запуске Host (проверено:
 * записанный `dsh.profile.bundles` пропадает), поэтому выбор хранится и локально
 * и возвращается в профиль после запуска Host ([PluginPollingEffect]).
 */
internal class BundleSelection(context: Context) {
    private val prefs = context.getSharedPreferences(PLUGIN_SELECTION_PREFS, Context.MODE_PRIVATE)

    fun set(name: String, enabled: Boolean) {
        val current = enabled().toMutableSet()
        if (enabled) current.add(name) else current.remove(name)
        prefs.edit().putStringSet(PLUGIN_SELECTION_KEY, current).apply()
    }

    fun enabled(): Set<String> = prefs.getStringSet(PLUGIN_SELECTION_KEY, emptySet())?.toSet().orEmpty()
}

/**
 * Включить или выключить bundle Host и дождаться ответа. Если Host просит перезапуск — перезапустить
 * и дождаться RUNNING: тогда новая функция (команда агентов, голос, терминал, диагностика, перевод,
 * магазин) появляется сразу; не вышло — окно «Нужен перезапуск» с причиной.
 * @return принял ли Host изменение; переключатель потом берёт состояние из обновлённого каталога.
 */
internal class BundleToggle(
    private val context: Context,
    private val repository: DshRepository,
    private val runner: HostProcessRunner,
    private val ui: AppUiState,
    private val selection: BundleSelection,
) {
    suspend fun toggle(packageName: String, enabled: Boolean): Boolean {
        val result = repository.plugins.setBundleEnabled(ui.activeHostId, packageName, enabled)
        result.fold(
            onSuccess = { outcome ->
                selection.set(packageName, enabled)
                if (outcome.restartRequired) {
                    toast(context.getString(R.string.plugin_toggle_restarting))
                    ui.pluginChangeNotice = if (restartHost()) null
                    else listOf(context.getString(R.string.restart_dsh_hint), restartReasonText(outcome.restartReason))
                } else if (outcome.warnings.isNotEmpty()) {
                    toast(outcome.warnings.first().take(120), long = true)
                }
            },
            onFailure = {
                val action = context.getString(if (enabled) R.string.plugin_enable else R.string.plugin_disable)
                toast(context.getString(R.string.plugin_toggle_failed, action))
            },
        )
        repository.plugins.refreshAll(ui.activeHostId)
        return result.isSuccess
    }

    private suspend fun restartHost(): Boolean {
        val restart = HostRuntimeHolder.restartHost?.invoke() ?: return false
        if (restart.isFailure) return false
        val finished = withTimeoutOrNull(90_000) {
            runner.statusFlow.first { it.phase != RuntimePhase.RUNNING }
            runner.statusFlow.first { it.phase == RuntimePhase.RUNNING }
            true
        } ?: false
        repository.plugins.refreshAll(ui.activeHostId)
        return finished
    }

    /** `restartReason` Host → пояснение к «Перезапустите DSH, чтобы применить изменения». */
    private fun restartReasonText(reason: String?): String = context.getString(
        when (reason) {
            "package-moved" -> R.string.restart_reason_package_moved
            "no-hmr" -> R.string.restart_reason_no_hmr
            "host-module" -> R.string.restart_reason_host_module
            "unmanaged-plugin" -> R.string.restart_reason_unmanaged_plugin
            "module-comparison" -> R.string.restart_reason_module_comparison
            else -> R.string.restart_reason_generic
        },
    )

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Окно «Нужен перезапуск» после переключения плагина: причины от Host и «Перезапустить сейчас». */
@Composable
internal fun PluginChangeNoticeDialog(ui: AppUiState, scope: CoroutineScope) {
    val lines = ui.pluginChangeNotice ?: return
    val context = LocalContext.current
    val c = Dsh.colors
    val close = {
        if (!ui.pluginRestartRunning) {
            ui.pluginChangeNotice = null
            ui.pluginRestartError = null
        }
    }
    DshDialog(
        onDismiss = close,
        title = stringResource(R.string.restart_needed_title),
        closeLabel = stringResource(R.string.action_close),
        description = stringResource(R.string.restart_needed_desc),
        content = {
            lines.forEach { line ->
                Text(line, style = ckBody(14.sp, 20.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp))
            }
            ui.pluginRestartError?.let { message ->
                Text(message, style = ckBody(13.5.sp, 19.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            }
        },
        footer = {
            DshDialogButton(text = stringResource(R.string.restart_later), primary = false, enabled = !ui.pluginRestartRunning, onClick = close)
            DshDialogButton(
                text = stringResource(if (ui.pluginRestartRunning) R.string.restarting else R.string.restart_now),
                primary = true,
                enabled = !ui.pluginRestartRunning,
                onClick = {
                    scope.launch {
                        ui.pluginRestartRunning = true
                        ui.pluginRestartError = null
                        val restart = HostRuntimeHolder.restartHost?.invoke()
                            ?: Result.failure(IllegalStateException(context.getString(R.string.host_not_ready)))
                        ui.pluginRestartRunning = false
                        if (restart.isSuccess) ui.pluginChangeNotice = null
                        else ui.pluginRestartError = restart.exceptionOrNull()?.message ?: context.getString(R.string.restart_failed)
                    }
                },
            )
        },
    )
}

/**
 * Связка Host для runtime: работающая версия bridge-плагина (manifest моста) и перезапуск Host с
 * новым кодом. Это знает только приложение — модуль runtime не видит слоя данных и рабочей папки.
 */
@Composable
internal fun HostRestartBridgeEffect(hostId: String, repository: DshRepository, runtimeManager: HostRuntimeManager, workspace: File) {
    DisposableEffect(hostId, workspace.absolutePath) {
        HostRuntimeHolder.runningBridgeVersionProvider = {
            runCatching { repository.plugins.bridgePluginVersion(forceRefresh = true) }.getOrNull()
        }
        HostRuntimeHolder.restartHost = {
            val stopped = runtimeManager.stop()
            if (stopped.isFailure) stopped else runtimeManager.start(workspace.absolutePath).map { }
        }
        onDispose {
            HostRuntimeHolder.runningBridgeVersionProvider = null
            HostRuntimeHolder.restartHost = null
        }
    }
}

/**
 * Периодическая работа с плагинами, пока приложение на экране и Host запущен (раз в 4 с):
 * - каталог моста: мост сканирует клиентские bundle постепенно, обновление двигает сканирование;
 * - включённые пользователем bundle возвращаются в профиль (Host сбрасывает его при запуске),
 *   каждый — один раз за сеанс, чтобы Host не перезагружался снова и снова;
 * - точки входа плагинов: при холодном старте первый запрос моста может опередить cookie сессии,
 *   поэтому, пока их нет, — чаще;
 * - состояние команды агентов и короткие сообщения плагинов (`client/refresh`, тосты).
 */
@Composable
internal fun PluginPollingEffect(ui: AppUiState, repository: DshRepository, runner: HostProcessRunner, selection: BundleSelection) {
    val context = LocalContext.current
    val restored = remember { mutableSetOf<String>() }
    val sessionId by rememberUpdatedState(ui.selectedSessionId)
    val hostId by rememberUpdatedState(ui.activeHostId)
    val plugins = repository.plugins
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            delay(4_000)
            // Опрос нужен только видимому интерфейсу: в фоне он лишь будил бы радио и CPU.
            if (!UserActivity.isForeground) continue
            if (hostId == HostDescriptor.Local().id && runner.statusFlow.value.phase != RuntimePhase.RUNNING) continue
            ticks += 1
            // Одна ошибка (Host перезапускается, мост не ответил) не должна остановить цикл навсегда.
            runCatching {
                if (ticks % 4 == 0) {
                    plugins.refreshBridgePluginCatalog()
                    val catalog = plugins.bridgePluginCatalogFlow.value
                    val missing = selection.enabled().filter { name ->
                        name !in restored && catalog.bundles.none { it.name == name && it.enabled }
                    }
                    if (missing.isNotEmpty()) {
                        restored += missing
                        missing.forEach { plugins.setBundleEnabled(hostId, it, true) }
                        plugins.refreshBridgePluginCatalog()
                        plugins.refreshPluginInventory(hostId)
                    }
                    if (catalog.bundles.any { it.name == AgentTeamPluginUi.BUNDLE && it.enabled }) {
                        repository.tasks.refreshAgentTeam(hostId, sessionId)
                    }
                }
                if (ticks % (if (plugins.pluginSlotEntriesFlow.value.isEmpty()) 2 else 8) == 0) {
                    plugins.refreshPluginSettingsPages()
                }
                val events = plugins.pendingPluginEvents()
                plugins.dispatchPluginEvents(events)
                events.filter { it.kind == "toast" && it.text.isNotBlank() }
                    .forEach { Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
