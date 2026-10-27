package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.protocol.DeepSeekOAuthManager
import com.deepseek.dsh.feature.settings.HostPermissionPreset
import com.deepseek.dsh.feature.settings.HostSessionLogUpload
import com.deepseek.dsh.feature.settings.HostTheme
import com.deepseek.dsh.runtime.host.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/**
 * Данные Host для приложения: первая загрузка, привязка к порту запущенного Host, вход в аккаунт,
 * подписки на события, настройки Host и поток открытой сессии.
 *
 * Пока Host не привязан, его адрес — заглушка `127.0.0.1:1`: потоки и настройки ждут привязки
 * ([AppRuntimeUiState.localHostBound]), иначе девять WebSocket просто не подключились бы.
 */
@Composable
internal fun HostDataSync(
    ui: AppUiState,
    runtimeUi: AppRuntimeUiState,
    repository: DshRepository,
    modelProviders: ModelProviders,
    oauthManager: DeepSeekOAuthManager,
    application: DshApplication,
    status: RuntimeStatus,
    scope: CoroutineScope,
) {
    val hostId = ui.activeHostId
    val remote = ui.currentAppSettings.remoteHostUrl.isNotBlank()
    val bound = runtimeUi.localHostBound || remote
    val account by oauthManager.accountFlow.collectAsState()

    // Первая загрузка: то, что можно прочитать без потоков.
    LaunchedEffect(hostId) {
        repository.refreshSessions(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        repository.plugins.refreshPluginInventory(hostId)
        // Каталог моста — слоты и значки, список Host — запас, если моста нет.
        repository.plugins.refreshBridgePluginCatalog()
        repository.plugins.refreshPluginSettingsPages()
        repository.tasks.refreshAutomationTasks(hostId)
        repository.tasks.refreshTeamTasks(hostId)
    }

    // Локальный Host запущен: привязать клиент к его порту и токену и перечитать всё.
    LaunchedEffect(status.phase, status.port, status.token) {
        val port = status.port ?: return@LaunchedEffect
        if (!remote) {
            // Host не принял вход — сказать один раз и предложить перезапуск, а не сыпать отказами (G-19).
            val accepted = application.bindLocalHost(port, status.token)
            runtimeUi.hostAuthFailed = !accepted
            if (!accepted) return@LaunchedEffect
        }
        repository.refreshSessions(hostId)
        modelProviders.refresh(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        repository.plugins.refreshPluginInventory(hostId)
        // Аккаунт — параллельно с каталогом плагинов: они независимы.
        val accountJob = async { if (!runtimeUi.accountHydrated) runtimeUi.accountHydrated = hydrateAccount(repository, oauthManager, hostId) }
        repository.plugins.refreshBridgePluginCatalog()
        repository.plugins.refreshPluginSettingsPages()
        accountJob.await()
        runtimeUi.pluginsHydrated = true
        repository.tasks.refreshAutomationTasks(hostId)
        repository.tasks.refreshTeamTasks(hostId)
        val baseUrl = application.defaultClient.currentBaseUrl()
        if (baseUrl.endsWith(":1")) {
            android.util.Log.w("DSH", "Host not bound yet, skip remote streams: $baseUrl")
            return@LaunchedEffect
        }
        runtimeUi.localHostBound = true
        // Дальше состояние аккаунта приходит по account/watch.
        repository.subscribeRemoteEvents(hostId)
        repository.hostSettings.syncHostLocale(hostId)
    }

    // Настройки Host нужны и без открытой сессии — как только Host доступен.
    LaunchedEffect(hostId, bound) {
        if (!bound) return@LaunchedEffect
        HostSchedules.wire(repository, hostId, scope)
        HostSessionLogUpload.wire(repository, hostId, scope)
        HostPermissionPreset.wire(repository, hostId, scope)
        HostTheme.wire(repository, hostId, scope)
    }

    // Открытая сессия: фоновые процессы и поток follow (снимок досылает историю и результаты).
    LaunchedEffect(hostId, ui.selectedSessionId, bound) {
        val sid = ui.selectedSessionId
        if (sid != null && bound) {
            repository.tasks.watchJobs(hostId, sid)
            repository.watchSession(hostId, sid)
        } else {
            repository.tasks.unwatchJobs()
        }
    }

    // Слэш-команды и навыки — по открытой сессии; commands/change тоже обновляет список.
    LaunchedEffect(hostId, ui.selectedSessionId) {
        val sid = ui.selectedSessionId ?: return@LaunchedEffect
        repository.commands.refreshHostCommands(hostId, sid)
        repository.commands.refreshHostSkills(hostId, sid)
    }

    // Вошли в аккаунт или задали ключ — провайдеры, каталог моделей и плагины меняются.
    LaunchedEffect(account.isSignedIn, account.hasApiKey) {
        if (!account.isSignedIn && !account.hasApiKey) return@LaunchedEffect
        delay(600)
        modelProviders.refresh(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        repository.plugins.refreshPluginInventory(hostId)
    }
}

/**
 * Прочитать состояние аккаунта у только что запущенного Host. Готово только при ясном ответе
 * signed-in или signed-out: пока Host поднимается, чтение повторяется — иначе первый экран
 * мигнул бы «Не вошли» перед «Вошли».
 * @return получен ли ясный ответ за 4 секунды.
 */
private suspend fun hydrateAccount(repository: DshRepository, oauthManager: DeepSeekOAuthManager, hostId: String): Boolean {
    val deadline = System.currentTimeMillis() + 4_000
    while (System.currentTimeMillis() < deadline) {
        val state = repository.account.getAccountState(hostId).getOrNull()
        if (state != null) {
            if (state.status == "credential-stored") {
                oauthManager.applyHostAccount(
                    repository.account.getAccountProfile(hostId).getOrNull(),
                    repository.account.getAccountBalance(hostId).getOrNull(),
                )
            } else if (state.status == "signed-out" && oauthManager.accountFlow.value.isSignedIn) {
                oauthManager.signOut()
            }
            return true
        }
        delay(150)
    }
    return false
}
