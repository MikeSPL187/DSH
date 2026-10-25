package com.deepseek.dsh

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.feature.chat.ModernChatScreen
import com.deepseek.dsh.feature.files.FilesScreen
import com.deepseek.dsh.feature.files.WorkspaceManager
import com.deepseek.dsh.plugin.surface.PluginOverlayContainer
import com.deepseek.dsh.plugin.surface.SurfaceCatalogStore
import com.deepseek.dsh.plugin.surface.SurfaceSheet
import com.deepseek.dsh.runtime.host.HostProcessRunner
import com.deepseek.dsh.runtime.host.HostRuntimeHolder
import com.deepseek.dsh.runtime.host.HostRuntimeManager
import com.deepseek.dsh.runtime.host.RuntimeLayout
import com.deepseek.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MainAppHost(
    context: android.content.Context,
    runtimeManager: HostRuntimeManager,
    runner: HostProcessRunner,
    surfaceStore: SurfaceCatalogStore,
    oauthManager: com.deepseek.dsh.core.protocol.DeepSeekOAuthManager,
    workspaceManager: WorkspaceManager,
    layout: RuntimeLayout,
    dshRepository: com.deepseek.dsh.core.data.DshRepository,
    settingsRepository: com.deepseek.dsh.core.data.DefaultSettingsRepository,
    /** 原生伴侣插件重扫信号（onResume 递增）；卸载/安装后回前台即重扫。 */
    nativePluginRescanFlow: kotlinx.coroutines.flow.StateFlow<Int> =
        kotlinx.coroutines.flow.MutableStateFlow(0),
) {
    val onboardingPreferences = remember {
        com.deepseek.dsh.feature.runtime.OnboardingPreferences(context)
    }
    val runtimeUi = rememberAppRuntimeUiState(
        initialOnboarding = !onboardingPreferences.isCompleted,
        initialRootfsProgress = 0f to context.getString(R.string.runtime_checking),
    )

    val drawerUi = remember { AppDrawerState() }
    val drawerState = drawerUi.drawer
    val scope = rememberCoroutineScope()

    // 插件详情页打开时，顶栏返回/系统返回先退回插件列表，而不是直接离开插件页。
    // 「原生插件」tab 的列表：NativePluginDiscovery 扫描系统里已装的伴侣 APK。
    // 进入插件页时扫一次；卸载/安装由 PackageManager 广播驱动重扫（见 onResume）。
    val updateInfo by com.deepseek.dsh.feature.settings.UpdateCenter.update.collectAsState()
    val currentVersion = remember {
        com.deepseek.dsh.feature.settings.UpdateCenter.currentVersion(context)
    }
    LaunchedEffect(Unit) {
        com.deepseek.dsh.feature.settings.UpdateCenter.ensureChecked(context)
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
    // 首次启动已强制授予所有文件访问权限，工作区固定为公有 Documents，无需再迁移/回退。
    val activeWorkspace = remember(workspaceChoiceKey) {
        externalWorkspaceDir.mkdirs()
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

    // UI 一开始就预热 Host，和引导/登录并行，避免用户点击登录时才冷启动。
    LaunchedEffect(Unit) {
        // 首次安装等 rootfs 准备完成后再启动；之后每次重新打开 App 立即启动 Host。
        if (java.io.File(context.filesDir, "rootfs/etc/os-release").exists()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { runtimeManager.warmUp(activeWorkspace.absolutePath) }
            }
        }
    }
    val availableHosts = remember {
        mutableStateListOf<com.deepseek.dsh.core.model.HostDescriptor>(
            com.deepseek.dsh.core.model.HostDescriptor.Local()
        )
    }
    // slot 贡献（composer dock 等）与整页原生页面共用同一个 pluginRpc；在这里
    // 注入给 NativeHost，NativeSlotHost 会把基础 host 升级成 PluginRpcHost。
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
    com.deepseek.dsh.plugin.nativehost.NativeHost.rpcHandler = slotPluginRpc

    val repoSessions by dshRepository.getSessions(ui.activeHostId).collectAsState(initial = emptyList())
    val sessions = repoSessions

    val pendingMessages = remember { androidx.compose.runtime.mutableStateListOf<ChatMessage>() }

    // 计划模式：横幅状态；placeholder 与发送文案在 ChatScreen 内切换。
    // 不按会话 id 重置——首个 prompt 会创建会话并改变 ui.selectedSessionId，
    // 按 id 重置会让刚开启的计划模式在第一次发送后神秘关闭。

    // 系统文件选择器选回的附件（已复制进当前工作区 attachments/）。
    val chatAttachments = remember { androidx.compose.runtime.mutableStateListOf<com.deepseek.dsh.feature.chat.ChatAttachment>() }
    // 切换会话时清空待发附件，避免附件串到另一个会话。
    LaunchedEffect(ui.selectedSessionId) { chatAttachments.clear() }
    val liveSessionIds by dshRepository.liveSessionIdsFlow.collectAsState()
    val runningSessionIds by dshRepository.runningSessionIdsFlow.collectAsState()
    val unreadSessionIds by dshRepository.unreadSessionIdsFlow.collectAsState()
    LaunchedEffect(ui.selectedSessionId) { dshRepository.markSessionViewed(ui.selectedSessionId) }

    LaunchedEffect(sessions, liveSessionIds) {
        // 每次打开 App：自动进入最近更新的会话，而不是停留在空白新会话页。
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
    val messages = remember(repoMessages, pendingMessages.toList()) {
        // Host 回显 user/message 与本地乐观气泡可能同时存在：按内容去重，
        // 避免同一条发送出现两个气泡。
        val echoed = repoMessages.filter { it.role == MessageRole.USER }.takeLast(16)
        repoMessages + pendingMessages.filter { pending ->
            // 同内容且时间接近（60s）才算同一条回显；避免把用户重复发送的
            // 同一条文字误判成回显而吞掉。
            echoed.none { real ->
                real.content.trim() == pending.content.trim() &&
                    kotlin.math.abs(real.createdAt - pending.createdAt) < 60_000
            }
        }
    }


    // 会话完成通知：运行中的会话从 running 变为结束时提醒。
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

    // Host 冷启动需要先拉起 proot/DSH，模型目录返回前用上次缓存让菜单立刻可用。
    val cachedModels = remember { ModelCatalogCacheState(readCachedModelCatalog(context)) }
    LaunchedEffect(catalogInfo.groups) {
        if (catalogInfo.groups.isNotEmpty()) {
            cachedModels.groups = catalogInfo.groups
            writeCachedModelGroups(context, catalogInfo.groups)
        }
    }
    // 模型目录改为事件驱动（对齐官方 model picker）：`$events` 里的
    // llm/adapters-updated、settings/document-updated、credentials/*-updated
    // 会触发 DshRepository 重新拉目录，这里不再轮询。
    LaunchedEffect(hostModels) {
        if (hostModels.isNotEmpty()) {
            cachedModels.names = hostModels.map { it.name }
            cachedModels.ids = hostModels.map { it.id }
            writeCachedModelNames(context, cachedModels.names, cachedModels.ids)
        }
    }

    val currentSessionStats by dshRepository.getStatsForSession(ui.selectedSessionId ?: "").collectAsState(
        initial = com.deepseek.dsh.core.model.ComposerStats()
    )

    // Подписка на каталог: чтение StateFlow.value в composition не перерисовывает UI при изменениях.
    val bridgeCatalogState by dshRepository.plugins.bridgePluginCatalogFlow.collectAsState()
    val voiceEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == VoiceInputPluginUi.bundle && it.enabled
        }
    }
    NotificationPermissionEffect(ui.currentAppSettings.notificationsEnabled)
    val voice = rememberVoiceInput(runtimeUi, dshRepository, { ui.activeHostId }, scope)
    // 思考内容翻译（官方 cot-translation 的 reasoning body）：插件启用时给
    // ReasoningCard 提供「查看译文/查看原文」能力。
    val cotTranslationEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == CotTranslationPluginUi.bundle && it.enabled
        }
    }

    // Claude Code 模组横幅：官方在 conversation.input.dock 画一条 Host 下发的
    // 动态横幅；只有 Claude Code 子智能体插件启用且当前会话存在时订阅。
    val claudeCodeModsEnabled = remember(bridgeCatalogState) {
        bridgeCatalogState.bundles.any {
            it.name == com.deepseek.dsh.feature.chat.ClaudeCodeModsPluginUi.BUNDLE && it.enabled
        }
    }
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId, claudeCodeModsEnabled) {
        runtimeUi.bandSnapshot = null
        val sid = ui.selectedSessionId
        if (claudeCodeModsEnabled && sid != null) {
            dshRepository.claudeCodeBand(ui.activeHostId, sid).collect { snapshot -> runtimeUi.bandSnapshot = snapshot }
        }
    }

    // 会话数据诊断（开发者工具）：只读展示当前会话收到的原始帧。
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
    /**
     * 启停实验性插件后真正让新代码生效：官方要求重启 Host 时自动重启并等到
     * RUNNING，再把 bridge 目录 / 插件清单 / 设置页重新拉一遍，使对应的功能与
     * UI（智能体团队、语音输入、终端、诊断、翻译、插件市场…）立刻出现，而不是
     * 停在「请重启」对话框里。
     */
    suspend fun restartHostForPluginChange(): Boolean {
        val restart = HostRuntimeHolder.restartHost?.invoke() ?: return false
        if (restart.isFailure) return false
        val finished = kotlinx.coroutines.withTimeoutOrNull(90_000) {
            runner.statusFlow.first { it.phase != RuntimePhase.RUNNING }
            runner.statusFlow.first { it.phase == RuntimePhase.RUNNING }
            true
        } ?: false
        runCatching { dshRepository.plugins.refreshBridgePluginCatalog() }
        runCatching { dshRepository.plugins.refreshPluginInventory(ui.activeHostId) }
        runCatching { dshRepository.plugins.refreshPluginSettingsPages() }
        return finished
    }

    /** Host `restartReason` → 官方「请重启 DSH 使更改生效」的具体说明。 */
    fun restartReasonText(reason: String?): String = when (reason) {
        "package-moved" ->
            context.getString(R.string.restart_reason_package_moved)
        "no-hmr" -> context.getString(R.string.restart_reason_no_hmr)
        "host-module" -> context.getString(R.string.restart_reason_host_module)
        "unmanaged-plugin" -> context.getString(R.string.restart_reason_unmanaged_plugin)
        "module-comparison" -> context.getString(R.string.restart_reason_module_comparison)
        else -> context.getString(R.string.restart_reason_generic)
    }
    // 启停插件后 Host 若要求重启或带提示，按官方做法明确告诉用户。
    ui.pluginChangeNotice?.let { lines ->
        val c = com.deepseek.dsh.core.designsystem.Dsh.colors
        val closeNotice = {
            if (!ui.pluginRestartRunning) {
                ui.pluginChangeNotice = null
                ui.pluginRestartError = null
            }
        }
        com.deepseek.dsh.core.designsystem.DshDialog(
            onDismiss = closeNotice,
            title = stringResource(R.string.restart_needed_title),
            closeLabel = stringResource(R.string.action_close),
            description = stringResource(R.string.restart_needed_desc),
            content = {
                lines.forEach { line ->
                    Text(line, style = com.deepseek.dsh.feature.chat.ckBody(14.sp, 20.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp))
                }
                ui.pluginRestartError?.let { message ->
                    Text(message, style = com.deepseek.dsh.feature.chat.ckBody(13.5.sp, 19.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
                }
            },
            footer = {
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.restart_later), primary = false, enabled = !ui.pluginRestartRunning, onClick = closeNotice,
                )
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = if (ui.pluginRestartRunning) stringResource(R.string.restarting) else stringResource(R.string.restart_now),
                    primary = true, enabled = !ui.pluginRestartRunning,
                    onClick = {
                        scope.launch {
                            ui.pluginRestartRunning = true
                            ui.pluginRestartError = null
                            val restart = HostRuntimeHolder.restartHost?.invoke()
                                ?: Result.failure(IllegalStateException(context.getString(R.string.host_not_ready)))
                            ui.pluginRestartRunning = false
                            if (restart.isSuccess) {
                                ui.pluginChangeNotice = null
                            } else {
                                ui.pluginRestartError = restart.exceptionOrNull()?.message ?: context.getString(R.string.restart_failed)
                            }
                        }
                    },
                )
            },
        )
    }
    if (ui.sessionInspectorOpen) {
        SessionInspectorOverlayDialog(
            frames = runtimeUi.rawSessionFrames,
            sessionId = ui.selectedSessionId,
            onDismiss = { ui.sessionInspectorOpen = false },
        )
    }

    fun pressClaudeCodeBand(generation: Long, actionId: String) {
        val sid = ui.selectedSessionId ?: return
        scope.launch {
            runtimeUi.bandPressing = true
            dshRepository.pressClaudeCodeBand(ui.activeHostId, sid, generation, actionId)
                .onSuccess { snapshot -> if (snapshot != null) runtimeUi.bandSnapshot = snapshot }
            runtimeUi.bandPressing = false
        }
    }


    val builtInPluginsList by dshRepository.plugins.builtInPluginsFlow.collectAsState()
    val pluginSnapshot by dshRepository.plugins.pluginSnapshotFlow.collectAsState()
    val pluginManagerSnapshot by dshRepository.plugins.pluginManagerSnapshotFlow.collectAsState()
    val pluginRows by dshRepository.plugins.pluginRowsFlow.collectAsState()
    // 插件页目录来自 bridge/v1：官方 slot 注册优先，Host 原生清单兜底。
    val bridgePluginCatalog by dshRepository.plugins.bridgePluginCatalogFlow.collectAsState()
    val bridgeWarnings by dshRepository.plugins.bridgeWarningsFlow.collectAsState()
    // 插件声明的 UI 入口（settings.section 等 slot 注册）：设置页按落点渲染。
    val pluginSlotEntries by dshRepository.plugins.pluginSlotEntriesFlow.collectAsState()
    val pluginSettingsPages by dshRepository.plugins.pluginSettingsPagesFlow.collectAsState()
    // Host 声明的行级/页面级设置位置：插件页的「内置插件配置」用它把官方设置页
    // 映射到真正的设置命名空间。
    val pluginSlotSurfaces by dshRepository.plugins.pluginSlotSurfacesFlow.collectAsState()
    // 官方/插件归属由后端决定（bridge 的 host-slots 归属），只在需要时读一次。
    val officialModelNamespaces = dshRepository.plugins.officialSettingsNamespaces()

    // 已注册（可路由）的 provider：官方添加目录会排除它们，改由各自卡片配置。

    val automationTasks by dshRepository.tasks.automationTasksFlow.collectAsState()
    val teamTasks by dshRepository.tasks.teamTasksFlow.collectAsState()
    val agentTeam by dshRepository.tasks.agentTeamFlow.collectAsState()
    // 团队动作只在「智能体团队」profile 启用时出现（官方由插件注册 header action）。
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

    // 运行中定期刷新底部统计（tok/s、缓存命中、上下文占用），结束后再刷新一次落定。
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

    // bridge 插件更新的「运行中版本」来源与「重启 Host」动作由 App 层注入：
    // runtime 模块不认识数据层，而重启需要当前工作区路径，只有这里知道。
    // 真正的安装（下载 tag 归档、解压进 rootfs）由 HostRuntimeManager 完成。

    LaunchedEffect(ui.activeHostId) {
        dshRepository.refreshSessions(ui.activeHostId)
        dshRepository.hostSettings.refreshModelCatalog(ui.activeHostId)
        dshRepository.plugins.refreshPluginInventory(ui.activeHostId)
        // bridge 目录与 Host 原生清单并行读取：前者带来官方 slot 与图形，
        // 后者是它不可用时的兜底，两者互不阻塞。
        dshRepository.plugins.refreshBridgePluginCatalog()
        dshRepository.plugins.refreshPluginSettingsPages()
        dshRepository.tasks.refreshAutomationTasks(ui.activeHostId)
        dshRepository.tasks.refreshTeamTasks(ui.activeHostId)
        // 这里不能订阅远程流：Host 还没绑定地址（占位 127.0.0.1:1）时开九个
        // WebSocket 只会全部连接失败。订阅放到 Host 就绪（port 非空）之后。
    }

    val backupAndDiag = remember {
        com.deepseek.dsh.runtime.host.RuntimeBackupAndDiagnostics(layout)
    }
    // 「添加插件」的 App 侧兜底安装器：Host 内的 pnpm 在 proot 下拿不到
    // rename/unlink/mkdtemp（ENOSYS），所以 Host 安装失败后由 App 自己下载、
    // 解压、装依赖并挂到 profile 的 patch 层。
    val appPluginInstaller = remember(layout) {
        com.deepseek.dsh.runtime.host.PluginBundleInstaller(
            context,
            layout,
            workspaceDir = { activeWorkspace },
        )
    }
    // App 安装器挂过的包名：用来区分「随安装自带」的官方插件和用户后来装的。
    LaunchedEffect(appPluginInstaller) {
        runtimeUi.managedPluginPackages = runCatching { appPluginInstaller.managedPackages() }
            .getOrDefault(emptySet())
    }

    /**
     * 记住用户通过 App 打开/关闭过的实验性 bundle。
     *
     * Device 上的 profile 清单在 Host 启动时会被重建为模板（实测：写入的
     * `dsh.profile.bundles` 在下次启动后消失），因此不能只依赖 profile；
     * 这里留一份本地选择，Host 起来后补回去。
     * @param name bundle 包名。
     * @param enabled 打开还是关闭。
     */
    fun rememberBundleSelection(name: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PLUGIN_SELECTION_PREFS, android.content.Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PLUGIN_SELECTION_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) current.add(name) else current.remove(name)
        prefs.edit().putStringSet(PLUGIN_SELECTION_KEY, current).apply()
    }

    /** 用户打开过的实验性 bundle 包名。 */
    fun savedEnabledBundles(): Set<String> =
        context.getSharedPreferences(PLUGIN_SELECTION_PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(PLUGIN_SELECTION_KEY, emptySet())?.toSet() ?: emptySet()

    /**
     * Включить или выключить bundle Host и дождаться ответа; при необходимости Host перезапускается.
     * Переключатель потом берёт фактическое состояние из обновлённого каталога.
     */
    suspend fun toggleBundleAwait(packageName: String, enabled: Boolean): Boolean {
        val result = dshRepository.plugins.setBundleEnabled(ui.activeHostId, packageName, enabled)
        result.fold(
            onSuccess = { outcome ->
                rememberBundleSelection(packageName, enabled)
                if (outcome.restartRequired) {
                    android.widget.Toast.makeText(context, context.getString(R.string.plugin_toggle_restarting), android.widget.Toast.LENGTH_SHORT).show()
                    ui.pluginChangeNotice = if (restartHostForPluginChange()) null
                    else listOf(context.getString(R.string.restart_dsh_hint), restartReasonText(outcome.restartReason))
                } else if (outcome.warnings.isNotEmpty()) {
                    android.widget.Toast.makeText(context, outcome.warnings.first().take(120), android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onFailure = {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.plugin_toggle_failed, context.getString(if (enabled) R.string.plugin_enable else R.string.plugin_disable)),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
        dshRepository.plugins.refreshBridgePluginCatalog()
        dshRepository.plugins.refreshPluginInventory(ui.activeHostId)
        dshRepository.plugins.refreshPluginSettingsPages()
        return result.isSuccess
    }


    // 「有任务时保持后台运行」交给前台服务在划掉最近任务时判断。
    LaunchedEffect(ui.currentAppSettings.keepAliveOnTask) {
        com.deepseek.dsh.runtime.host.HostService.setKeepAliveOnTaskRemoved(
            context,
            ui.currentAppSettings.keepAliveOnTask,
        )
    }
    ToolPolicyEffect(dshRepository)
    val nativePlugins = rememberNativePlugins(runtimeUi, layout, appPluginInstaller, nativePluginRescanFlow, scope)

    val status by runner.statusFlow.collectAsState()

    IdleHostStopEffect(runtimeManager, runner, persistedSettings.idleStopMinutes, runningSessionIds.isNotEmpty(), activeWorkspace)

    // 官方 workflow 工具在手机 profile 默认关闭：用官方 API 打开，保持与桌面一致。
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
    val hostCommands by dshRepository.commands.hostCommandsFlow.collectAsState()
    val hostSkills by dshRepository.commands.hostSkillsFlow.collectAsState()
    val sessionTodos by dshRepository.todosFlow.collectAsState()
    val sessionGoal by dshRepository.goalFlow.collectAsState()
    val sessionDeliverables by dshRepository.deliverablesFlow.collectAsState()
    val sessionChanges by dshRepository.changesFlow.collectAsState()
    val sessionQueuedMessages by dshRepository.queuedMessagesFlow.collectAsState()
    // 终端页状态：屏幕缓冲（去 ANSI）、状态行、可写标志。
    // Host 客户端是否已绑定到真实地址（决定能否开远程流）。
    // 未读奖励提示（官方 account/getUnnotifiedBonuses）；无奖励时什么都不渲染。
    AccountBonusHost(
        repository = dshRepository,
        hostId = ui.activeHostId,
        hostBound = runtimeUi.localHostBound,
    )

    // 斜杠命令目录跟着会话走；commands/change 事件也会触发刷新。
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId) {
        val sid = ui.selectedSessionId
        if (sid != null) {
            runCatching { dshRepository.commands.refreshHostCommands(ui.activeHostId, sid) }
            runCatching { dshRepository.commands.refreshHostSkills(ui.activeHostId, sid) }
        }
    }
    // Настройки Host (тема, пресет доступа, загрузка журналов, расписания) — как только Host доступен,
    // а не после открытия сессии: экран «Настройки» нужен и без неё.
    LaunchedEffect(ui.activeHostId, runtimeUi.localHostBound) {
        if (!runtimeUi.localHostBound && ui.currentAppSettings.remoteHostUrl.isBlank()) return@LaunchedEffect
        HostSchedules.wire(dshRepository, ui.activeHostId, scope)
        com.deepseek.dsh.feature.settings.HostSessionLogUpload.wire(dshRepository, ui.activeHostId, scope)
        com.deepseek.dsh.feature.settings.HostPermissionPreset.wire(dshRepository, ui.activeHostId, scope)
        com.deepseek.dsh.feature.settings.HostTheme.wire(dshRepository, ui.activeHostId, scope)
    }
    // Фоновые задачи и поток открытой сессии (follow: снимок досылает историю и результаты).
    LaunchedEffect(ui.activeHostId, ui.selectedSessionId, runtimeUi.localHostBound) {
        val sid = ui.selectedSessionId
        // Host 未绑定时 baseUrl 还是占位 127.0.0.1:1，开流只会连接失败。
        val bound = runtimeUi.localHostBound || ui.currentAppSettings.remoteHostUrl.isNotBlank()
        if (sid != null && bound) {
            dshRepository.tasks.watchJobs(ui.activeHostId, sid)
            dshRepository.watchSession(ui.activeHostId, sid)
        }
        else dshRepository.tasks.unwatchJobs()
    }
    val streamingText by dshRepository.streamingTextFlow.collectAsState()
    val streamingReasoning by dshRepository.streamingReasoningFlow.collectAsState()
    val hostWorkspaces by dshRepository.hostWorkspacesFlow.collectAsState()
    val hostSessionModels by dshRepository.sessionModelFlow.collectAsState()
    LaunchedEffect(accountState.isSignedIn, accountState.hasApiKey) {
        if (accountState.isSignedIn || accountState.hasApiKey) {
            kotlinx.coroutines.delay(600)
            runtimeUi.modelSettingsRaw = dshRepository.rpcRaw(ui.activeHostId, "settings/describe", "{}").getOrNull()
            runtimeUi.modelProvidersRaw = dshRepository.rpcRaw(ui.activeHostId, "llm/listConfigurableProviders", "{}").getOrNull()
            val providerRefs = com.deepseek.dsh.feature.settings.parseModelProviders(
                runtimeUi.modelProvidersRaw, runtimeUi.modelSettingsRaw, null, officialModelNamespaces
            ).map { it.keyRef }.distinct()
            val refsJson = providerRefs.joinToString(prefix = "{\"refs\":[", postfix = "]}") { "\"$it\"" }
            runtimeUi.modelCredentialsRaw = dshRepository.rpcRaw(ui.activeHostId, "credentials/describe", refsJson).getOrNull()
            dshRepository.hostSettings.refreshModelCatalog(ui.activeHostId)
            dshRepository.plugins.refreshPluginInventory(ui.activeHostId)
        }
    }
    val dshApplication = context.applicationContext as DshApplication

    // Host 目录优先；Host 冷启动期间用上次缓存立即渲染，避免“请选择模型”闪烁。
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

    // 本地运行时启动后：把仓库客户端绑定到实际端口/token，并重新拉取全部 Host 数据。
    LaunchedEffect(status.phase, status.port, status.token) {
        val port = status.port
        if (port != null) {
            if (ui.currentAppSettings.remoteHostUrl.isBlank()) {
                dshApplication.bindLocalHost(port, status.token)
            }
            dshRepository.refreshSessions(ui.activeHostId)
            runtimeUi.modelSettingsRaw = dshRepository.rpcRaw(ui.activeHostId, "settings/describe", "{}").getOrNull()
            runtimeUi.modelProvidersRaw = dshRepository.rpcRaw(ui.activeHostId, "llm/listConfigurableProviders", "{}").getOrNull()
            val providerRefs = com.deepseek.dsh.feature.settings.parseModelProviders(
                runtimeUi.modelProvidersRaw, runtimeUi.modelSettingsRaw, null, officialModelNamespaces
            ).map { it.keyRef }.distinct()
            val refsJson = providerRefs.joinToString(prefix = "{\"refs\":[", postfix = "]}") { "\"$it\"" }
            runtimeUi.modelCredentialsRaw = dshRepository.rpcRaw(ui.activeHostId, "credentials/describe", refsJson).getOrNull()
            dshRepository.hostSettings.refreshModelCatalog(ui.activeHostId)
            dshRepository.plugins.refreshPluginInventory(ui.activeHostId)
            // Host 一就绪就主动读账号，且必须拿到一个明确的 signed-in /
            // signed-out 才标记 hydrated；读失败（Host 还没完全起来）继续重试，
            // 否则首屏会先显示“未登录”，一两秒后才跳成已登录。
            // 与插件目录并行：两者互不依赖，串行只会把启动时间相加。
            val accountHydrationJob = async {
                if (!runtimeUi.accountHydrated) {
                    val deadline = System.currentTimeMillis() + 4_000
                    while (System.currentTimeMillis() < deadline) {
                        val state = runCatching {
                            dshRepository.account.getAccountState(ui.activeHostId).getOrNull()
                        }.getOrNull()
                        if (state != null) {
                            if (state.status == "credential-stored") {
                                val profile = runCatching {
                                    dshRepository.account.getAccountProfile(ui.activeHostId).getOrNull()
                                }.getOrNull()
                                val balance = runCatching {
                                    dshRepository.account.getAccountBalance(ui.activeHostId).getOrNull()
                                }.getOrNull()
                                oauthManager.applyHostAccount(profile, balance)
                            } else if (state.status == "signed-out" && oauthManager.accountFlow.value.isSignedIn) {
                                oauthManager.signOut()
                            }
                            runtimeUi.accountHydrated = true
                            return@async
                        }
                        kotlinx.coroutines.delay(150)
                    }
                }
            }
            // 插件清单、bridge 目录、插件设置页都拿到后才算插件数据就绪。
            dshRepository.plugins.refreshBridgePluginCatalog()
            dshRepository.plugins.refreshPluginSettingsPages()
            accountHydrationJob.await()
            runtimeUi.pluginsHydrated = true
            dshRepository.tasks.refreshAutomationTasks(ui.activeHostId)
            dshRepository.tasks.refreshTeamTasks(ui.activeHostId)
            val baseUrl = dshApplication.defaultClient.currentBaseUrl()
            if (baseUrl.endsWith(":1")) {
                android.util.Log.w("DSH", "Host not bound yet, skip remote streams: $baseUrl")
            } else {
                runtimeUi.localHostBound = true
                dshRepository.subscribeRemoteEvents(ui.activeHostId)
                // App 显示语言同步给 Host：英文界面下 Host 返回的文案也用英文。
                val appLanguage = context.resources.configuration.locales[0].language
                dshRepository.hostSettings.syncHostLocale(ui.activeHostId)
            }
            // 账号态之后由 account/watch 持续推送；首屏已主动拉过一次。
        }
    }

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
            officialModelNamespaces = officialModelNamespaces,
            onboardingPreferences = onboardingPreferences,
            settingsRepository = settingsRepository,
            oauthManager = oauthManager,
            openAllFilesSettings = openAllFilesSettings,
            onWorkspaceChosen = { workspaceChoiceKey++ },
            backup = backupAndDiag,
        )
        return
    }

    // 文件夹名保持固定（用于磁盘路径），界面上按当前语言显示。
    val defaultWorkspaceLabel = stringResource(R.string.workspace_default)
    val currentWorkspace = androidx.compose.runtime.rememberUpdatedState(activeWorkspace)
    val chatSender = remember(dshRepository, runtimeManager) {
        ChatSender(context, scope, dshRepository, ui, runtimeUi, runtimeManager, pendingMessages, chatAttachments) { currentWorkspace.value }
    }
    val activeWorkspaceFolderPath = java.io.File(activeWorkspace, runtimeUi.activeWorkspaceName).absolutePath

    // 真正的系统文件选择器（SAF）：可多选图片/任意文件，复制进当前工作区后
    // 在输入卡片显示；发送时以 @<工作区路径> 附在消息开头。
    fun importAttachments(uris: List<android.net.Uri>) {
        if (uris.isNotEmpty()) {
            val workspaceDir = java.io.File(activeWorkspace, runtimeUi.activeWorkspaceName)
            // 大文件复制放 IO 线程，避免卡住输入法/界面。
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.deepseek.dsh.AttachmentImporter.import(context, workspaceDir, uris)
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
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok -> cameraUri?.takeIf { ok }?.let { importAttachments(listOf(it)) } }
    val hasCamera = remember { context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) }
    // job/follow 输出窗：目标任务、聚合输出、结算状态行。

    // bridge 插件更新后需要两件事：读到「正在运行」的版本（bridge manifest），
    // 以及重启 Host 让新代码加载。两者都只有 App 层能提供，因此注入给 runtime。
    DisposableEffect(ui.activeHostId, activeWorkspace.absolutePath) {
        HostRuntimeHolder.runningBridgeVersionProvider = {
            runCatching { dshRepository.plugins.bridgePluginVersion(forceRefresh = true) }.getOrNull()
        }
        HostRuntimeHolder.restartHost = {
            val stopped = runtimeManager.stop()
            if (stopped.isFailure) {
                stopped
            } else {
                runtimeManager.start(activeWorkspace.absolutePath).map { }
            }
        }
        onDispose {
            HostRuntimeHolder.runningBridgeVersionProvider = null
            HostRuntimeHolder.restartHost = null
        }
    }
    // 插件瞬态消息：Host 排队、客户端轮询取走，用系统 Toast 呈现。
    // 没有事件通道时这段是空转，取不到消息也不会打扰任何东西。
    // profile 清单每次 Host 启动会被重置，这个集合保证每个 App 会话里每个
    // bundle 只补一次，避免反复触发 Host 重载。
    val restoredBundles = remember { mutableSetOf<String>() }
    // 轮询协程只启动一次，用 latest-value 快照读当前会话，避免拿到旧值。
    val currentSessionForTeam by androidx.compose.runtime.rememberUpdatedState(ui.selectedSessionId)
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            kotlinx.coroutines.delay(4_000)
            // Опрос нужен только видимому интерфейсу: в фоне или при остановленном локальном
            // Host он лишь будил радио/CPU каждые 4 секунды и разряжал батарею.
            if (!UserActivity.isForeground) continue
            if (ui.activeHostId == com.deepseek.dsh.core.model.HostDescriptor.Local().id &&
                runner.statusFlow.value.phase != RuntimePhase.RUNNING
            ) continue
            ticks += 1
            // 桥对客户端 bundle 是增量扫描的：每轮只求值一个，结果累积。定时刷新让
            // 后续批次继续推进，扫描完成后不再产生新条目，刷新也就变成一次廉价读。
            if (ticks % 4 == 0) {
                runCatching { dshRepository.plugins.refreshBridgePluginCatalog() }
                // 把用户打开过的实验性 bundle 补回 profile（清单会被启动重置）。
                val catalog = dshRepository.plugins.bridgePluginCatalogFlow.value
                val missing = savedEnabledBundles().filter { name ->
                    name !in restoredBundles && catalog.bundles.none { it.name == name && it.enabled }
                }
                if (missing.isNotEmpty()) {
                    missing.forEach { name -> restoredBundles += name }
                    missing.forEach { name ->
                        runCatching { dshRepository.plugins.setBundleEnabled(ui.activeHostId, name, true) }
                    }
                    runCatching { dshRepository.plugins.refreshBridgePluginCatalog() }
                    runCatching { dshRepository.plugins.refreshPluginInventory(ui.activeHostId) }
                }
            }
            // 插件界面的入口不能只依赖「Host 状态刚变成有端口」那一刻：Host 冷启动时
            // 首个 bridge 请求可能先于会话 cookie 就绪而失败，一次失败就会让设置里的
            // 「插件界面」整组消失。入口为空时用更短的周期重试，稳定后降低频率。
            val entriesEmpty = dshRepository.plugins.pluginSlotEntriesFlow.value.isEmpty()
            val settingsRefreshEvery = if (entriesEmpty) 2 else 8
            if (ticks % settingsRefreshEvery == 0) {
                runCatching { dshRepository.plugins.refreshPluginSettingsPages() }
            }
            // 团队面板的成员状态随工具调用变化，定时刷新投影（只读一次轻量读）。
            if (ticks % 4 == 0) {
                val teamOn = dshRepository.plugins.bridgePluginCatalogFlow.value.bundles.any {
                    it.name == AgentTeamPluginUi.BUNDLE && it.enabled
                }
                if (teamOn) {
                    runCatching { dshRepository.tasks.refreshAgentTeam(ui.activeHostId, currentSessionForTeam) }
                }
            }
            runCatching {
                val events = dshRepository.plugins.pendingPluginEvents()
                // client/refresh 转成组件刷新信号；toast 用系统 Toast 呈现。
                dshRepository.plugins.dispatchPluginEvents(events)
                events.forEach { event ->
                    if (event.kind == "toast" && event.text.isNotBlank()) {
                        android.widget.Toast.makeText(context, event.text, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    val workspaceOps = remember(activeWorkspace, ui.activeHostId) {
        WorkspaceOps(
            root = activeWorkspace,
            host = object : WorkspaceHost {
                override suspend fun create(path: String) { dshRepository.createWorkspace(ui.activeHostId, path) }
                override suspend fun rename(workspaceId: String, title: String) { dshRepository.workspaceRename(ui.activeHostId, workspaceId, title) }
                override suspend fun delete(workspaceId: String) { dshRepository.deleteWorkspace(ui.activeHostId, workspaceId) }
                override suspend fun renameSession(sessionId: String, title: String) { dshRepository.renameSession(ui.activeHostId, sessionId, title) }
            },
            hostIdOf = { path -> dshRepository.hostWorkspacesFlow.value.firstOrNull { it.path == path }?.id },
        )
    }
    WorkspaceDialogs(runtimeUi, workspaceOps, scope)


    // Запрос агента на действие (approval/request): в чате — карточкой в ходе работы или экраном удаления.
    val chatApproval: com.deepseek.dsh.feature.chat.ChatApproval? = pendingApprovalWaterfalls.firstOrNull()?.let { waterfall ->
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
                m.role == com.deepseek.dsh.core.model.MessageRole.TOOL && m.content.split(2.toChar()).getOrNull(2) == callId
            }?.content?.split(2.toChar())?.getOrNull(6)
        }
        val local = ui.activeHostId == com.deepseek.dsh.core.model.HostDescriptor.Local().id
        val workspacePath = sessions.find { it.sessionId == ui.selectedSessionId }?.workspacePath ?: activeWorkspace.path
        com.deepseek.dsh.feature.chat.ChatApproval(
            eventId = waterfall.eventId,
            toolName = toolName,
            reason = localizedReason,
            args = callArgs,
            callId = callId,
            onAllow = { always ->
                // «Разрешать такие действия без вопроса» — то же правило, что в Плагины → инструменты.
                if (always) com.deepseek.dsh.feature.settings.logic.ToolPolicy.set(toolName, "auto")
                scope.launch { dshRepository.approvals.respondApprovalWaterfall(ui.activeHostId, waterfall.eventId, true) }
            },
            onReject = { scope.launch { dshRepository.approvals.respondApprovalWaterfall(ui.activeHostId, waterfall.eventId, false) } },
            inspect = if (local) ({ paths -> inspectDeletion(layout.rootfsDir, workspacePath, paths) }) else null,
        )
    }

    // Агент ждёт решения, а открыт другой экран — плашка внизу ведёт в чат.
    if (chatApproval != null && ui.activePage != ActivePage.CHAT) {
        androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.BottomCenter) {
            val c = com.deepseek.dsh.core.designsystem.Dsh.colors
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
                Text(stringResource(R.string.approval_waiting_banner), color = c.bg, style = com.deepseek.dsh.feature.chat.ckBody(14.sp))
            }
        }
    }

    // 官方 user-questions/request：原生回答弹窗（选项 / 自定义回答）。
    pendingQuestions.firstOrNull()?.let { waterfall ->
        val questions = waterfall.request["questions"]
            as? kotlinx.serialization.json.JsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        UserQuestionDialog(
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

    // job/follow 输出窗。
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
                    com.deepseek.dsh.AttachmentImporter.importInto(context, ui.filesDir, uris)
                }
                runtimeUi.filesRevision += 1
                runtimeUi.workspaceRevision += 1
                if (result.added.isNotEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            com.deepseek.dsh.feature.files.R.string.files_imported,
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



    // 文件面板内容：FILES 页与聊天右侧栏复用同一份列表与回调。
    val projectDir = java.io.File(activeWorkspaceFolderPath)
    val filesGit = remember(activeWorkspace, runtimeUi.activeWorkspaceName) {
        WorkspaceFilesGit(com.deepseek.dsh.runtime.host.WorkspaceGit(layout, activeWorkspace), runtimeUi.activeWorkspaceName)
    }
    // Прикрепить файл проекта к следующему сообщению: путь от корня рабочих папок (@путь).
    fun attachToChat(file: java.io.File) {
        val rel = file.absolutePath.removePrefix(activeWorkspace.absolutePath).trimStart('/').replace('\\', '/')
        if (rel.isNotBlank() && chatAttachments.none { it.workspaceRelativePath == rel }) {
            chatAttachments.add(
                com.deepseek.dsh.feature.chat.ChatAttachment(displayName = file.name, workspaceRelativePath = rel, sizeBytes = file.length(), localPath = file.absolutePath),
            )
        }
    }
    val workspaceFilesContent: @Composable () -> Unit = {
        val askTemplate = stringResource(com.deepseek.dsh.feature.files.R.string.fs_ask_prompt)
        FilesScreen(
            root = projectDir,
            rootLabel = if (runtimeUi.activeWorkspaceName == DEFAULT_WORKSPACE_DIR_NAME) defaultWorkspaceLabel else runtimeUi.activeWorkspaceName,
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
            commitMessage = { info -> com.deepseek.dsh.ai.CommitMessageWriter.write(context, filesGit, info) },
            onShare = { f -> shareWorkspaceFile(context, f) },
            onImport = { dir ->
                ui.filesDir = dir
                filesImportLauncher.launch(arrayOf("*/*"))
            },
            onToast = { text -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() },
        )
    }


    // Терминал: страница (с «Назад») и вкладка боковой панели (без неё) — одна связка с Host.
    // Оболочка открывается в последней сессии, если ни одна не выбрана.
    val terminalDistro = remember(ui.activeHostId) {
        if (ui.activeHostId != com.deepseek.dsh.core.model.HostDescriptor.Local().id) null
        else runCatching {
            java.io.File(layout.rootfsDir, "etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')?.substringBefore(" GNU/Linux")?.substringBefore(" (")
        }.getOrNull()
    }
    val terminalRoute: @Composable (Boolean) -> Unit = { page ->
        com.deepseek.dsh.terminal.DshTerminalRoute(
            repository = dshRepository,
            hostId = ui.activeHostId,
            sessionId = ui.selectedSessionId ?: sessions.firstOrNull()?.sessionId,
            distro = terminalDistro,
            onBack = if (page) ({ ui.activePage = ActivePage.CHAT }) else null,
        )
    }

    // 插件注册到右侧栏的标签：App 只放容器，标签与内容都由插件贡献。
    val rightPanePluginTabs = com.deepseek.dsh.plugin.nativehost.nativeRightPaneTabs()
    val rightPaneGuideEntries = com.deepseek.dsh.plugin.nativehost.nativeGuideEntries()
    var pluginPanesOpen by remember { mutableStateOf(false) }

    val workspaceFolders = remember(
        sessions, ui.selectedSessionId, activeWorkspace, runtimeUi.workspaceRevision, hostWorkspaces,
    ) {
        // 根目录 DSH-Workspace 本身不是工作区，只展示其子文件夹。
        val workspaceDirs = workspaceManager.listWorkspaceDirectories(activeWorkspace)
            .filter { it.absolutePath != activeWorkspace.absolutePath }
        // Host 的 workspace/follow 投影决定顺序与显示名；没有投影时退回本地目录名。
        val hostByPath = hostWorkspaces.associateBy { it.path }
        val orderedDirs = workspaceDirs.sortedBy { dir ->
            hostWorkspaces.indexOfFirst { it.path == dir.absolutePath }
                .let { if (it < 0) Int.MAX_VALUE else it }
        }
        orderedDirs.mapIndexed { idx, dir ->
            val dirSessions = sessions.filter { s ->
                val wsPath = s.workspacePath
                wsPath != null && (wsPath == dir.absolutePath || wsPath.startsWith(dir.absolutePath))
            }.ifEmpty {
                if (idx == 0) sessions else emptyList()
            }
            val hostSessionOrder = hostByPath[dir.absolutePath]?.sessionIds.orEmpty()
            val orderedSessions = dirSessions.sortedBy { s ->
                hostSessionOrder.indexOf(s.sessionId).let { if (it < 0) Int.MAX_VALUE else it }
            }
            com.deepseek.dsh.feature.sessions.WorkspaceFolder(
                id = "ws_${dir.name.hashCode()}",
                name = hostByPath[dir.absolutePath]?.title?.takeIf { it.isNotBlank() }
                    ?: dir.name.ifEmpty { context.getString(R.string.workspace_main) },
                path = dir.absolutePath,
                sessions = orderedSessions.mapIndexed { sIdx, s ->
                    com.deepseek.dsh.feature.sessions.SessionDisplayItem(
                        sessionId = s.sessionId,
                        title = s.title,
                        isRunning = s.sessionId in runningSessionIds,
                        hasUnread = s.sessionId in unreadSessionIds,
                        updatedAt = s.updatedAt,
                        archived = s.archived,
                        pinned = s.pinned,
                        timeAgo = run {
                            val elapsed = (System.currentTimeMillis() - s.updatedAt).coerceAtLeast(0L)
                            when {
                                elapsed < 60_000L -> context.getString(R.string.time_just_now)
                                elapsed < 60L * 60 * 1000 -> context.getString(R.string.time_minutes, (elapsed / 60_000L).toInt())
                                elapsed < 24L * 60 * 60 * 1000 -> context.getString(R.string.time_hours, (elapsed / (60 * 60 * 1000)).toInt())
                                else -> context.getString(R.string.time_days, (elapsed / (24 * 60 * 60 * 1000)).toInt())
                            }
                        },
                        isActiveDot = (s.sessionId == ui.selectedSessionId)
                    )
                },
                isExpanded = idx == 0
            )
        }
    }

    // 终端页跟随官方插件行：Host 的 Loader 里 `@deepseek-ai/dsh-api-terminal-controller`
    // 启用时才出现。这与官方插件页里那一行的开关是同一个事实源，客户端不加设置项。
    val terminalSupported = remember(bridgePluginCatalog) {
        // 官方终端的开关是可选 bundle「持久终端」(@deepseek-ai/dsh-experimental-terminal-
        // bundle)：它默认关闭，用户在插件页打开后才安装/启用终端。之前按
        // terminal-controller 行判断，而该行属于基础 bundle 且默认启用，导致移动端
        // 默认就露出终端页/标签（电脑版没有）。
        bridgePluginCatalog.bundles.any { bundle ->
            bundle.name == "@deepseek-ai/dsh-experimental-terminal-bundle" && bundle.enabled
        }
    }
    LaunchedEffect(terminalSupported, ui.activePage) {
        if (!terminalSupported && ui.activePage == ActivePage.TERMINAL) ui.activePage = ActivePage.CHAT
    }

    val chatServices = remember(dshRepository) { AppChatServices(context, dshRepository, ui, scope) }
    androidx.compose.runtime.CompositionLocalProvider(com.deepseek.dsh.feature.chat.LocalChatServices provides chatServices) {
    Box(modifier = Modifier.fillMaxSize()) {
        com.deepseek.dsh.core.designsystem.DshSheetScaffold(
            sheet = drawerUi.sheet,
            onDismiss = { drawerUi.sheet = null },
        ) {
        com.deepseek.dsh.feature.sessions.DshPushDrawer(
            state = drawerState,
            drawer = {
                AppDrawer(
                    state = drawerUi, ui = ui, runtimeUi = runtimeUi, repository = dshRepository, scope = scope,
                    folders = workspaceFolders, hostWorkspaces = hostWorkspaces, sessions = sessions,
                    waitingIds = pendingApprovalWaterfalls.mapNotNull { it.agentId }.toSet(),
                    previews = sessionPreviews,
                    hostLive = status.phase == RuntimePhase.RUNNING || ui.activeHostId != com.deepseek.dsh.core.model.HostDescriptor.Local().id,
                    defaultWorkspaceLabel = defaultWorkspaceLabel,
                    pluginsOn = bridgePluginCatalog.bundles.count { it.enabled } +
                        runtimeUi.nativePluginItems.count { it.statusLabel == context.getString(R.string.plugin_status_installed) },
                    userName = accountState.profile?.name,
                    updateVersion = updateInfo?.tag?.takeIf { com.deepseek.dsh.feature.settings.UpdateCenter.hasUpdate(context) },
                )
            },
        ) {
            // 对话页使用完整顶栏；设置页自带标题栏（不再叠加）；其余全屏页只保留返回条，
            // 避免出现双 header。
            // Вложенные страницы «Плагинов», «Автоматизации» и «Настроек» закрывает их собственный стек.
            BackHandler(enabled = ui.activePage != ActivePage.CHAT) { ui.activePage = ActivePage.CHAT }
            // Шапка чата живёт внутри экрана чата: под листами она уменьшается вместе с ним.
            val chatTopBar: @Composable (Boolean) -> Unit = { scrolled ->
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
                            ModernTopAppBar(
                                workspace = runtimeUi.activeWorkspaceName,
                                live = status.phase == RuntimePhase.RUNNING || ui.activeHostId != com.deepseek.dsh.core.model.HostDescriptor.Local().id,
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
                                                    .parseToJsonElement(frameJson)
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
                    // conversation.session.header.corner：поверх шапки в правом верхнем углу.
                    com.deepseek.dsh.plugin.nativehost.OfficialSlot(
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
                // 内容区不再让 Scaffold 自动消费系统栏/IME：聊天页自己按
                // WindowInsets.ime 动态留白，其它页在下面补 navigationBars，
                // 避免「窗口已 resize + Scaffold 又加一次 IME 内边距」的双重上移。
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
                    // 更新提示属于页面内容，不属于顶栏：放进内容区顶部，随内容滚动，
                    // 不再压住页面自己的标题栏。
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
                            var reasoningLevel by remember {
                                mutableStateOf(
                                    runCatching {
                                        com.deepseek.dsh.feature.chat.ReasoningLevel.valueOf(ui.currentAppSettings.defaultReasoningLevel)
                                    }.getOrDefault(com.deepseek.dsh.feature.chat.ReasoningLevel.FAST)
                                )
                            }

                            val configuredModel = activeSession?.model ?: ui.currentAppSettings.defaultModel
                            val modelPrefs = remember {
                                context.getSharedPreferences("dsh_model_prefs", android.content.Context.MODE_PRIVATE)
                            }
                            var lastUsedModel by remember {
                                mutableStateOf(modelPrefs.getString("last_model_name", "").orEmpty())
                            }
                            val catalogDefaultName = hostModels
                                .firstOrNull { it.id == catalogInfo.defaultModel }?.name
                            // 官方逻辑：会话的模型来自 Host `modelSelection` 投影（hostSessionModels），
                            // 数据库里的 configuredModel 是 Host 暂时没连上时的兜底；全局 lastUsedModel
                            // 只用于还没有会话的新会话。以前 lastUsedModel 优先，重启后打开旧会话会
                            // 被全局选择覆盖，看起来“没有选中上次用的模型”。
                            fun modelNameOf(value: String?): String? = when {
                                value.isNullOrBlank() -> null
                                value in availableModelNames -> value
                                else -> effectiveModelIdByName.entries
                                    .firstOrNull { it.value == value }?.key
                            }
                            val sessionModelName = modelNameOf(configuredModel)
                            val hostModelName = ui.selectedSessionId?.let { sid ->
                                modelNameOf(hostSessionModels[sid])
                            }
                            val currentModel = when {
                                // Host 目录尚未返回时，先用上次使用的模型占位，避免闪“请选择模型”。
                                availableModelNames.isEmpty() && lastUsedModel.isNotBlank() -> lastUsedModel
                                availableModelNames.isEmpty() -> ""
                                hostModelName != null -> hostModelName
                                sessionModelName != null -> sessionModelName
                                lastUsedModel in availableModelNames -> lastUsedModel
                                // 首次进入：选 Host 目录的默认/最新模型（当前为 DeepSeek-V41-Flash）。
                                else -> catalogDefaultName ?: availableModelNames.first()
                            }
                            LaunchedEffect(currentModel, availableModelNames) {
                                if (currentModel in availableModelNames && currentModel != lastUsedModel) {
                                    lastUsedModel = currentModel
                                    modelPrefs.edit().putString("last_model_name", currentModel).apply()
                                }
                            }
                            LaunchedEffect(hostModelName) {
                                if (!hostModelName.isNullOrBlank() &&
                                    hostModelName != lastUsedModel &&
                                    hostModelName in availableModelNames
                                ) {
                                    lastUsedModel = hostModelName
                                    modelPrefs.edit()
                                        .putString("last_model_name", hostModelName)
                                        .apply()
                                }
                            }
                            // Host 目录还没回来时用上次缓存的目录：推理等级、默认档位
                            // 全部来自 Host 的 modelCatalog，而不是客户端预置文案。
                            val modelGroupsForUi = catalogInfo.groups.ifEmpty { cachedModels.groups }
                            val currentProviderGroup = modelGroupsForUi.firstOrNull { group ->
                                group.models.any { it.name == currentModel }
                            }
                            val currentModelMeta = currentProviderGroup?.models?.firstOrNull { it.name == currentModel }
                            val backendEfforts = currentModelMeta?.reasoningEfforts?.map { effort ->
                                com.deepseek.dsh.feature.chat.ReasoningOption(effort.id, effort.name, effort.description)
                            } ?: emptyList()
                            var selectedReasoningId by remember(currentModel) {
                                mutableStateOf(
                                    currentModelMeta?.defaultReasoningEffort
                                        ?: backendEfforts.firstOrNull()?.id.orEmpty()
                                )
                            }
                            LaunchedEffect(currentModel, currentModelMeta) {
                                if (backendEfforts.none { it.id == selectedReasoningId }) {
                                    selectedReasoningId = currentModelMeta?.defaultReasoningEffort
                                        ?: backendEfforts.firstOrNull()?.id.orEmpty()
                                }
                            }

                            val effortLabelText = backendEfforts
                                .firstOrNull { it.id == selectedReasoningId }?.label
                                .orEmpty()
                            val currentPermission = remember(activeSession?.permissionPreset) {
                                when (activeSession?.permissionPreset) {
                                    "read_only" -> com.deepseek.dsh.feature.chat.PermissionPreset.READ_ONLY
                                    "danger_full_access" -> com.deepseek.dsh.feature.chat.PermissionPreset.FULL_ACCESS
                                    "auto_review" -> com.deepseek.dsh.feature.chat.PermissionPreset.AUTO_REVIEW
                                    else -> com.deepseek.dsh.feature.chat.PermissionPreset.WORKSPACE_WRITE
                                }
                            }

                            // Отправка: ChatSender; модель для новой сессии — выбранная сейчас.
                            val sendChat: (String) -> Unit = { text ->
                                chatSender.send(
                                    text,
                                    ModelChoice(
                                        effectiveModelIdByName[currentModel] ?: currentModel,
                                        currentProviderGroup?.id ?: if (accountState.hasApiKey) "deepseek-official" else "deepseek-account",
                                    ),
                                )
                            }
                            // «Проверить с агентом» и подобные действия отправляют запрос сразу, как в прототипе.
                            LaunchedEffect(ui.pendingAutoSend) {
                                val text = ui.pendingAutoSend ?: return@LaunchedEffect
                                ui.pendingAutoSend = null
                                sendChat(text)
                            }

                            ModernChatScreen(
                                topBar = chatTopBar,
                                userName = accountState.profile?.name,
                                resume = sessions
                                    .filter { it.sessionId in runningSessionIds && it.sessionId != ui.selectedSessionId }
                                    .maxByOrNull { it.updatedAt }
                                    ?.let { com.deepseek.dsh.feature.chat.ChatResume(it.sessionId, it.title) },
                                onResume = { r -> ui.selectedSessionId = r.sessionId },
                                filesBadge = sessionChanges?.files?.size ?: 0,
                                onOpenFiles = { ui.activePage = ActivePage.FILES },
                                onOpenTerminal = if (terminalSupported) ({ ui.activePage = ActivePage.TERMINAL }) else null,
                                approval = chatApproval,
                                defaultModelId = catalogInfo.defaultModel.ifBlank { null },
                                sessionTitle = sessions.find { it.sessionId == ui.selectedSessionId }?.title,
                                messages = messages,
                                streamingText = streamingText,
                                streamingReasoning = streamingReasoning,
                                hostCommands = hostCommands,
                                skills = hostSkills,
                                loadSkills = {
                                    val sid = ui.selectedSessionId
                                    if (sid == null) emptyList()
                                    else dshRepository.commands.listSkills(ui.activeHostId, sid)
                                },
                                onRefreshSkills = {
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            runCatching { dshRepository.commands.refreshHostSkills(ui.activeHostId, sid) }
                                        }
                                    }
                                },
                                todos = sessionTodos,
                                goal = sessionGoal,
                                deliverables = sessionDeliverables,
                                queuedMessages = sessionQueuedMessages,
                                onQueueAction = { id, kind, text ->
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            dshRepository.updateQueue(ui.activeHostId, sid, id, kind, text)
                                        }
                                    }
                                },
                                changes = sessionChanges,
                                onLoadChangeDiff = { index ->
                                    val sid = ui.selectedSessionId
                                    if (sid == null) null
                                    else dshRepository.loadChangeDiff(ui.activeHostId, sid, index)
                                },
                                onOpenDeliverable = { file ->
                                    // Host 路径 /workspace/<x> 对应本机工作区 <activeWorkspace>/<x>。
                                    val hostPrefix = "/workspace/"
                                    val localPath = when {
                                        file.path.startsWith(hostPrefix) -> java.io.File(
                                            activeWorkspace,
                                            file.path.removePrefix(hostPrefix),
                                        ).absolutePath
                                        file.path.startsWith(activeWorkspace.absolutePath) ->
                                            file.path
                                        else -> null
                                    }
                                    val dir = localPath?.let { java.io.File(it).parentFile }
                                    if (dir != null && dir.exists()) {
                                        ui.filesDir = dir
                                        ui.activePage = ActivePage.FILES
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            file.path,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onHostCommand = { line ->
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            val result = dshRepository.commands.executeHostCommand(
                                                ui.activeHostId, sid, line,
                                            )
                                            val message = result?.text
                                                ?: result?.kind
                                                ?: context.getString(R.string.cmd_unknown)
                                            android.widget.Toast.makeText(
                                                context,
                                                message,
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                                onLoadOlder = {
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            dshRepository.loadOlderMessages(ui.activeHostId, sid)
                                        }
                                    }
                                },
                                onAnswerUserQuestion = { callId, questionId, selected ->
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch {
                                            dshRepository.answerUserQuestion(
                                                ui.activeHostId, sid, callId, questionId, selected
                                            )
                                        }
                                    }
                                },
                                onSendMessage = sendChat,
                                isSending = runtimeUi.isSending,
                                workspaceName = if (runtimeUi.activeWorkspaceName == DEFAULT_WORKSPACE_DIR_NAME) {
                                    defaultWorkspaceLabel
                                } else {
                                    runtimeUi.activeWorkspaceName
                                },
                                // 工作区列表来自 Host；选中即切换当前工作区，末行开新建对话框。
                                workspaceNames = workspaceFolders.map { folder ->
                                    if (folder.name == DEFAULT_WORKSPACE_DIR_NAME) defaultWorkspaceLabel else folder.name
                                },
                                onWorkspaceSelected = { name -> runtimeUi.activeWorkspaceName = name },
                                onAddWorkspace = { runtimeUi.newWorkspaceName = ""; runtimeUi.showAddWorkspaceDialog = true },
                                onWorkspaceClick = { scope.launch { drawerState.open() } },
                                isThinking = ui.selectedSessionId != null && ui.selectedSessionId in runningSessionIds,
                                onStopClick = {
                                    val sid = ui.selectedSessionId
                                    if (sid != null) {
                                        scope.launch { dshRepository.cancelSession(ui.activeHostId, sid) }
                                    }
                                },
                                selectedModel = currentModel,
                                selectedModelId = effectiveModelIdByName[currentModel] ?: currentModel,
                                onNativeModelSelect = { provider, model, effort ->
                                    runCatching {
                                        val sid = ui.selectedSessionId
                                            ?: error(context.getString(R.string.no_active_session_fast))
                                        dshRepository.selectModel(
                                            ui.activeHostId,
                                            sid,
                                            model,
                                            provider,
                                            effort,
                                        )
                                    }
                                },
                                availableModels = availableModelNames,
                                agentPresetLabel = com.deepseek.dsh.feature.chat.agentPresetLabel(
                                    ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView
                                ),
                                agentPresetSelectedId = ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView,
                                onAgentPresetSelected = { preset ->
                                    // 立即生效：新会话用新预设；当前会话保持原样。
                                    ui.pendingAgentPreset = preset
                                    scope.launch {
                                        settingsRepository.updateSettings { current ->
                                            current.copy(transcriptView = preset)
                                        }
                                    }
                                },                                modelGroups = modelGroupsForUi,
                                onModelSelected = { provider, modelId, name ->
                                    // provider/模型 id 直接来自 Host 模型目录的分组，
                                    // 不再按名字猜 provider（否则 Claude/Kimi 会被写成 deepseek-*）。
                                    val sid = ui.selectedSessionId
                                    val displayName = name.ifBlank { modelId }
                                    // 新会话还没有 sessionId：先把选择记成默认模型，下一条
                                    // 消息创建会话时会带着它走。
                                    if (displayName in availableModelNames) {
                                        lastUsedModel = displayName
                                        modelPrefs.edit().putString("last_model_name", displayName).apply()
                                    }
                                    if (sid != null) {
                                        scope.launch {
                                            val result = dshRepository.selectModel(
                                                ui.activeHostId,
                                                sid,
                                                modelId.ifBlank {
                                                    effectiveModelIdByName[displayName] ?: displayName
                                                },
                                                provider.ifBlank {
                                                    if (accountState.hasApiKey) {
                                                        "deepseek-official"
                                                    } else {
                                                        "deepseek-account"
                                                    }
                                                },
                                                displayName = displayName,
                                            )
                                            result.onSuccess {
                                                // 立刻更新本地选择，别等 Host 投影回来才换标签。
                                                if (displayName in availableModelNames) {
                                                    lastUsedModel = displayName
                                                    modelPrefs.edit()
                                                        .putString("last_model_name", displayName)
                                                        .apply()
                                                }
                                            }.onFailure { error ->
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.model_switch_failed,
                                                        error.message.orEmpty(),
                                                    ),
                                                    android.widget.Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                selectedPermission = currentPermission,
                                onPermissionSelected = { preset ->
                                    val presetStr = when (preset) {
                                        com.deepseek.dsh.feature.chat.PermissionPreset.READ_ONLY -> "read_only"
                                        com.deepseek.dsh.feature.chat.PermissionPreset.FULL_ACCESS -> "danger_full_access"
                                        com.deepseek.dsh.feature.chat.PermissionPreset.AUTO_REVIEW -> "auto_review"
                                        else -> "workspace_write"
                                    }
                                    ui.selectedSessionId?.let { sid ->
                                        scope.launch {
                                            dshRepository.selectPermissionPreset(ui.activeHostId, sid, presetStr)
                                        }
                                    }
                                },
                                reasoningLevel = reasoningLevel,
                                reasoningOptions = backendEfforts,
                                selectedReasoningId = selectedReasoningId,
                                effortLabelOverride = effortLabelText,
                                onReasoningSelected = { option ->
                                    selectedReasoningId = option.id
                                    scope.launch {
                                        settingsRepository.updateSettings { current ->
                                            current.copy(defaultReasoningLevel = option.id)
                                        }
                                        ui.selectedSessionId?.let { sid ->
                                            dshRepository.selectModel(
                                                ui.activeHostId,
                                                sid,
                                                effectiveModelIdByName[currentModel] ?: currentModel,
                                                currentProviderGroup?.id,
                                                option.id
                                            )
                                        }
                                    }
                                },
                                onReasoningLevelChange = { level ->
                                    reasoningLevel = level
                                    scope.launch {
                                        settingsRepository.updateSettings { it.copy(defaultReasoningLevel = level.name) }
                                    }
                                },
                                stats = currentSessionStats,
                                voiceEnabled = voiceEnabled,
                                voiceRecording = runtimeUi.voiceRecording,
                                voiceSeconds = runtimeUi.voiceSeconds,
                                voiceLevel = runtimeUi.voiceLevel,
                                voiceBusy = runtimeUi.voiceBusy,
                                voiceError = runtimeUi.voiceError,
                                voiceTranscript = runtimeUi.voiceTranscript,
                                onVoiceStart = voice::start,
                                onVoiceStop = voice::stop,
                                onVoiceCancel = voice::cancel,
                                onVoiceTranscriptConsumed = { runtimeUi.voiceTranscript = null },
                                translateReasoning = if (cotTranslationEnabled) {
                                    { text ->
                                        dshRepository.translation.cotTranslationTranslate(
                                            ui.activeHostId,
                                            text,
                                            ui.selectedSessionId ?: "",
                                        )
                                    }
                                } else null,
                                bandSnapshot = runtimeUi.bandSnapshot,
                                bandPressing = runtimeUi.bandPressing,
                                onBandPress = { generation, actionId -> pressClaudeCodeBand(generation, actionId) },
                                attachments = chatAttachments,
                                onRemoveAttachment = { attachment ->
                                    chatAttachments.remove(attachment)
                                },
                                onPickAttachments = {
                                    // SAF 选择器：任意类型文件 + 图片；图片走同一通道。
                                    attachmentPickerLauncher.launch(arrayOf("*/*"))
                                },
                                onPickPhotos = {
                                    photoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onTakePhoto = if (hasCamera) ({
                                    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = java.io.File(dir, "photo-" + System.currentTimeMillis() + ".jpg")
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                    cameraUri = uri
                                    cameraLauncher.launch(uri)
                                }) else null,
                                terminalContext = (ui.selectedSessionId ?: sessions.firstOrNull()?.sessionId)?.let { sid ->
                                    com.deepseek.dsh.terminal.TerminalHub.lastFailed(ui.activeHostId, sid)?.let { b ->
                                        com.deepseek.dsh.feature.chat.ChatTerminalContext(b.cmd, b.code, b.out.plain())
                                    }
                                },
                                planMode = runtimeUi.chatPlanMode,
                                onPlanModeChange = { enabled -> runtimeUi.chatPlanMode = enabled },
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
                                toggleBundle = { packageName, enabled -> toggleBundleAwait(packageName, enabled) },
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
                                loadCredential = { ref -> dshRepository.plugins.credentialStatus(ui.activeHostId, ref) },
                                saveCredential = { ref, value -> dshRepository.plugins.setCredential(ui.activeHostId, ref, value) },
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
                            projects = workspaceFolders.map { com.deepseek.dsh.feature.settings.ui.AutoProject(it.name, it.path) }
                                .ifEmpty { listOf(com.deepseek.dsh.feature.settings.ui.AutoProject(activeWorkspace.name, activeWorkspace.absolutePath)) },
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
                                officialNamespaces = officialModelNamespaces, workspace = activeWorkspace, availableModels = availableModelNames,
                                remotes = availableHosts,
                                pluginsOn = bridgePluginCatalog.bundles.count { it.enabled } +
                                    runtimeUi.nativePluginItems.count { it.statusLabel == context.getString(R.string.plugin_status_installed) },
                                trustedNative = runtimeUi.nativePluginItems.filter { it.signatureFingerprint != null }.map { item ->
                                    com.deepseek.dsh.feature.settings.ui.TrustedPluginRow(
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
                                automationsOn = com.deepseek.dsh.feature.settings.logic.AutomationStore.items.count { it.on },
                                automationModel = com.deepseek.dsh.feature.settings.logic.AutomationStore.defaultModel,
                                onAutomationModel = { m, r -> com.deepseek.dsh.feature.settings.logic.AutomationStore.setDefaultModel(m, r) },
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

        // 插件 Surface Sheet 呈现 (v4.0 §7.5)
        ui.activeSheetSurface?.let { item ->
            SurfaceSheet(
                item = item,
                hostPort = status.port ?: 8080,
                token = status.token,
                onDismissRequest = { ui.activeSheetSurface = null }
            )
        }

        // 插件单例悬浮窗 (v4.0 §7.7)
        ui.activeOverlaySurface?.let { item ->
            PluginOverlayContainer(
                item = item,
                hostPort = status.port ?: 8080,
                token = status.token,
                onClose = { ui.activeOverlaySurface = null }
            )
        }

        // 反向联动：刚装的 JS 插件有原生伴侣时，底部提示可去商店装原生界面。
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
        // Нашёлся нативный плагин, пока пользователь не в «Плагинах», — спрашиваем о доверии поверх экрана.
        // На первом запуске не перебиваем онбординг: спросим, когда он закончится.
        if (ui.activePage != ActivePage.PLUGINS && !runtimeUi.showOnboarding) {
            nativePlugins.pendingApprovals.firstOrNull()?.let { c ->
                com.deepseek.dsh.feature.settings.ui.PluginTrustOverlay(trustOf(context, c), onTrust = { nativePlugins.trust(c) }, onDismiss = { nativePlugins.dismiss(c) })
            }
        }
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
                ui.showSignInDialog = false
                ui.showApiKeyDialog = true
            },
            onSignIn = {
                ui.showSignInDialog = false
                hostAccount.signIn()
            },
            showApiKey = ui.showApiKeyDialog,
            apiKeyInput = ui.apiKeyInput,
            onApiKeyChange = { ui.apiKeyInput = it },
            onDismissApiKey = { ui.showApiKeyDialog = false },
            onSubmitApiKey = {
                oauthManager.signInWithApiKey(ui.apiKeyInput)
                ui.apiKeyInput = ""
                ui.showApiKeyDialog = false
            },
        )
    
        if (pluginPanesOpen) PluginPanesSheet(rightPanePluginTabs, rightPaneGuideEntries, onDismiss = { pluginPanesOpen = false })
}
    }

}
