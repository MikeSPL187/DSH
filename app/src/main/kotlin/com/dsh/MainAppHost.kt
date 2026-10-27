package com.dsh

import com.dsh.core.protocol.parseHostJson
import androidx.compose.ui.draw.shadow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.feature.files.FilesScreen
import com.dsh.feature.files.WorkspaceManager
import com.dsh.plugin.surface.PluginOverlayContainer
import com.dsh.plugin.surface.SurfaceCatalogStore
import com.dsh.plugin.surface.SurfaceSheet
import com.dsh.runtime.host.HostProcessRunner
import com.dsh.runtime.host.HostRuntimeHolder
import com.dsh.runtime.host.HostRuntimeManager
import com.dsh.runtime.host.RuntimeLayout
import com.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MainAppHost(
    context: android.content.Context,
    runtimeManager: HostRuntimeManager,
    runner: HostProcessRunner,
    surfaceStore: SurfaceCatalogStore,
    oauthManager: com.dsh.core.protocol.DeepSeekOAuthManager,
    workspaceManager: WorkspaceManager,
    layout: RuntimeLayout,
    dshRepository: com.dsh.core.data.DshRepository,
    settingsRepository: com.dsh.core.data.DefaultSettingsRepository,
    /** Сигнал пересканировать APK-компаньоны (растёт в onResume): после установки или удаления. */
    nativePluginRescanFlow: kotlinx.coroutines.flow.StateFlow<Int> =
        kotlinx.coroutines.flow.MutableStateFlow(0),
) {
    val onboardingPreferences = remember {
        com.dsh.feature.runtime.OnboardingPreferences(context)
    }
    val runtimeUi = rememberAppRuntimeUiState(
        initialOnboarding = !onboardingPreferences.isCompleted,
        initialRootfsProgress = 0f to context.getString(R.string.runtime_checking),
    )

    val drawerUi = remember { AppDrawerState() }
    val drawerState = drawerUi.drawer
    val scope = rememberCoroutineScope()

    val updateInfo by com.dsh.feature.settings.UpdateCenter.update.collectAsState()
    val currentVersion = remember {
        com.dsh.feature.settings.UpdateCenter.currentVersion(context)
    }
    LaunchedEffect(Unit) {
        com.dsh.feature.settings.UpdateCenter.ensureChecked(context)
    }

    val persistedSettings by settingsRepository.settingsFlow.collectAsState()

    // Папка выбирается на первом запуске («Внутри DSH» или память телефона):
    // после выбора пути пересчитываются.
    var workspaceChoiceKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val externalWorkspaceDir = remember(workspaceChoiceKey) { StorageAccess.workspaceRoot(context) }
    val visibleWorkspaceDir = remember(workspaceChoiceKey) { StorageAccess.workspaceRoot(context) }

    // Android 8–10: обычный runtime-запрос; Android 11+: системный экран «Доступ ко всем файлам».
    var storageAccessTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val legacyStoragePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { storageAccessTick += 1 }
    val openAllFilesSettings: () -> Unit = {
        if (StorageAccess.usesAllFilesSettings) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            legacyStoragePermissionLauncher.launch(StorageAccess.legacyPermissions)
        }
    }
    // Доступ ко всем файлам выдаётся при первом запуске; рабочие папки — в общих Documents.
    val activeWorkspace = remember(workspaceChoiceKey) {
        externalWorkspaceDir.mkdirs()
        migrateDefaultWorkspace(externalWorkspaceDir, java.io.File(context.filesDir, HOST_SESSIONS_DIR))
        java.io.File(externalWorkspaceDir, DEFAULT_WORKSPACE_DIR_NAME).mkdirs()
        externalWorkspaceDir
    }

    val ui = rememberAppUiState(
        initialSettings = persistedSettings,
        initialWorkspace = activeWorkspace,
        initialAllFilesAccess = StorageAccess.hasAccess(context),
    )
    LaunchedEffect(workspaceChoiceKey) { if (workspaceChoiceKey > 0) ui.filesDir = activeWorkspace }
    LaunchedEffect(ui.hasAllFilesAccess, storageAccessTick) {
        ui.hasAllFilesAccess = StorageAccess.hasAccess(context)
        while (!ui.hasAllFilesAccess) {
            kotlinx.coroutines.delay(1000)
            ui.hasAllFilesAccess = StorageAccess.hasAccess(context)
        }
    }

    // Host прогревается сразу, параллельно с первым запуском и входом, а не по первому действию.
    LaunchedEffect(Unit) {
        // При первой установке — после подготовки Linux; дальше Host стартует при каждом открытии.
        if (java.io.File(context.filesDir, "rootfs/etc/os-release").exists()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { runtimeManager.warmUp(activeWorkspace.absolutePath) }
            }
        }
    }
    val availableHosts = remember {
        mutableStateListOf<com.dsh.core.model.HostDescriptor>(
            com.dsh.core.model.HostDescriptor.Local()
        )
    }
    // Вклады плагинов в слоты и нативные страницы используют один pluginRpc: он передаётся
    // в NativeHost, и NativeSlotHost превращает базовый host в PluginRpcHost.
    val slotPluginRpc: suspend (String, kotlinx.serialization.json.JsonObject) -> Result<String> =
        remember(ui.activeHostId) {
            { method, payload ->
                val first = dshRepository.plugins.pluginRpc(ui.activeHostId, method, payload.toString())
                val firstText = first.getOrNull() ?: first.exceptionOrNull()?.message
                if (ensureMissingPluginDir(context, firstText)) {
                    dshRepository.plugins.pluginRpc(ui.activeHostId, method, payload.toString())
                } else {
                    first
                }
            }
        }
    com.dsh.plugin.nativehost.NativeHost.rpcHandler = slotPluginRpc

    val repoSessions by dshRepository.getSessions(ui.activeHostId).collectAsState(initial = emptyList())
    val sessions = repoSessions

    val pendingMessages = remember { androidx.compose.runtime.mutableStateListOf<ChatMessage>() }

    // Режим плана не сбрасывается по id сессии: первый prompt создаёт сессию и меняет
    // ui.selectedSessionId — сброс по id выключал бы план сразу после первой отправки.

    // Вложения из системного выбора файлов (уже скопированы в attachments/ рабочей папки).
    val chatAttachments = remember { androidx.compose.runtime.mutableStateListOf<com.dsh.feature.chat.ChatAttachment>() }
    // При смене сессии неотправленные вложения очищаются, чтобы не уйти в другую сессию.
    LaunchedEffect(ui.selectedSessionId) {
        chatAttachments.clear()
        // Права «до первого сообщения» — только для новой сессии; отправка уже взяла их с собой.
        if (ui.selectedSessionId != null) ui.pendingPermissionPreset = null
    }
    // Новый чат в проекте с клонированным чужим кодом — «Только чтение» (G-50), пока пользователь не выберет другое.
    LaunchedEffect(ui.selectedSessionId, runtimeUi.activeWorkspaceName) {
        clonedProjectPreset(
            cloned = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.dsh.runtime.host.ClonedProjects.contains(layout, runtimeUi.activeWorkspaceName) },
            sessionId = ui.selectedSessionId,
            pending = ui.pendingPermissionPreset,
        )?.let { ui.pendingPermissionPreset = it }
    }
    val liveSessionIds by dshRepository.liveSessionIdsFlow.collectAsState()
    val runningSessionIds by dshRepository.runningSessionIdsFlow.collectAsState()
    val unreadSessionIds by dshRepository.unreadSessionIdsFlow.collectAsState()
    LaunchedEffect(ui.selectedSessionId) { dshRepository.markSessionViewed(ui.selectedSessionId) }

    LaunchedEffect(sessions, liveSessionIds) {
        // При открытии приложения — последняя обновлённая сессия, а не пустая новая.
        if (!runtimeUi.didInitialResume && sessions.isNotEmpty()) {
            runtimeUi.didInitialResume = true
            val candidates = sessions.filter { !it.archived }.ifEmpty { sessions }
            ui.selectedSessionId = (
                candidates.filter { it.sessionId in liveSessionIds }.ifEmpty { candidates }
                    .maxByOrNull { it.updatedAt }
                )?.sessionId
        }
    }

    val activeSession = sessions.find { it.sessionId == ui.selectedSessionId }

    val repoMessages by dshRepository.getMessages(ui.activeHostId, ui.selectedSessionId ?: "")
        .collectAsState(initial = emptyList())
    val messages = remember(repoMessages, pendingMessages.toList(), ui.selectedSessionId) {
        // Эхо user/message от Host и мгновенный пузырь могут быть одновременно:
        // дубли по содержимому убираются.
        val echoed = repoMessages.filter { it.role == MessageRole.USER }.takeLast(16)
        val openSession = ui.selectedSessionId ?: PENDING_SESSION
        repoMessages + pendingMessages.filter { pending ->
            // Неотправленное сообщение видно только в своей сессии.
            pending.sessionId == openSession &&
            // Эхом считается тот же текст в пределах 60 с — повторную отправку
            // того же текста пользователем не глотаем. Неотправленное эхом не считается.
            (pending.failed || echoed.none { real ->
                real.content.trim() == pending.content.trim() &&
                    kotlin.math.abs(real.createdAt - pending.createdAt) < 60_000
            })
        }
    }


    // Уведомление о завершении: сессия перешла из «идёт ход» в «готово».
    // Ход, закончившийся ошибкой («⚠️ …»), уведомляет по настройке «Ошибки»
    // (раньше она только сохранялась), обычный — по «Завершение ответа».
    val sessionRunningNow = ui.selectedSessionId != null && ui.selectedSessionId in runningSessionIds
    val latestMessages by androidx.compose.runtime.rememberUpdatedState(messages)
    LaunchedEffect(sessionRunningNow) {
        if (runtimeUi.wasSessionRunning && !sessionRunningNow) {
            val settings = ui.currentAppSettings
            val title = sessions.find { it.sessionId == ui.selectedSessionId }?.title ?: context.getString(R.string.notify_session)
            val failed = latestMessages.lastOrNull()?.let {
                it.role == MessageRole.ASSISTANT && it.content.trimStart().startsWith("⚠️")
            } == true
            if (settings.notificationsEnabled) {
                if (failed && settings.notifyOnError) {
                    DshNotifications.notify(context, context.getString(R.string.notify_error_title), context.getString(R.string.notify_error_body, title))
                } else if (!failed && settings.notifyOnTurnComplete) {
                    DshNotifications.notify(context, context.getString(R.string.notify_done_title), context.getString(R.string.notify_done_body, title))
                }
            }
        }
        runtimeUi.wasSessionRunning = sessionRunningNow
    }

    val catalogInfo by dshRepository.hostSettings.modelCatalogFlow.collectAsState()
    val hostModels = remember(catalogInfo) { catalogInfo.groups.flatMap { it.models } }
    val hostModelNames = remember(hostModels) { hostModels.map { it.name } }
    val hostModelIdByName = remember(hostModels) { hostModels.associate { it.name to it.id } }

    // Host стартует не сразу (proot, DSH): до ответа каталога меню моделей берёт кэш.
    val cachedModels = remember { ModelCatalogCacheState(readCachedModelCatalog(context)) }
    LaunchedEffect(catalogInfo.groups) {
        if (catalogInfo.groups.isNotEmpty()) {
            cachedModels.groups = catalogInfo.groups
            writeCachedModelGroups(context, catalogInfo.groups)
        }
    }
    // Каталог обновляется по событиям Host (`$events`: llm/adapters-updated,
    // settings/document-updated, credentials/*-updated) —
    // DshRepository перечитывает его сам — опроса здесь нет.
    LaunchedEffect(hostModels) {
        if (hostModels.isNotEmpty()) {
            cachedModels.names = hostModels.map { it.name }
            cachedModels.ids = hostModels.map { it.id }
            writeCachedModelNames(context, cachedModels.names, cachedModels.ids)
        }
    }


    // Подписка на каталог: чтение StateFlow.value в composition не перерисовывает UI при изменениях.
    val bridgeCatalogState by dshRepository.plugins.bridgePluginCatalogFlow.collectAsState()
    val voiceEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == VoiceInputPluginUi.bundle && it.enabled
        }
    }
    NotificationPermissionEffect(ui.currentAppSettings.notificationsEnabled)
    val voice = rememberVoiceInput(runtimeUi, dshRepository, { ui.activeHostId }, scope)
    // Перевод рассуждений (плагин cot-translation): при включённом плагине
    // у рассуждения появляется «Показать перевод / оригинал».
    val cotTranslationEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == CotTranslationPluginUi.bundle && it.enabled
        }
    }

    // Полоса модов Claude Code над полем ввода: подписка, только если плагин
    // субагентов Claude Code включён и сессия открыта.
    val claudeCodeModsEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == com.dsh.feature.chat.ClaudeCodeModsPluginUi.BUNDLE && it.enabled
        }
    }
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId, claudeCodeModsEnabled) {
        runtimeUi.bandSnapshot = null
        val sid = ui.selectedSessionId
        if (claudeCodeModsEnabled && sid != null) {
            dshRepository.claudeCodeBand(ui.activeHostId, sid).collect { snapshot -> runtimeUi.bandSnapshot = snapshot }
        }
    }

    // Диагностика сессии (инструменты разработчика): сырые кадры сессии, только чтение.
    val sessionInspectorEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == SessionInspectorPluginUi.BUNDLE && it.enabled
        }
    }
    LaunchedEffect(ui.sessionInspectorOpen, ui.selectedSessionId) {
        while (ui.sessionInspectorOpen) {
            runtimeUi.rawSessionFrames = ui.selectedSessionId?.let { dshRepository.rawSessionFrames(it) } ?: emptyList()
            kotlinx.coroutines.delay(1_000)
        }
    }
    PluginChangeNoticeDialog(ui, scope)
    if (ui.sessionInspectorOpen) {
        SessionInspectorOverlayDialog(
            frames = runtimeUi.rawSessionFrames,
            sessionId = ui.selectedSessionId,
            onDismiss = { ui.sessionInspectorOpen = false },
        )
    }

    val builtInPluginsList by dshRepository.plugins.builtInPluginsFlow.collectAsState()
    val pluginSnapshot by dshRepository.plugins.pluginSnapshotFlow.collectAsState()
    val pluginManagerSnapshot by dshRepository.plugins.pluginManagerSnapshotFlow.collectAsState()
    val pluginRows by dshRepository.plugins.pluginRowsFlow.collectAsState()
    // Каталог страницы плагинов — из bridge/v1 (регистрации слотов), запас — список Host.
    val bridgePluginCatalog by dshRepository.plugins.bridgePluginCatalogFlow.collectAsState()
    val bridgeWarnings by dshRepository.plugins.bridgeWarningsFlow.collectAsState()
    // Точки входа плагинов (слоты settings.section и др.): настройки рисуют их на своих местах.
    val pluginSlotEntries by dshRepository.plugins.pluginSlotEntriesFlow.collectAsState()
    val pluginSettingsPages by dshRepository.plugins.pluginSettingsPagesFlow.collectAsState()
    // Места настроек, объявленные Host: «Настройки встроенных плагинов» связывают
    // официальные страницы с настоящими пространствами настроек.
    val pluginSlotSurfaces by dshRepository.plugins.pluginSlotSurfacesFlow.collectAsState()
    // Кто официальный, а кто плагин — решает Host (принадлежность host-slots моста).
    val officialModelNamespaces = dshRepository.plugins.officialSettingsNamespaces()
    val modelProviders = remember(dshRepository) { ModelProviders(dshRepository) }

    // Зарегистрированные провайдеры: в каталоге «Добавить» их нет, они настраиваются своими карточками.

    val automationTasks by dshRepository.tasks.automationTasksFlow.collectAsState()
    val teamTasks by dshRepository.tasks.teamTasksFlow.collectAsState()
    val agentTeam by dshRepository.tasks.agentTeamFlow.collectAsState()
    // Действия команды — только при включённом профиле «Команда агентов».
    val agentTeamEnabled = remember(bridgePluginCatalog) {
        bridgeCatalogState.bundles.any {
            it.name == AgentTeamPluginUi.BUNDLE && it.enabled
        }
    }
    LaunchedEffect(ui.selectedSessionId, ui.activeHostId, agentTeamEnabled) {
        if (agentTeamEnabled) {
            runtimeUi.agentTeamLoading = true
            dshRepository.tasks.refreshAgentTeam(ui.activeHostId, ui.selectedSessionId)
            runtimeUi.agentTeamLoading = false
        }
    }
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId, messages.size) {
        ui.selectedSessionId?.let { sid ->
            dshRepository.refreshSessionStats(ui.activeHostId, sid)
        }
    }

    // Во время хода статистика внизу (ток/с, кэш, контекст) обновляется; после — ещё раз, итоговая.
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId, sessionRunningNow) {
        val sid = ui.selectedSessionId ?: return@LaunchedEffect
        if (sessionRunningNow) {
            while (true) {
                // Статистика нужна только на экране; в фоне не опрашиваем Host каждые 1,2 с.
                if (UserActivity.isForeground) {
                    dshRepository.refreshSessionStats(ui.activeHostId, sid)
                }
                kotlinx.coroutines.delay(1200)
            }
        } else {
            dshRepository.refreshSessionStats(ui.activeHostId, sid)
        }
    }


    val backupAndDiag = remember {
        com.dsh.runtime.host.RuntimeBackupAndDiagnostics(layout, KeystoreBackupSigner)
    }
    // Установщик плагинов на стороне приложения: pnpm в Host под proot не может
    // rename/unlink/mkdtemp (ENOSYS), поэтому после ошибки Host приложение само скачивает,
    // распаковывает, ставит зависимости и подключает пакет к слою patch профиля.
    val appPluginInstaller = remember(layout) {
        com.dsh.runtime.host.PluginBundleInstaller(
            context,
            layout,
            workspaceDir = { activeWorkspace },
        )
    }
    // Пакеты, поставленные установщиком приложения: так официальные «из коробки» отличаются от поставленных пользователем.
    LaunchedEffect(appPluginInstaller) {
        runtimeUi.managedPluginPackages = runCatching { appPluginInstaller.managedPackages() }
            .getOrDefault(emptySet())
    }

    val bundleSelection = remember { BundleSelection(context) }
    val bundleToggle = remember(dshRepository, runner) { BundleToggle(context, dshRepository, runner, ui, bundleSelection) }


    // «Работать в фоне, пока есть задачи» решает фоновая служба, когда приложение смахивают.
    LaunchedEffect(ui.currentAppSettings.keepAliveOnTask) {
        com.dsh.runtime.host.HostService.setKeepAliveOnTaskRemoved(
            context,
            ui.currentAppSettings.keepAliveOnTask,
        )
    }
    ToolPolicyEffect(dshRepository)
    val nativePlugins = rememberNativePlugins(runtimeUi, layout, appPluginInstaller, nativePluginRescanFlow, scope)

    val status by runner.statusFlow.collectAsState()

    // Инструменты workflow в профиле телефона по умолчанию выключены: включаем через API, как на компьютере.
    LaunchedEffect(ui.activeHostId, status.phase) {
        if (status.phase == RuntimePhase.RUNNING) {
            ensureOfficialWorkflowEnabled(dshRepository, ui.activeHostId)
        }
    }
    val catalog by surfaceStore.catalogFlow.collectAsState()
    val accountState by oauthManager.accountFlow.collectAsState()
    val pendingApprovalWaterfalls by dshRepository.approvals.pendingWaterfallApprovalsFlow.collectAsState()
    // Текст из «Плагинов» («Спросить», «Создать с агентом») — в черновик новой сессии.
    LaunchedEffect(ui.pendingComposerText, ui.activePage) {
        val text = ui.pendingComposerText
        if (text != null && ui.activePage == ActivePage.CHAT) {
            runtimeUi.voiceTranscript = text
            ui.pendingComposerText = null
        }
    }
    val sessionPreviews by remember(ui.activeHostId) { dshRepository.sessionPreviews(ui.activeHostId) }.collectAsState(initial = emptyMap())
    val pendingQuestions by dshRepository.approvals.pendingQuestionsFlow.collectAsState()
    AgentWaitingNotificationsEffect(
        approvalIds = pendingApprovalWaterfalls.map { it.eventId },
        questionIds = pendingQuestions.map { it.eventId },
        notificationsEnabled = ui.currentAppSettings.notificationsEnabled,
    )
    val hostJobs by dshRepository.tasks.jobsFlow.collectAsState()
    // Авто-сон (G-13): простой — это не только «нет хода агента». Живые команды терминала, фоновые процессы,
    // ожидающие одобрения и вопросы держат Host.
    IdleHostStopEffect(
        runtimeManager, runner, persistedSettings.idleStopMinutes,
        busy = runningSessionIds.isNotEmpty() || pendingApprovalWaterfalls.isNotEmpty() || pendingQuestions.isNotEmpty() ||
            hostJobs.any { it.finishedAt == null } || com.dsh.terminal.TerminalHub.anyLive(),
        workspace = activeWorkspace,
        notificationsEnabled = ui.currentAppSettings.notificationsEnabled,
    )
    // Непоказанные бонусы аккаунта (account/getUnnotifiedBonuses); без бонусов ничего не рисуется.
    AccountBonusHost(
        repository = dshRepository,
        hostId = ui.activeHostId,
        hostBound = runtimeUi.localHostBound,
    )

    val hostWorkspaces by dshRepository.hostWorkspacesFlow.collectAsState()
    val dshApplication = context.applicationContext as DshApplication

    // Сначала каталог Host; пока Host запускается — кэш, чтобы не мигало «Выберите модель».
    val effectiveModelNames = remember(hostModelNames, cachedModels.names) {
        if (hostModelNames.isNotEmpty()) hostModelNames else cachedModels.names
    }
    val effectiveModelIdByName = remember(
        hostModelNames, hostModelIdByName, cachedModels.names, cachedModels.ids
    ) {
        if (hostModelNames.isNotEmpty()) hostModelIdByName
        else cachedModels.names.zip(cachedModels.ids).toMap()
    }
    val availableModelNames = effectiveModelNames.distinct()

    val openUrl: (String) -> Unit = { url -> openWebLink(context, url) }
    val currentWorkspaceForAccount = androidx.compose.runtime.rememberUpdatedState(activeWorkspace)
    val hostAccount = rememberHostAccount(
        ui, dshRepository, oauthManager, runtimeManager, scope,
        workspace = { currentWorkspaceForAccount.value },
        baseUrl = { dshApplication.defaultClient.currentBaseUrl() },
    )

    HostDataSync(ui, runtimeUi, dshRepository, modelProviders, oauthManager, dshApplication, status, scope)

    // Запуск: Linux установлен и не требует переустановки, есть доступ к папке, Host отвечает,
    // аккаунт и плагины загружены. До этого — экраны запуска.
    if (!StartupGate(runtimeUi, ui, runtimeManager, status, activeWorkspace, pluginSnapshot != null, openAllFilesSettings, scope)) return

    if (runtimeUi.showOnboarding) {
        FirstRunOnboarding(
            runtimeUi = runtimeUi,
            ui = ui,
            scope = scope,
            dshRepository = dshRepository,
            runtimeManager = runtimeManager,
            activeWorkspace = activeWorkspace,
            modelProviders = modelProviders,
            onboardingPreferences = onboardingPreferences,
            settingsRepository = settingsRepository,
            oauthManager = oauthManager,
            openAllFilesSettings = openAllFilesSettings,
            onWorkspaceChosen = { workspaceChoiceKey++ },
            backup = backupAndDiag,
        )
        return
    }

    // Имя папки на диске постоянное, на экране — на языке интерфейса.
    val defaultWorkspaceLabel = stringResource(R.string.workspace_default)
    fun workspaceLabelOf(name: String) = if (name == DEFAULT_WORKSPACE_DIR_NAME) defaultWorkspaceLabel else name
    val currentWorkspace = androidx.compose.runtime.rememberUpdatedState(activeWorkspace)
    val chatSender = remember(dshRepository, runtimeManager) {
        ChatSender(context, scope, dshRepository, ui, runtimeUi, runtimeManager, pendingMessages, chatAttachments) { currentWorkspace.value }
    }
    val activeWorkspaceFolderPath = java.io.File(activeWorkspace, runtimeUi.activeWorkspaceName).absolutePath

    // Системный выбор файлов (SAF): несколько картинок или любых файлов копируются в
    // рабочую папку и показываются в поле ввода; при отправке — ссылкой @<путь> в начале сообщения.
    fun importAttachments(uris: List<android.net.Uri>, afterImport: () -> Unit = {}) {
        if (uris.isNotEmpty()) {
            val workspaceDir = java.io.File(activeWorkspace, runtimeUi.activeWorkspaceName)
            // Большие файлы копируются в потоке IO, чтобы не тормозили клавиатура и интерфейс.
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.dsh.AttachmentImporter.import(context, workspaceDir, uris).also { afterImport() }
                }
                result.added.forEach { added ->
                    if (chatAttachments.none { it.workspaceRelativePath == added.workspaceRelativePath }) {
                        chatAttachments.add(added)
                    }
                }
                runtimeUi.workspaceRevision += 1
                result.skipped.forEach { name ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.attachment_copy_failed, name),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val attachmentPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<android.net.Uri> -> importAttachments(uris) }
    // Фото — системный выбор (Android сам показывает недавние), без доступа ко всей галерее.
    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<android.net.Uri> -> importAttachments(uris) }
    // Камера: снимок во временный файл приложения, затем в папку проекта, как любое вложение.
    // Пока открыта камера, система часто убивает DSH (G-20): адрес снимка переживает пересоздание (rememberSaveable),
    // иначе фото молча терялось. Временный файл снимка после копирования в проект удаляется.
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val cameraDir = remember { java.io.File(context.cacheDir, "camera") }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        cameraUri = null
        val cleanup = { cameraDir.listFiles()?.forEach { it.delete() }; Unit }
        if (ok && uri != null) importAttachments(listOf(uri), cleanup) else scope.launch(kotlinx.coroutines.Dispatchers.IO) { cleanup() }
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) }

    HostRestartBridgeEffect(ui.activeHostId, dshRepository, runtimeManager, activeWorkspace)
    PluginPollingEffect(ui, dshRepository, runner, bundleSelection)
    val workspaceOps = remember(activeWorkspace, ui.activeHostId) {
        WorkspaceOps(
            root = activeWorkspace,
            host = object : WorkspaceHost {
                override suspend fun create(path: String) = dshRepository.createWorkspace(ui.activeHostId, path)
                override suspend fun rename(workspaceId: String, title: String) = dshRepository.workspaceRename(ui.activeHostId, workspaceId, title).isSuccess
                override suspend fun delete(workspaceId: String) = dshRepository.deleteWorkspace(ui.activeHostId, workspaceId)
                override suspend fun renameSession(sessionId: String, title: String) = dshRepository.renameSession(ui.activeHostId, sessionId, title)
            },
            created = object : CreatedProjectsLog {
                override fun add(name: String) = com.dsh.runtime.host.CreatedProjects.add(layout, name)
                override fun rename(from: String, to: String) {
                    com.dsh.runtime.host.CreatedProjects.rename(layout, from, to)
                    com.dsh.runtime.host.ClonedProjects.rename(layout, from, to)
                }
            },
            hostIdOf = { path -> dshRepository.hostWorkspacesFlow.value.forFolder(path, activeWorkspace)?.id },
        )
    }
    WorkspaceDialogs(ui, runtimeUi, workspaceOps, scope, runningSessionIds)


    // Запрос агента на действие (approval/request): в чате — карточкой в ходе работы или экраном удаления.
    val chatApproval: com.dsh.feature.chat.ChatApproval? = pendingApprovalWaterfalls.firstOrNull()?.let { waterfall ->
        fun str(k: String) = (waterfall.request[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val toolName = str("toolName").orEmpty().ifBlank { "tool" }
        // Причина на языке интерфейса, если Host прислал перевод.
        val localizedReason = (waterfall.request["displayReason"] as? kotlinx.serialization.json.JsonObject)?.let { map ->
            val lang = java.util.Locale.getDefault().language
            ((map[lang] ?: map["en"]) as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: str("reason")
        val callId = str("callId")
        // Аргументы вызова — из сообщения инструмента с тем же callId.
        val callArgs = remember(callId, messages) {
            if (callId.isNullOrBlank()) null else messages.lastOrNull { m ->
                m.role == com.dsh.core.model.MessageRole.TOOL && m.content.split(2.toChar()).getOrNull(2) == callId
            }?.content?.split(2.toChar())?.getOrNull(6)
        }
        val local = ui.activeHostId == com.dsh.core.model.HostDescriptor.Local().id
        val workspacePath = sessions.find { it.sessionId == ui.selectedSessionId }?.workspacePath ?: activeWorkspace.path
        com.dsh.feature.chat.ChatApproval(
            eventId = waterfall.eventId,
            toolName = toolName,
            reason = localizedReason,
            args = callArgs,
            callId = callId,
            onAllow = { always ->
                // «Разрешать такие действия без вопроса» — то же правило, что в Плагины → инструменты.
                if (always) com.dsh.feature.settings.logic.ToolPolicy.set(toolName, "auto")
                scope.launch { dshRepository.approvals.respondApprovalWaterfall(ui.activeHostId, waterfall.eventId, true) }
            },
            onReject = { scope.launch { dshRepository.approvals.respondApprovalWaterfall(ui.activeHostId, waterfall.eventId, false) } },
            inspect = if (local) ({ paths -> inspectDeletion(layout.rootfsDir, activeWorkspace, workspacePath, paths) }) else null,
        )
    }

    // Агент ждёт решения, а открыт другой экран — плашка внизу ведёт в чат.
    if (chatApproval != null && ui.activePage != ActivePage.CHAT) {
        androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.BottomCenter) {
            val c = com.dsh.core.designsystem.Dsh.colors
            val shape = androidx.compose.foundation.shape.CircleShape
            Row(
                Modifier.navigationBarsPadding().padding(bottom = 16.dp)
                    .then(Modifier.shadow(14.dp, shape)).clip(shape).background(c.t1)
                    .clickable { ui.activePage = ActivePage.CHAT }
                    .padding(start = 16.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(shape).background(c.warn))
                Text(stringResource(R.string.approval_waiting_banner), color = c.bg, style = com.dsh.feature.chat.ckBody(14.sp))
            }
        }
    }

    // user-questions/request: нативное окно ответа (варианты или свой ответ).
    pendingQuestions.firstOrNull()?.let { waterfall ->
        val questions = waterfall.request["questions"]
            as? kotlinx.serialization.json.JsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        // Проверка плана — свой экран; «Назад» прячет его, план ждёт решения (карточка в чате).
        val review = remember(waterfall.eventId) { planReviewOf(waterfall.eventId, questions) }
        if (review != null) {
            androidx.compose.animation.AnimatedVisibility(
                visible = ui.hiddenPlanReview != review.eventId,
                enter = androidx.compose.animation.slideInHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
                exit = androidx.compose.animation.slideOutHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
            ) { com.dsh.feature.chat.PlanReviewScreen(
                review = review,
                session = sessions.find { it.sessionId == ui.selectedSessionId }?.title,
                onBack = { ui.hiddenPlanReview = review.eventId; ui.activePage = ActivePage.CHAT },
                onApprove = { scope.launch { dshRepository.approvals.respondUserQuestion(ui.activeHostId, review.eventId, planReviewAnswer(review, null)) } },
                onFeedback = { text -> scope.launch { dshRepository.approvals.respondUserQuestion(ui.activeHostId, review.eventId, planReviewAnswer(review, text)) } },
            ) }
        } else UserQuestionDialog(
            questions = questions,
            onSubmit = { answers ->
                scope.launch {
                    dshRepository.approvals.respondUserQuestion(ui.activeHostId, waterfall.eventId, answers)
                }
            },
            onDismiss = {
                scope.launch {
                    dshRepository.approvals.respondUserQuestion(
                        ui.activeHostId,
                        waterfall.eventId,
                        "{\"answers\":[]}",
                    )
                }
            },
        )
    }

    // Встроенный браузер — поверх текущей страницы; «Назад» возвращает туда, откуда открыли.
    androidx.compose.animation.AnimatedVisibility(
        visible = ui.browser != null,
        enter = androidx.compose.animation.slideInHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
        exit = androidx.compose.animation.slideOutHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
    ) {
        // Цель запоминается на время анимации ухода: ui.browser к этому моменту уже null.
        val target = remember { mutableStateOf<BrowserTarget?>(null) }
        ui.browser?.let { target.value = it }
        target.value?.let { t -> androidx.compose.runtime.key(t) { BrowserScreen(t, onClose = { ui.browser = null }) } }
    }

    // Вывод фонового процесса (job/follow).
    runtimeUi.jobOutputTarget?.let { job ->
        JobOutputDialog(
            job = job,
            output = runtimeUi.jobOutputText,
            statusLine = runtimeUi.jobOutputStatus,
            onDismiss = { runtimeUi.jobOutputTarget = null },
        )
    }

    // Страница «Файлы» открыта: изменения в папке проекта (в том числе от агента) сразу видны.
    WatchDirectory(activeWorkspaceFolderPath, enabled = ui.activePage == ActivePage.FILES) { runtimeUi.filesRevision += 1 }
    // Импорт файлов с телефона в открытую папку проекта: Host видит только папки проектов.
    val filesImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.dsh.AttachmentImporter.importInto(context, ui.filesDir, uris)
                }
                runtimeUi.filesRevision += 1
                runtimeUi.workspaceRevision += 1
                if (result.added.isNotEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            com.dsh.feature.files.R.string.files_imported,
                            result.added.size,
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                result.skipped.forEach { name ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.attachment_copy_failed, name),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }



    // Содержимое страницы «Файлы».
    val projectDir = java.io.File(activeWorkspaceFolderPath)
    val filesGit = remember(activeWorkspace, runtimeUi.activeWorkspaceName) {
        WorkspaceFilesGit(com.dsh.runtime.host.WorkspaceGit(layout, activeWorkspace), runtimeUi.activeWorkspaceName)
    }
    // Прикрепить файл проекта к следующему сообщению: путь от корня рабочих папок (@путь).
    fun attachToChat(file: java.io.File) {
        val rel = file.absolutePath.removePrefix(activeWorkspace.absolutePath).trimStart('/').replace('\\', '/')
        if (rel.isNotBlank() && chatAttachments.none { it.workspaceRelativePath == rel }) {
            chatAttachments.add(
                com.dsh.feature.chat.ChatAttachment(displayName = file.name, workspaceRelativePath = rel, sizeBytes = file.length(), localPath = file.absolutePath),
            )
        }
    }
    val workspaceFilesContent: @Composable () -> Unit = {
        val askTemplate = stringResource(com.dsh.feature.files.R.string.fs_ask_prompt)
        FilesScreen(
            root = projectDir,
            rootLabel = workspaceLabelOf(runtimeUi.activeWorkspaceName),
            refreshKey = runtimeUi.filesRevision,
            git = filesGit,
            onClose = { ui.activePage = ActivePage.CHAT },
            onAttach = { f -> attachToChat(f); ui.activePage = ActivePage.CHAT },
            onAsk = { f ->
                attachToChat(f)
                runtimeUi.voiceTranscript = askTemplate.format(f.name)
                ui.activePage = ActivePage.CHAT
            },
            onReview = { text ->
                // Как в прототипе: новая сессия, запрос уходит сразу.
                ui.selectedSessionId = null
                ui.pendingAutoSend = text
                ui.activePage = ActivePage.CHAT
            },
            commitMessage = { info -> com.dsh.ai.CommitMessageWriter.write(context, filesGit, info) },
            onShare = { f ->
                if (!shareWorkspaceFile(context, f)) android.widget.Toast.makeText(context, context.getString(R.string.share_failed), android.widget.Toast.LENGTH_LONG).show()
            },
            onImport = { dir ->
                ui.filesDir = dir
                filesImportLauncher.launch(arrayOf("*/*"))
            },
            onToast = { text -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() },
            markdown = { text -> com.dsh.feature.chat.MarkdownMessageText(text) },
            onOpenInBrowser = { f ->
                BrowserUrls.workspaceUrl(projectDir, f)?.let { url -> ui.browser = BrowserTarget(url, projectDir) }
            },
        )
    }


    // Терминал: страница (с «Назад») и вкладка боковой панели (без неё) — одна связка с Host.
    // Оболочка открывается в последней сессии, если ни одна не выбрана.
    val terminalDistro = remember(ui.activeHostId) {
        if (ui.activeHostId != com.dsh.core.model.HostDescriptor.Local().id) null
        else runCatching {
            java.io.File(layout.rootfsDir, "etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')?.substringBefore(" GNU/Linux")?.substringBefore(" (")
        }.getOrNull()
    }
    val terminalRoute: @Composable (Boolean) -> Unit = { page ->
        // Адрес из вывода (dev-сервер в Linux DSH) открывается во встроенном браузере.
        // Лямбда стабильна: она ключ remember вывода блоков, новая на каждый кадр пересчитывала бы вывод.
        val openUrl = remember(ui) { { url: String -> ui.browser = BrowserTarget(url) } }
        androidx.compose.runtime.CompositionLocalProvider(LocalOpenUrl provides openUrl) {
        com.dsh.terminal.DshTerminalRoute(
            repository = dshRepository,
            hostId = ui.activeHostId,
            sessionId = ui.selectedSessionId ?: sessions.firstOrNull()?.sessionId,
            distro = terminalDistro,
            onBack = if (page) ({ ui.activePage = ActivePage.CHAT }) else null,
        )
        }
    }

    // Вкладки, которые плагины регистрируют в панели: приложение даёт только контейнер.
    val rightPanePluginTabs = com.dsh.plugin.nativehost.nativeRightPaneTabs()
    val rightPaneGuideEntries = com.dsh.plugin.nativehost.nativeGuideEntries()
    var pluginPanesOpen by remember { mutableStateOf(false) }

    val workspaceFolders = remember(
        sessions, ui.selectedSessionId, activeWorkspace, runtimeUi.workspaceRevision, hostWorkspaces,
        runningSessionIds, unreadSessionIds,
    ) {
        buildWorkspaceFolders(
            // Корень DSH-Workspace сам не рабочая папка — только его подпапки.
            dirs = workspaceManager.listWorkspaceDirectories(activeWorkspace)
                .filter { it.absolutePath != activeWorkspace.absolutePath },
            root = activeWorkspace,
            sessions = sessions,
            hostWorkspaces = hostWorkspaces,
            selectedSessionId = ui.selectedSessionId,
            runningIds = runningSessionIds,
            unreadIds = unreadSessionIds,
            timeAgo = { updatedAt -> timeAgoLabel(context, updatedAt) },
        )
    }

    // Терминал появляется, только когда в Host включён плагин терминала —
    // тот же переключатель, что на странице плагинов; своей настройки у клиента нет.
    val terminalSupported = remember(bridgePluginCatalog) {
        // Переключатель терминала — необязательный bundle «Постоянный терминал» (@deepseek-ai/dsh-experimental-terminal-
        // bundle): по умолчанию выключен, включается на странице плагинов. Раньше проверялась
        // строка terminal-controller — она из базового bundle и включена всегда, поэтому
        // терминал показывался по умолчанию (на компьютере его нет).
        bridgePluginCatalog.bundles.any { bundle ->
            bundle.name == "@deepseek-ai/dsh-experimental-terminal-bundle" && bundle.enabled
        }
    }
    LaunchedEffect(terminalSupported, ui.activePage) {
        if (!terminalSupported && ui.activePage == ActivePage.TERMINAL) ui.activePage = ActivePage.CHAT
    }

    val chatServices = remember(dshRepository) { AppChatServices(context, dshRepository, ui, scope) }
    androidx.compose.runtime.CompositionLocalProvider(com.dsh.feature.chat.LocalChatServices provides chatServices) {
    Box(modifier = Modifier.fillMaxSize()) {
        com.dsh.core.designsystem.DshSheetScaffold(
            sheet = drawerUi.sheet,
            onDismiss = { drawerUi.sheet = null },
        ) {
        com.dsh.feature.sessions.DshPushDrawer(
            state = drawerState,
            drawer = {
                AppDrawer(
                    state = drawerUi, ui = ui, runtimeUi = runtimeUi, repository = dshRepository, scope = scope,
                    folders = workspaceFolders, hostWorkspaces = hostWorkspaces, workspaceRoot = activeWorkspace, sessions = sessions,
                    waitingIds = pendingApprovalWaterfalls.mapNotNull { it.agentId }.toSet(),
                    previews = sessionPreviews,
                    hostLive = status.phase == RuntimePhase.RUNNING || ui.activeHostId != com.dsh.core.model.HostDescriptor.Local().id,
                    defaultWorkspaceLabel = defaultWorkspaceLabel,
                    pluginsOn = bridgePluginCatalog.bundles.count { it.enabled } +
                        runtimeUi.nativePluginItems.count { it.statusLabel == context.getString(R.string.plugin_status_installed) },
                    pluginsAwaitingTrust = nativePlugins.pendingApprovals.size,
                    userName = accountState.profile?.name,
                    updateVersion = updateInfo?.tag?.takeIf { com.dsh.feature.settings.UpdateCenter.hasUpdate(context) },
                )
            },
        ) {
            // У чата полная шапка; у страниц со своим заголовком второй шапки нет.
            // Вложенные страницы «Плагинов», «Автоматизации» и «Настроек» закрывает их собственный стек.
            BackHandler(enabled = ui.activePage != ActivePage.CHAT) { ui.activePage = ActivePage.CHAT }
            // Шапка чата живёт внутри экрана чата: под листами она уменьшается вместе с ним.
            val chatTopBar: @Composable (Boolean) -> Unit = { scrolled ->
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
                            ModernTopAppBar(
                                workspace = runtimeUi.activeWorkspaceName,
                                live = status.phase == RuntimePhase.RUNNING || ui.activeHostId != com.dsh.core.model.HostDescriptor.Local().id,
                                scrolled = scrolled,
                                onNew = {
                                    // Новая сессия создаётся на Host только с первым сообщением.
                                    ui.selectedSessionId = null
                                    ui.activePage = ActivePage.CHAT
                                },
                                title = if (messages.isEmpty()) {
                                    ""
                                } else {
                                    sessions.find { it.sessionId == ui.selectedSessionId }?.title
                                        ?: stringResource(R.string.app_name)
                                },
                                onToggleRightPane = if (rightPanePluginTabs.isNotEmpty() || rightPaneGuideEntries.isNotEmpty()) ({ pluginPanesOpen = true }) else null,
                                teamTasks = teamTasks,
                                hostJobs = hostJobs,
                                onKillJob = { job ->
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            dshRepository.tasks.killJob(ui.activeHostId, sid, job.id)
                                        }
                                    }
                                },
                                onOpenJobOutput = { job ->
                                    runtimeUi.jobOutputTarget = job
                                    runtimeUi.jobOutputText = ""
                                    runtimeUi.jobOutputStatus = null
                                    scope.launch {
                                        dshRepository.tasks.followJob(
                                            ui.activeHostId,
                                            job.owner ?: ui.selectedSessionId,
                                            job.id,
                                        ).collect { frameJson ->
                                            val frame = runCatching {
                                                kotlinx.serialization.json.Json
                                                    .parseHostJson(frameJson)
                                                    as? kotlinx.serialization.json.JsonObject
                                            }.getOrNull() ?: return@collect
                                            when ((frame["type"]
                                                as? kotlinx.serialization.json.JsonPrimitive)
                                                ?.content) {
                                                "output" -> {
                                                    val chunks = frame["chunks"]
                                                        as? kotlinx.serialization.json.JsonArray
                                                    chunks?.forEach { chunk ->
                                                        val text = ((chunk
                                                            as? kotlinx.serialization.json.JsonObject)
                                                            ?.get("text")
                                                            as? kotlinx.serialization.json.JsonPrimitive)
                                                            ?.content
                                                        if (!text.isNullOrEmpty()) {
                                                            runtimeUi.jobOutputText = (runtimeUi.jobOutputText + text)
                                                                .takeLast(60000)
                                                        }
                                                    }
                                                }
                                                "status" -> {
                                                    val settled = frame["job"]
                                                        as? kotlinx.serialization.json.JsonObject
                                                    val status = (settled?.get("status")
                                                        as? kotlinx.serialization.json.JsonPrimitive)
                                                        ?.content.orEmpty()
                                                    val detail = (settled?.get("detail")
                                                        as? kotlinx.serialization.json.JsonPrimitive)
                                                        ?.content
                                                    runtimeUi.jobOutputStatus = buildString {
                                                        append(status)
                                                        if (!detail.isNullOrBlank()) {
                                                            append(" · ")
                                                            append(detail)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                inspectorEnabled = sessionInspectorEnabled,
                                onOpenInspector = { ui.sessionInspectorOpen = true },
                                agentTeam = agentTeam,
                                agentTeamLoading = runtimeUi.agentTeamLoading,
                                teamEnabled = agentTeamEnabled,
                                currentSessionId = ui.selectedSessionId,
                                onOpenMember = { memberSessionId ->
                                    ui.selectedSessionId = memberSessionId
                                    ui.activePage = ActivePage.CHAT
                                    scope.launch {
                                        dshRepository.tasks.refreshAgentTeam(ui.activeHostId, memberSessionId)
                                    }
                                },
                                onMenuClick = { scope.launch { drawerState.open() } },
                            )
                    // conversation.session.header.corner: поверх шапки в правом верхнем углу.
                    com.dsh.plugin.nativehost.OfficialSlot(
                        slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.SESSION_HEADER_CORNER,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 8.dp, end = 12.dp),
                    ) {}
                }
            }
            val chatPage = ui.activePage == ActivePage.CHAT
            val pageHasOwnHeader = chatPage || ui.activePage == ActivePage.SETTINGS || ui.activePage == ActivePage.FILES ||
                ui.activePage == ActivePage.PLUGINS || ui.activePage == ActivePage.AUTOMATION || ui.activePage == ActivePage.TERMINAL
            Scaffold(
                // Scaffold не забирает отступы системных панелей и клавиатуры: чат сам отступает
                // по WindowInsets.ime, остальным страницам ниже добавляется navigationBars —
                // иначе окно с resize и отступ Scaffold подняли бы содержимое дважды.
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                topBar = {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
                        Column {
                        when {
                            chatPage -> Unit

                            !pageHasOwnHeader -> PageBackBar(
                                title = pageTitleOf(ui.activePage),
                                onBack = { ui.activePage = ActivePage.CHAT },
                            )
                        }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .then(
                            if (chatPage) Modifier
                            else Modifier.navigationBarsPadding()
                        )
                ) {
                    // Уведомление об обновлении — часть содержимого, а не шапки: вверху страницы,
                    // прокручивается вместе с ней и не закрывает заголовок.
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (runtimeUi.rootfsUpdateAvailable && !runtimeUi.rootfsUpdateBannerDismissed) {
                            RootfsUpdateBanner(
                                installed = runtimeUi.installedRootfsVersion.orEmpty(),
                                available = runtimeUi.availableRootfsVersion.orEmpty(),
                                onUpdate = { runtimeUi.showRootfsUpdatePrompt = true },
                                onDismiss = { runtimeUi.rootfsUpdateBannerDismissed = true },
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                    when (ui.activePage) {
                        ActivePage.CHAT -> PageContentSlot {
                            ChatRoute(
                                ChatDeps(
                                    ui = ui,
                                    runtimeUi = runtimeUi,
                                    repository = dshRepository,
                                    settingsRepository = settingsRepository,
                                    topBar = chatTopBar,
                                    userName = accountState.profile?.name,
                                    hasApiKey = accountState.hasApiKey,
                                    sessions = sessions,
                                    activeSession = activeSession,
                                    runningSessionIds = runningSessionIds,
                                    messages = messages,
                                    approval = chatApproval,
                                    catalog = catalogInfo,
                                    cachedModelGroups = cachedModels.groups,
                                    availableModels = availableModelNames,
                                    modelIdByName = effectiveModelIdByName,
                                    workspace = activeWorkspace,
                                    workspaceLabel = workspaceLabelOf(runtimeUi.activeWorkspaceName),
                                    workspaceNames = workspaceFolders.map { workspaceLabelOf(it.name) },
                                    sender = chatSender,
                                    runtimeManager = runtimeManager,
                                    voice = voice,
                                    voiceEnabled = voiceEnabled,
                                    translateReasoning = cotTranslationEnabled,
                                    attachments = chatAttachments,
                                    onPickAttachments = { attachmentPickerLauncher.launch(arrayOf("*/*")) },
                                    onPickPhotos = {
                                        photoPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                    onTakePhoto = if (hasCamera) ({
                                        cameraDir.mkdirs()
                                        val file = java.io.File(cameraDir, "photo-" + System.currentTimeMillis() + ".jpg")
                                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                        cameraUri = uri
                                        // Камера есть, а приложение камеры отключено — ActivityNotFoundException, раньше падение.
                                        runCatching { cameraLauncher.launch(uri) }.onFailure {
                                            cameraUri = null
                                            android.widget.Toast.makeText(context, context.getString(R.string.camera_unavailable), android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }) else null,
                                    onOpenTerminal = if (terminalSupported) ({ ui.activePage = ActivePage.TERMINAL }) else null,
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                ),
                            )
                        }

                        ActivePage.FILES -> PageContentSlot { workspaceFilesContent() }

                        ActivePage.PLUGINS -> DshPluginsRoute(
                            PluginDeps(
                                bundles = bridgePluginCatalog.bundles,
                                natives = runtimeUi.nativeDiscovered,
                                slotEntries = pluginSlotEntries,
                                settingsPages = pluginSettingsPages,
                                rootfsDir = layout.rootfsDir,
                                installCacheDir = layout.cacheDir,
                                pins = { nativePlugins.trustStore.pins() },
                                loadSettings = { ns -> dshRepository.plugins.pluginSettingsDocument(ns) },
                                saveSettings = { ns, patch, revision -> dshRepository.plugins.updatePluginSettings(ns, patch, revision) },
                                marketIndexes = { dshRepository.plugins.marketIndexes(ui.activeHostId) },
                                marketSearch = { url, query -> dshRepository.plugins.marketSearch(ui.activeHostId, url, query) },
                                toggleBundle = bundleToggle::toggle,
                                install = { spec ->
                                    installPluginWithProgress(
                                        context = context, repository = dshRepository, hostId = ui.activeHostId, pkgName = spec,
                                        installer = appPluginInstaller, onProgress = { _, _ -> },
                                        onManagedPackages = { runtimeUi.managedPluginPackages = it },
                                        onNativeCompanion = { ui.nativeCompanionAvailable = it },
                                    )
                                },
                                uninstallBundle = { name ->
                                    // Вместе с плагином удаляются его парные APK (каждое удаление подтверждает Android).
                                    nativePlugins.uninstallCompanionsOf(name)
                                    if (name in runtimeUi.managedPluginPackages) {
                                        appPluginInstaller.uninstall(name)
                                        runtimeUi.managedPluginPackages = runCatching { appPluginInstaller.managedPackages() }.getOrDefault(runtimeUi.managedPluginPackages)
                                        HostRuntimeHolder.restartHost?.invoke()
                                    } else {
                                        dshRepository.plugins.uninstallPlugin(ui.activeHostId, name)
                                        appPluginInstaller.stripRowMountName(name)
                                    }
                                    dshRepository.plugins.refreshPluginInventory(ui.activeHostId)
                                    dshRepository.plugins.refreshBridgePluginCatalog()
                                    dshRepository.plugins.refreshPluginSettingsPages()
                                },
                                uninstallApk = { pkg -> nativePlugins.requestApkUninstall(pkg) },
                                onNativeChanged = { nativePlugins.rescan() },
                                trustCandidate = nativePlugins.pendingApprovals.firstOrNull(),
                                onTrust = { nativePlugins.trust(it) },
                                onDismissTrust = { nativePlugins.dismiss(it) },
                                onAsk = { text ->
                                    ui.pendingComposerText = text
                                    ui.selectedSessionId = null
                                    ui.activePage = ActivePage.CHAT
                                },
                                onCreateWithAgent = {
                                    ui.pendingAgentPreset = "cordis"
                                    ui.pendingComposerText = context.getString(R.string.plr_create_prefill)
                                    ui.selectedSessionId = null
                                    ui.activePage = ActivePage.CHAT
                                },
                                onOpenSource = { dir -> ui.filesDir = dir; ui.activePage = ActivePage.FILES },
                                onOpenPluginUi = { id -> ui.pluginUiOpen = id },
                                speech = SpeechHooks(
                                    catalog = { dshRepository.speech.speechCatalog(ui.activeHostId) },
                                    configure = { providerId, language -> dshRepository.speech.speechConfigure(ui.activeHostId, providerId, language) },
                                    prepare = { providerId, source -> dshRepository.speech.speechPrepare(ui.activeHostId, providerId, source) },
                                    cancelPrepare = { providerId -> dshRepository.speech.speechCancelPrepare(ui.activeHostId, providerId) },
                                ),
                                onClose = { ui.activePage = ActivePage.CHAT },
                            ),
                        )

                        ActivePage.AUTOMATION -> DshAutomationRoute(
                            projects = workspaceFolders.map { com.dsh.feature.settings.ui.AutoProject(it.name, it.path) }
                                .ifEmpty { listOf(com.dsh.feature.settings.ui.AutoProject(activeWorkspace.name, activeWorkspace.absolutePath)) },
                            models = availableModelNames,
                            hostTasks = HostSchedules.flow?.collectAsState()?.value.orEmpty(),
                            onOpenSession = { sid -> ui.selectedSessionId = sid; ui.activePage = ActivePage.CHAT },
                            onClose = { ui.activePage = ActivePage.CHAT },
                        )

                        ActivePage.TERMINAL -> terminalRoute(true)

                        ActivePage.SETTINGS -> DshSettingsRoute(
                            SettingsDeps(
                                ui = ui, runtimeUi = runtimeUi, repository = dshRepository, settingsRepository = settingsRepository,
                                runtimeManager = runtimeManager, layout = layout, backup = backupAndDiag, oauthManager = oauthManager,
                                officialNamespaces = officialModelNamespaces, modelProviders = modelProviders, workspace = activeWorkspace, availableModels = availableModelNames,
                                remotes = availableHosts,
                                pluginsOn = bridgePluginCatalog.bundles.count { it.enabled } +
                                    runtimeUi.nativePluginItems.count { it.statusLabel == context.getString(R.string.plugin_status_installed) },
                                trustedNative = runtimeUi.nativePluginItems.filter { it.signatureFingerprint != null }.map { item ->
                                    com.dsh.feature.settings.ui.TrustedPluginRow(
                                        id = item.pluginId, name = item.name, letter = item.name.take(1).uppercase(),
                                        hue = (item.pluginId.hashCode().toLong() and 0xffff).toFloat() % 360f,
                                        cert = item.signatureFingerprint.orEmpty().chunked(2).let { c -> if (c.size > 6) c.take(3).joinToString(":") + "…" + c.takeLast(2).joinToString(":") else c.joinToString(":") },
                                        blocked = item.statusLabel == context.getString(R.string.plugin_status_identity_mismatch),
                                    )
                                },
                                openAllFilesSettings = openAllFilesSettings,
                                onWorkspaceChanged = { workspaceChoiceKey++ },
                                onOpenTerminal = { ui.activePage = ActivePage.TERMINAL },
                                onOpenPlugins = { ui.activePage = ActivePage.PLUGINS },
                                onOpenAutomation = { ui.activePage = ActivePage.AUTOMATION },
                                automationsOn = com.dsh.feature.settings.logic.AutomationStore.items.count { it.on },
                                automationModel = com.dsh.feature.settings.logic.AutomationStore.defaultModel,
                                onAutomationModel = { m, r -> com.dsh.feature.settings.logic.AutomationStore.setDefaultModel(m, r) },
                                onClose = { ui.activePage = ActivePage.CHAT },
                            ),
                            status = status,
                            initialPage = ui.settingsInitialSection,
                        )

                    }
                        }
                    }
                }
            }
        }
        }

        // Лист поверхности плагина.
        ui.activeSheetSurface?.let { item ->
            SurfaceSheet(
                item = item,
                hostPort = status.port ?: 8080,
                token = status.token,
                onDismissRequest = { ui.activeSheetSurface = null }
            )
        }

        // Плавающее окно плагина (одно на плагин).
        ui.activeOverlaySurface?.let { item ->
            PluginOverlayContainer(
                item = item,
                hostPort = status.port ?: 8080,
                token = status.token,
                onClose = { ui.activeOverlaySurface = null }
            )
        }

        // Обратная связь: у только что поставленного JS-плагина есть нативный компаньон — подсказка установить его.
        ui.nativeCompanionAvailable?.let { companion ->
            CompanionAvailableCard(
                companion = companion,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                onGoInstall = {
                    companion.storeUrl?.let(openUrl)
                    ui.nativeCompanionAvailable = null
                    ui.activePage = ActivePage.PLUGINS
                },
                onIgnore = { ui.nativeCompanionAvailable = null },
            )
        }
        PluginIslandOverlays()
        // Нативный плагин, ждущий доверия, лист поверх чата не открывает (G-06: только что установленный чужой APK
        // не должен подсовывать «Доверять» на любом экране) — в меню у «Плагинов» метка, лист — в «Плагинах».
        ui.pluginUiOpen?.let { id ->
            PluginUiPage(
                id = id,
                title = bridgePluginCatalog.bundles.firstOrNull { it.name == id }?.title
                    ?: runtimeUi.nativePluginItems.firstOrNull { "apk:" + it.pluginId == id }?.name ?: id,
                slotEntries = pluginSlotEntries,
                registry = dshRepository.plugins.bridge(),
                refresh = dshRepository.plugins.pluginComponentRefreshFlow,
                hostPort = status.port ?: dshApplication.localHostPort,
                hostToken = status.token ?: dshApplication.localHostToken,
                hostPortProvider = { dshApplication.localHostPort.takeIf { it > 0 } ?: (status.port ?: 0) },
                pluginRpc = slotPluginRpc,
                onBack = { ui.pluginUiOpen = null },
            )
        }
        SignInDialogs(
            showSignIn = ui.showSignInDialog,
            onDismissSignIn = { ui.showSignInDialog = false },
            onAddKey = {
                // Ключ сохраняется только там: в Host и Keystore, с проверкой и «Показать».
                ui.showSignInDialog = false
                ui.settingsInitialSection = com.dsh.feature.settings.ui.StPage.Models
                ui.activePage = ActivePage.SETTINGS
            },
            onSignIn = {
                ui.showSignInDialog = false
                hostAccount.signIn()
            },
        )
    
        if (pluginPanesOpen) PluginPanesSheet(rightPanePluginTabs, rightPaneGuideEntries, onDismiss = { pluginPanesOpen = false })
}
    }

}
