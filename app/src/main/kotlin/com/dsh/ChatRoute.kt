package com.dsh

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dsh.core.data.DefaultSettingsRepository
import com.dsh.core.data.DshRepository
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.ComposerStats
import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.model.ModelProviderGroup
import com.dsh.core.model.SessionInfo
import com.dsh.feature.chat.ChatApproval
import com.dsh.feature.chat.ChatAttachment
import com.dsh.feature.chat.ChatResume
import com.dsh.feature.chat.ChatTerminalContext
import com.dsh.feature.chat.ModernChatScreen
import com.dsh.feature.chat.PermissionPreset
import com.dsh.feature.chat.ReasoningLevel
import com.dsh.feature.chat.agentPresetLabel
import com.dsh.terminal.TerminalHub
import kotlinx.coroutines.launch
import java.io.File

/** Всё, что странице чата нужно от [MainAppHost]; данные открытой сессии она читает из репозитория сама. */
internal class ChatDeps(
    val ui: AppUiState,
    val runtimeUi: AppRuntimeUiState,
    val repository: DshRepository,
    val settingsRepository: DefaultSettingsRepository,
    val topBar: @Composable (Boolean) -> Unit,
    val userName: String?,
    val hasApiKey: Boolean,
    val sessions: List<SessionInfo>,
    val activeSession: SessionInfo?,
    val runningSessionIds: Set<String>,
    val messages: List<ChatMessage>,
    val approval: ChatApproval?,
    val catalog: ModelCatalogInfo,
    val cachedModelGroups: List<ModelProviderGroup>,
    val availableModels: List<String>,
    val modelIdByName: Map<String, String>,
    val workspace: File,
    val workspaceLabel: String,
    val workspaceNames: List<String>,
    val sender: ChatSender,
    val runtimeManager: com.dsh.runtime.host.HostRuntimeManager,
    val voice: VoiceInput,
    val voiceEnabled: Boolean,
    val translateReasoning: Boolean,
    val attachments: MutableList<ChatAttachment>,
    val onPickAttachments: () -> Unit,
    val onPickPhotos: () -> Unit,
    val onTakePhoto: (() -> Unit)?,
    val onOpenTerminal: (() -> Unit)?,
    val onOpenDrawer: () -> Unit,
)

/** Страница чата: поле ввода, ход агента, выбор модели и доступа, план, изменения, голос. */
@Composable
internal fun ChatRoute(d: ChatDeps) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = d.ui
    val runtimeUi = d.runtimeUi
    val repository = d.repository
    val hostId = ui.activeHostId
    val sessionId = ui.selectedSessionId

    val streamingText by repository.streamingTextFlow.collectAsState()
    val streamingReasoning by repository.streamingReasoningFlow.collectAsState()
    val todos by repository.todosFlow.collectAsState()
    val goal by repository.goalFlow.collectAsState()
    val deliverables by repository.deliverablesFlow.collectAsState()
    val changes by repository.changesFlow.collectAsState()
    val queued by repository.queuedMessagesFlow.collectAsState()
    val hostSessionModels by repository.sessionModelFlow.collectAsState()
    val hostCommands by repository.commands.hostCommandsFlow.collectAsState()
    val hostSkills by repository.commands.hostSkillsFlow.collectAsState()
    val stats by remember(sessionId) { repository.getStatsForSession(sessionId.orEmpty()) }
        .collectAsState(initial = ComposerStats())

    val model = rememberChatModelSelection(
        catalog = d.catalog,
        cachedGroups = d.cachedModelGroups,
        available = d.availableModels,
        idByName = d.modelIdByName,
        sessionModel = d.activeSession?.model ?: ui.currentAppSettings.defaultModel,
        hostSessionModel = sessionId?.let { hostSessionModels[it] },
    )
    val defaultProvider = if (d.hasApiKey) "deepseek-official" else "deepseek-account"
    var reasoningLevel by remember {
        mutableStateOf(
            runCatching { ReasoningLevel.valueOf(ui.currentAppSettings.defaultReasoningLevel) }
                .getOrDefault(ReasoningLevel.FAST),
        )
    }
    val agentPreset = ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView

    /** Действие над открытой сессией; без сессии ничего не делает. */
    fun withSession(action: suspend (String) -> Unit) {
        val sid = ui.selectedSessionId ?: return
        scope.launch { action(sid) }
    }

    fun toast(text: String, long: Boolean = false) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    val send: (String) -> Unit = { text ->
        d.sender.send(text, ModelChoice(model.id, model.providerGroup?.id ?: defaultProvider, model.reasoningId))
    }
    // «Проверить с агентом» и подобные действия отправляют запрос сразу, как в прототипе.
    LaunchedEffect(ui.pendingAutoSend) {
        val text = ui.pendingAutoSend ?: return@LaunchedEffect
        ui.pendingAutoSend = null
        send(text)
    }

    ModernChatScreen(
        topBar = d.topBar,
        userName = d.userName,
        resume = d.sessions
            .filter { it.sessionId in d.runningSessionIds && it.sessionId != sessionId }
            .maxByOrNull { it.updatedAt }
            ?.let { ChatResume(it.sessionId, it.title) },
        onResume = { r -> ui.selectedSessionId = r.sessionId },
        filesBadge = changes?.files?.size ?: 0,
        onOpenFiles = { ui.activePage = ActivePage.FILES },
        onOpenTerminal = d.onOpenTerminal,
        approval = d.approval,
        defaultModelId = d.catalog.defaultModel.ifBlank { null },
        sessionTitle = d.sessions.find { it.sessionId == sessionId }?.title,
        messages = d.messages,
        streamingText = streamingText,
        streamingReasoning = streamingReasoning,
        hostCommands = hostCommands,
        skills = hostSkills,
        loadSkills = { ui.selectedSessionId?.let { repository.commands.listSkills(hostId, it) }.orEmpty() },
        onRefreshSkills = { withSession { runCatching { repository.commands.refreshHostSkills(hostId, it) } } },
        todos = todos,
        goal = goal,
        deliverables = deliverables,
        queuedMessages = queued,
        onQueueAction = { id, kind, text -> withSession { repository.updateQueue(hostId, it, id, kind, text) } },
        changes = changes,
        onLoadChangeDiff = { index -> ui.selectedSessionId?.let { repository.loadChangeDiff(hostId, it, index) } },
        onOpenDeliverable = { file ->
            // Путь Host /workspace/<x> — это <рабочая папка>/<x> на телефоне.
            val dir = workspaceFileOf(file.path, d.workspace)?.parentFile?.takeIf { it.exists() }
            if (dir != null) {
                ui.filesDir = dir
                ui.activePage = ActivePage.FILES
            } else {
                toast(file.path)
            }
        },
        onHostCommand = { line ->
            withSession { sid ->
                val result = repository.commands.executeHostCommand(hostId, sid, line)
                toast(result?.text ?: result?.kind ?: context.getString(R.string.cmd_unknown))
            }
        },
        onLoadOlder = { withSession { repository.loadOlderMessages(hostId, it) } },
        onAnswerUserQuestion = { callId, questionId, selected ->
            withSession {
                // Сбой — сказать: агент ждёт ответа, а вопрос остаётся, чтобы ответить ещё раз.
                if (!repository.answerUserQuestion(hostId, it, callId, questionId, selected)) toast(context.getString(R.string.answer_failed), long = true)
            }
        },
        onSendMessage = send,
        onRetryMessage = d.sender::retry,
        hostDenied = runtimeUi.hostAuthFailed,
        onRestartHost = {
            scope.launch {
                runtimeUi.hostAuthFailed = false
                d.runtimeManager.stop()
                d.runtimeManager.start(d.workspace.absolutePath)
            }
        },
        isSending = runtimeUi.isSending,
        workspaceName = d.workspaceLabel,
        workspaceNames = d.workspaceNames,
        onWorkspaceSelected = { name -> runtimeUi.activeWorkspaceName = name },
        onAddWorkspace = {
            runtimeUi.newWorkspaceName = ""
            runtimeUi.showAddWorkspaceDialog = true
        },
        onWorkspaceClick = d.onOpenDrawer,
        isThinking = sessionId != null && sessionId in d.runningSessionIds,
        onStopClick = {
            withSession {
                // Host не подтвердил остановку — агент продолжает работать: сказать, а не прятать индикатор.
                if (!repository.cancelSession(hostId, it)) toast(context.getString(R.string.stop_failed), long = true)
            }
        },
        selectedModel = model.name,
        selectedModelId = model.id,
        onNativeModelSelect = { provider, modelId, effort ->
            runCatching {
                val sid = ui.selectedSessionId ?: error(context.getString(R.string.no_active_session_fast))
                repository.selectModel(hostId, sid, modelId, provider, effort)
            }
        },
        availableModels = d.availableModels,
        agentPresetLabel = agentPresetLabel(agentPreset),
        agentPresetSelectedId = agentPreset,
        onAgentPresetSelected = { preset ->
            // Новый пресет — для новых сессий; текущая остаётся как есть.
            ui.pendingAgentPreset = preset
            scope.launch { d.settingsRepository.updateSettings { it.copy(transcriptView = preset) } }
        },
        modelGroups = model.groups,
        onModelSelected = { provider, modelId, name ->
            // Провайдер и id — из группы каталога Host, а не по названию (иначе Claude/Kimi
            // записались бы как deepseek-*). У новой сессии id ещё нет: выбор станет моделью
            // по умолчанию и уйдёт с первым сообщением.
            val displayName = name.ifBlank { modelId }
            model.rememberChoice(displayName)
            withSession { sid ->
                repository.selectModel(
                    hostId,
                    sid,
                    modelId.ifBlank { d.modelIdByName[displayName] ?: displayName },
                    provider.ifBlank { defaultProvider },
                    displayName = displayName,
                ).onFailure { error ->
                    toast(context.getString(R.string.model_switch_failed, error.message.orEmpty()), long = true)
                }
            }
        },
        selectedPermission = permissionPresetOf(d.activeSession?.permissionPreset ?: ui.pendingPermissionPreset.takeIf { sessionId == null }),
        onPermissionSelected = { preset ->
            // Сессии ещё нет — права применятся к новой сессии с первым сообщением (G-23).
            if (ui.selectedSessionId == null) ui.pendingPermissionPreset = preset.hostId()
            // Права шире «Только чтения» в проекте с клонированным кодом — пользователь доверяет проекту (G-50).
            if (preset != PermissionPreset.READ_ONLY) {
                (context.applicationContext as? DshApplication)?.runtimeLayout?.let { layout ->
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.dsh.runtime.host.ClonedProjects.trust(layout, runtimeUi.activeWorkspaceName) }
                }
            }
            withSession {
                if (!repository.selectPermissionPreset(hostId, it, preset.hostId())) {
                    toast(context.getString(R.string.perm_switch_failed), long = true)
                }
            }
        },
        reasoningLevel = reasoningLevel,
        reasoningOptions = model.efforts,
        selectedReasoningId = model.reasoningId,
        effortLabelOverride = model.effortLabel,
        onReasoningSelected = { option ->
            model.reasoningId = option.id
            scope.launch {
                d.settingsRepository.updateSettings { it.copy(defaultReasoningLevel = option.id) }
                // Без сессии уровень уйдёт с первым сообщением (ModelChoice.reasoningEffort); отказ Host — тост (G-24).
                ui.selectedSessionId?.let { sid ->
                    repository.selectModel(hostId, sid, model.id, model.providerGroup?.id, option.id).onFailure { error ->
                        toast(context.getString(R.string.model_switch_failed, error.message.orEmpty()), long = true)
                    }
                }
            }
        },
        onReasoningLevelChange = { level ->
            reasoningLevel = level
            scope.launch { d.settingsRepository.updateSettings { it.copy(defaultReasoningLevel = level.name) } }
        },
        stats = stats,
        voiceEnabled = d.voiceEnabled,
        voiceRecording = runtimeUi.voiceRecording,
        voiceSeconds = runtimeUi.voiceSeconds,
        voiceLevel = runtimeUi.voiceLevel,
        voiceBusy = runtimeUi.voiceBusy,
        voiceError = runtimeUi.voiceError,
        voiceTranscript = runtimeUi.voiceTranscript,
        onVoiceStart = d.voice::start,
        onVoiceStop = d.voice::stop,
        onVoiceCancel = d.voice::cancel,
        onVoiceTranscriptConsumed = { runtimeUi.voiceTranscript = null },
        translateReasoning = if (d.translateReasoning) {
            { text -> repository.translation.cotTranslationTranslate(hostId, text, ui.selectedSessionId.orEmpty()) }
        } else null,
        bandSnapshot = runtimeUi.bandSnapshot,
        bandPressing = runtimeUi.bandPressing,
        onBandPress = { generation, actionId ->
            withSession { sid ->
                runtimeUi.bandPressing = true
                repository.pressClaudeCodeBand(hostId, sid, generation, actionId)
                    .onSuccess { snapshot -> if (snapshot != null) runtimeUi.bandSnapshot = snapshot }
                runtimeUi.bandPressing = false
            }
        },
        attachments = d.attachments,
        onRemoveAttachment = { d.attachments.remove(it) },
        onPickAttachments = d.onPickAttachments,
        onPickPhotos = d.onPickPhotos,
        onTakePhoto = d.onTakePhoto,
        terminalContext = (sessionId ?: d.sessions.firstOrNull()?.sessionId)?.let { sid ->
            TerminalHub.lastFailed(hostId, sid)?.let { b -> ChatTerminalContext(b.cmd, b.code, b.out.plain()) }
        },
        planMode = runtimeUi.chatPlanMode,
        onPlanModeChange = { enabled -> runtimeUi.chatPlanMode = enabled },
    )
}

/** Корень рабочих папок внутри Host. */
private const val HOST_WORKSPACE = "/workspace/"

/**
 * Файл телефона для пути из ответа агента: `/workspace/<x>` — это `<рабочая папка>/<x>`.
 * Путь вне рабочей папки (в том числе через `..` или ссылку) — null: агент не открывает другие папки телефона.
 */
internal fun workspaceFileOf(path: String, workspace: File): File? {
    val candidate = when {
        path.startsWith(HOST_WORKSPACE) -> File(workspace, path.removePrefix(HOST_WORKSPACE))
        path.startsWith(workspace.absolutePath + File.separator) -> File(path)
        else -> return null
    }
    val root = workspace.canonicalFile.toPath()
    val real = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
    return real.takeIf { it.toPath().startsWith(root) }
}

private fun permissionPresetOf(hostId: String?): PermissionPreset = when (hostId) {
    "read_only" -> PermissionPreset.READ_ONLY
    "danger_full_access" -> PermissionPreset.FULL_ACCESS
    "auto_review" -> PermissionPreset.AUTO_REVIEW
    else -> PermissionPreset.WORKSPACE_WRITE
}

private fun PermissionPreset.hostId(): String = when (this) {
    PermissionPreset.READ_ONLY -> "read_only"
    PermissionPreset.FULL_ACCESS -> "danger_full_access"
    PermissionPreset.AUTO_REVIEW -> "auto_review"
    else -> "workspace_write"
}
