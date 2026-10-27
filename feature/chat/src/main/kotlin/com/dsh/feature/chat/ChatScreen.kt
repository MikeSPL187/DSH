package com.dsh.feature.chat

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.positionInParent
import androidx.compose.material.icons.rounded.Code
import kotlinx.serialization.json.put

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.core.model.ComposerStats
import com.dsh.feature.chat.R

data class ReasoningOption(val id: String, val label: String, val description: String? = null)

enum class ReasoningLevel(val labelResId: Int) {
    FAST(R.string.reasoning_fast),
    DEEP_THINK(R.string.reasoning_deep_think),
    MAX_REASONING(R.string.reasoning_max)
}

/**
 * Режим доступа (как выбор в текущей сессии ui-permission-presets).
 *
 * Четыре встроенных: только просмотр / изменения в рабочей папке / полный доступ / автопроверка (эксперимент, метка EXP).
 * Полный доступ и автопроверка включаются через окно риска с подтверждающей галочкой.
 */
enum class PermissionPreset(
    val titleResId: Int,
    val descResId: Int,
    val badgeResId: Int? = null,
    val confirmTitleResId: Int? = null,
    val confirmDescResId: Int? = null,
    val confirmAckResId: Int? = null,
    val confirmEnableResId: Int? = null,
) {
    READ_ONLY(R.string.perm_read_only, R.string.perm_desc_read_only),
    WORKSPACE_WRITE(R.string.perm_workspace_write, R.string.perm_desc_workspace_write),
    FULL_ACCESS(
        R.string.perm_danger_full_access,
        R.string.perm_desc_danger_full_access,
        confirmTitleResId = R.string.perm_confirm_title,
        confirmDescResId = R.string.perm_confirm_desc,
        confirmAckResId = R.string.perm_confirm_acknowledge,
        confirmEnableResId = R.string.perm_confirm_enable,
    ),
    AUTO_REVIEW(
        R.string.perm_auto_review,
        R.string.perm_desc_auto_review,
        badgeResId = R.string.perm_auto_badge,
        confirmTitleResId = R.string.perm_auto_confirm_title,
        confirmDescResId = R.string.perm_auto_confirm_desc,
        confirmAckResId = R.string.perm_auto_confirm_acknowledge,
        confirmEnableResId = R.string.perm_auto_confirm_enable,
    ),
}

/** Название текстовой слэш-команды на языке интерфейса (для метки в поле ввода); не текстовая — null. */
private fun commandLabelRes(token: String): Int? = when (token) {
    "goal" -> R.string.cmd_goal
    "feedback" -> R.string.cmd_feedback
    "compact" -> R.string.cmd_compact
    "export" -> R.string.cmd_export
    else -> null
}

/** Подсказка аргумента текстовой слэш-команды (placeholder после выбора команды). */
private fun commandHintRes(token: String): Int? = when (token) {
    "goal" -> R.string.cmd_goal_hint
    "feedback" -> R.string.cmd_feedback_hint
    "compact" -> R.string.cmd_compact_hint
    "export" -> R.string.cmd_export_hint
    else -> null
}

/**
 * Главный экран чата: ход агента и поле ввода.
 *
 * В карточке ввода сверху текст, ниже строка действий: слева «+» (меню команд),
 * режим доступа, отступ, модель и уровень рассуждений, справа «Отправить/Стоп» —
 * две группы, как tools и trailing у `InputBar`.
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModernChatScreen(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    /** Host запущен, но не принял вход приложения (G-19): полоса «Host не принял вход» с «Перезапустить». */
    hostDenied: Boolean = false,
    onRestartHost: () -> Unit = {},
    /** «Повторить» у сообщения, которое Host не принял (id пузыря). */
    onRetryMessage: (String) -> Unit = {},
    isSending: Boolean = false,
    /** Текст ответа, который печатается сейчас (база при переподключении + живые фрагменты); пусто — нет. */
    streamingText: String = "",
    /** Рассуждение, которое печатается сейчас. */
    streamingReasoning: String = "",
    /** Список прокручен к началу — догрузить более раннюю историю. */
    onLoadOlder: () -> Unit = {},
    /** Слэш-команды Host (`commands/list`), появляются в меню «+». */
    hostCommands: List<com.dsh.core.model.HostCommandInfo> = emptyList(),
    /** Выполнить слэш-команду Host (commands/execute). */
    onHostCommand: (String) -> Unit = {},
    /** Навыки текущей сессии (`skills/list`). */
    skills: List<com.dsh.core.model.SkillInfo> = emptyList(),
    /** Перечитать навыки перед открытием меню «+». */
    onRefreshSkills: () -> Unit = {},
    /** Прочитать навыки напрямую (меню рисует результат сразу, не дожидаясь состояния). */
    loadSkills: suspend () -> List<com.dsh.core.model.SkillInfo> = { emptyList() },
    /** План агента (проекция `todos`). */
    todos: List<com.dsh.core.model.TodoEntryInfo> = emptyList(),
    /** Цель сессии (проекция `goal`). */
    goal: com.dsh.core.model.GoalInfo? = null,
    /** Изменить цель (пауза, продолжить, текст, готово, убрать); для EDIT передаётся новый текст. */
    onGoalAction: suspend (GoalAction, String?) -> Result<Unit> = { _, _ -> Result.failure(IllegalStateException("goal")) },
    /** Кандидаты ссылки «@»: файлы и папки рабочей папки, другие сессии; null — Host недоступен. */
    loadReferences: suspend (query: String, filesOnly: Boolean) -> com.dsh.core.data.ContextRepository.Candidates? = { _, _ -> null },
    /** Файлы — результат хода (`deliverables/presented`). */
    deliverables: List<com.dsh.core.protocol.PresentedFileInfo> = emptyList(),
    /** Изменения файлов за последний ход (workspace/changes). */
    changes: com.dsh.core.protocol.ChangesSummaryInfo? = null,
    /** Очередь сообщений, отправленных во время хода (проекция inbox). */
    queuedMessages: List<com.dsh.core.model.QueuedMessageInfo> = emptyList(),
    /** Действие с сообщением в очереди: kind = edit / remove / steer. */
    onQueueAction: (id: String, kind: String, text: String?) -> Unit = { _, _, _ -> },
    /** Diff изменённого файла (индекс — исходный индекс в summary.files). */
    onLoadChangeDiff: suspend (Int) -> com.dsh.core.protocol.ChangesDiffInfo? = { null },
    /** Открыть результат (страница «Файлы» на его папке). */
    onOpenDeliverable: (com.dsh.core.protocol.PresentedFileInfo) -> Unit = {},
    workspaceName: String = androidx.compose.ui.res.stringResource(com.dsh.feature.chat.R.string.chat_default_workspace),
    /** Вложения из системного выбора файлов; копирует их в рабочую папку вызывающий. */
    attachments: List<ChatAttachment> = emptyList(),
    onRemoveAttachment: (ChatAttachment) -> Unit = {},
    /** «Файл»: открыть системный выбор файлов (картинки и любые файлы). */
    onPickAttachments: () -> Unit = {},
    /** Режим плана сессии; от него зависят placeholder и подпись отправки. */
    planMode: Boolean = false,
    onPlanModeChange: (Boolean) -> Unit = {},
    /** Рабочие папки для переключения (из списка Host). */
    workspaceNames: List<String> = emptyList(),
    onWorkspaceSelected: (String) -> Unit = {},
    onAddWorkspace: () -> Unit = {},
    onWorkspaceClick: () -> Unit = {},
    onAnswerUserQuestion: (callId: String, questionId: String, selected: String) -> Unit = { _, _, _ -> },
    selectedModel: String = "",
    /** id текущей модели (например, deepseek-account/deepseek-flash, kimi-for-coding) — по нему место дока определяет провайдера. */
    selectedModelId: String = "",
    /** Нативное место просит сменить модель или уровень (например, кнопка fast-mode плагина). */
    onNativeModelSelect: suspend (provider: String, model: String, reasoningEffort: String?) -> Result<Unit> =
        { _, _, _ -> Result.failure(IllegalStateException("Model switching is not supported")) },
    availableModels: List<String> = emptyList(),
    modelGroups: List<com.dsh.core.model.ModelProviderGroup> = emptyList(),
    onModelSelected: (provider: String, modelId: String, name: String) -> Unit = { _, _, _ -> },
    selectedPermission: PermissionPreset = PermissionPreset.WORKSPACE_WRITE,
    agentPresetLabel: String = androidx.compose.ui.res.stringResource(com.dsh.feature.chat.R.string.chat_preset_standard_label),
    /** id выбранного пресета (standard/cordis) — для галочки в меню. */
    agentPresetSelectedId: String = "standard",
    onAgentPresetSelected: (String) -> Unit = {},
    onPermissionSelected: (PermissionPreset) -> Unit = {},
    reasoningLevel: ReasoningLevel = ReasoningLevel.FAST,
    reasoningOptions: List<ReasoningOption> = emptyList(),
    selectedReasoningId: String = "",
    effortLabelOverride: String? = null,
    onReasoningLevelChange: (ReasoningLevel) -> Unit = {},
    onReasoningSelected: (ReasoningOption) -> Unit = {},
    stats: ComposerStats = ComposerStats(),
    // Голосовой ввод (плагин записи): кнопка микрофона и полоса записи.
    // Полоса модов Claude Code (conversation.input.dock).

    /** Перевод рассуждений (плагин cot-translation); null — выключен. */


    translateReasoning: (suspend (String) -> Result<String>)? = null,


    bandSnapshot: com.dsh.core.data.ClaudeCodeBandSnapshot? = null,

    bandPressing: Boolean = false,

    onBandPress: (Long, String) -> Unit = { _, _ -> },

    voiceEnabled: Boolean = false,
    voiceRecording: Boolean = false,
    voiceSeconds: Int = 0,
    voiceLevel: Float = 0f,
    voiceBusy: Boolean = false,
    voiceError: String? = null,
    voiceTranscript: String? = null,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onVoiceTranscriptConsumed: () -> Unit = {},
    isThinking: Boolean = false,
    onStopClick: () -> Unit = {},
    /** Шапка `.topbar`; получает «лента прокручена» для стеклянного фона. */
    topBar: (@Composable (scrolled: Boolean) -> Unit)? = null,
    /** Имя из профиля аккаунта для приветствия. */
    userName: String? = null,
    /** Сессия, где агент сейчас работает, — карточка на главном. */
    resume: ChatResume? = null,
    onResume: (ChatResume) -> Unit = {},
    /** Число изменённых файлов на кнопке «Файлы» дока. */
    filesBadge: Int = 0,
    onOpenFiles: () -> Unit = {},
    /** «Терминал» в доке; null — терминал выключен. */
    onOpenTerminal: (() -> Unit)? = null,
    /** Запрос агента на действие: правка — карточкой в ходе работы, удаление — экраном и панелью решения. */
    approval: ChatApproval? = null,
    /** Название сессии — подпись экрана «Запрос агента». */
    sessionTitle: String? = null,
    /** Лист «+»: камера (null — нет), фото из системного выбора. */
    onTakePhoto: (() -> Unit)? = null,
    onPickPhotos: () -> Unit = {},
    /** Последняя команда терминала с ошибкой — строка «Вывод терминала» в листе «+». */
    terminalContext: ChatTerminalContext? = null,
    /** Есть ли интернет; null — узнать у Android. */
    online: Boolean? = null,
    /** Открыть лист сразу: «plus», «model», «permission» (снимки экранов). */
    initialSheet: String? = null,
    /** Модель по умолчанию из каталога Host — метка в листе «Модель». */
    defaultModelId: String? = null,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    // Распознанный текст — в черновик: в веб-версии вставка идёт в позицию курсора до записи;
    // здесь черновик — одна строка, текст добавляется в конец, его можно поправить перед отправкой.
    LaunchedEffect(voiceTranscript) {
        val transcript = voiceTranscript?.trim()
        if (!transcript.isNullOrEmpty()) {
            val base = inputText.trimEnd()
            inputText = when {
                base.isBlank() -> transcript
                base.endsWith(transcript) -> base
                else -> "$base $transcript"
            }
        }
        if (voiceTranscript != null) onVoiceTranscriptConsumed()
    }
    var currentPermission by remember(selectedPermission) { mutableStateOf(selectedPermission) }
    var commandSheetOpen by remember { mutableStateOf(initialSheet == "plus") }
    var trajectoryOpen by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val localContext = LocalContext.current
    // Навыки читаются при каждом открытии меню: .dsh/skills рабочей папки могли только что поменяться.
    val sheetSkills = remember { mutableStateOf(skills) }
    val sheetScope = rememberCoroutineScope()
    LaunchedEffect(skills) {
        if (skills.isNotEmpty()) sheetSkills.value = skills
    }
    // Слэш-команда из меню «+»: в поле — метка на языке интерфейса (например, /цель),
    // placeholder — подсказка аргумента, при отправке — снова понятная Host /goal.
    var activeCommandToken by remember { mutableStateOf<String?>(null) }
    var toolDetail by remember { mutableStateOf<ToolDetailData?>(null) }
    var changeDiffIndex by remember { mutableStateOf<Int?>(null) }
    var changeDiff by remember { mutableStateOf<com.dsh.core.protocol.ChangesDiffInfo?>(null) }
    var changeDiffLoading by remember { mutableStateOf(false) }
    LaunchedEffect(changeDiffIndex) {
        val index = changeDiffIndex ?: return@LaunchedEffect
        changeDiff = null
        changeDiffLoading = true
        changeDiff = onLoadChangeDiff(index)
        changeDiffLoading = false
    }
    var permissionMenuOpen by remember { mutableStateOf(initialSheet == "permission") }
    var modelMenuOpen by remember { mutableStateOf(initialSheet == "model") }
    var agentPresetMenuOpen by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<PermissionPreset?>(null) }

    val services = LocalChatServices.current
    val feedbackFlow = services.feedback
    val feedback by (feedbackFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, com.dsh.core.model.MessageFeedbackInfo>()) }).collectAsState()
    val messageImages by services.images.collectAsState()
    val workflowRuns by services.workflowRuns.collectAsState(initial = emptyList())
    // Оценки перечитываются, когда в сессии появились новые сообщения.
    LaunchedEffect(messages.size, messages.firstOrNull()?.sessionId) {
        if (messages.isNotEmpty()) services.refreshFeedback()
    }
    val listState = rememberLazyListState()
    val collapsedTurns = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val chatRows = remember(messages, isThinking) { buildChatRows(messages, isThinking) }
    // Решения по запросам агента: callId → (разрешено, удаление) — строка итога под шагом.
    val decided = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Boolean, Boolean>>() }
    var dangerOpen by remember { mutableStateOf(false) }
    var dangerInfo by remember { mutableStateOf<DangerInfo?>(null) }
    val pendingApproval = approval?.takeIf { a -> a.callId == null || a.callId !in decided }
    val destructive = pendingApproval?.tier == ApprovalTier.DESTRUCTIVE
    LaunchedEffect(pendingApproval?.eventId) {
        dangerInfo = null
        val a = pendingApproval ?: run { dangerOpen = false; return@LaunchedEffect }
        if (a.tier != ApprovalTier.DESTRUCTIVE) return@LaunchedEffect
        a.inspect?.let { inspect -> dangerInfo = runCatching { inspect(deletionTargets(a.toolName, a.args)) }.getOrNull() }
        // Как в прототипе: экран запроса открывается сам, карточка в ленте остаётся.
        kotlinx.coroutines.delay(700)
        dangerOpen = true
    }
    fun decide(a: ChatApproval, allowed: Boolean) {
        a.callId?.let { decided[it] = allowed to (a.tier == ApprovalTier.DESTRUCTIVE) }
        dangerOpen = false
    }
    val runningWorkKey = chatRows.lastOrNull { it is ChatRow.Work && !it.done }?.let { (it as ChatRow.Work).key }
    // Последний ответ агента после последнего хода работы — к нему приставляется итог изменений.
    val finalAnswerId = remember(chatRows) {
        val lastWork = chatRows.indexOfLast { it is ChatRow.Work }
        chatRows.drop(lastWork + 1).filterIsInstance<ChatRow.Single>()
            .lastOrNull { it.message.role == MessageRole.ASSISTANT }?.message?.messageId
            ?.takeIf { lastWork >= 0 && (chatRows[lastWork] as ChatRow.Work).done }
    }
    // Вклады в слоты (fast-mode и др.) меняют модель и уровень сессии через тот же вход.
    com.dsh.plugin.nativehost.NativeHost.modelSelectHandler = onNativeModelSelect

    // chatRows делит рассуждения и инструменты на строки, индексы не совпадают с messages — прокрутка по числу строк.
    LaunchedEffect(chatRows.size, isThinking, approval?.eventId) {
        if (chatRows.isNotEmpty()) {
            // До самого низа: последний элемент может быть выше экрана (ход работы, ответ).
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2)
        }
    }

    // Поле ввода `.composer`: чипы модели и режима, вложения, команда, «+», отправка.
    var composerFocused by remember { mutableStateOf(false) }
    val activeCommandLabel = activeCommandToken?.let { token -> commandLabelRes(token)?.let { stringResource(it) } }
    val activeCommandHint = activeCommandToken?.let { token -> commandHintRes(token)?.let { stringResource(it) } }
    val canSend = inputText.isNotBlank() || attachments.isNotEmpty() || activeCommandToken != null
    // Ссылка «@», которую человек набирает в конце черновика, и список её кандидатов.
    val mention = remember(inputText) { trailingMention(inputText) }
    var mentionUi by remember { mutableStateOf<MentionUi>(MentionUi.Loading) }
    var mentionRetry by remember { mutableStateOf(0) }
    var composerFocusTick by remember { mutableStateOf(0) }
    LaunchedEffect(mention?.query, mention?.quoted, mentionRetry) {
        val q = mention ?: return@LaunchedEffect
        if (mentionUi !is MentionUi.Ready) mentionUi = MentionUi.Loading
        kotlinx.coroutines.delay(120)
        val result = loadReferences(q.query, q.filesOnly)
        mentionUi = if (result == null || result.failed) MentionUi.Failed else MentionUi.Ready(result.items)
    }
    val mentionStyles = com.dsh.core.designsystem.Dsh.colors.let { c ->
        remember(c) {
            MentionTransformation(
                fileStyle = androidx.compose.ui.text.SpanStyle(color = c.accent),
                sessionStyle = androidx.compose.ui.text.SpanStyle(color = c.ok, fontWeight = FontWeight.Medium),
            )
        }
    }
    fun send() {
        if (!canSend) return
        // Вложения уходят как @путь в начале сообщения; картинки — отдельно.
        val attachmentBlock = attachments.filterNot { it.isImageAttachment() }
            .joinToString(" ") { it.asMessageReference() }
            .let { block -> if (block.isBlank()) "" else block + "\n\n" }
        // В поле — локализованная команда (/цель); отправляется команда Host (/goal).
        // Команда — первой: текст, начинающийся с @вложения, Host принял бы за обычный prompt.
        val commandPrefix = activeCommandToken?.let { "/$it " } ?: ""
        val text = commandPrefix + attachmentBlock + inputText.trim()
        inputText = ""
        activeCommandToken = null
        onSendMessage(text)
    }
    val composerBlock: @Composable () -> Unit = {
        if (planMode) PlanModeBar(onExit = { onPlanModeChange(false) })
        ClaudeCodeModsPluginUi.BandView(snapshot = bandSnapshot, pressing = bandPressing, onPress = onBandPress)
        if (voiceRecording) VoiceBar(voiceSeconds, voiceLevel, voiceBusy, onVoiceCancel, onVoiceStop)
        if (voiceEnabled && voiceError != null) {
            Text(voiceError, style = ckBody(12.sp), color = com.dsh.core.designsystem.Dsh.colors.danger, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        com.dsh.plugin.nativehost.OfficialSlot(
            slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_BAR,
            arrangement = com.dsh.plugin.nativehost.SlotArrangement.BLOCK,
        ) {
            ChatComposer(
                text = inputText,
                onText = { inputText = it },
                placeholder = activeCommandHint ?: if (planMode) stringResource(R.string.chat_input_placeholder_plan) else stringResource(R.string.ck_placeholder),
                mode = when {
                    isThinking && !canSend -> SendMode.Stop
                    canSend -> SendMode.Send
                    voiceEnabled && !voiceRecording && !voiceBusy -> SendMode.Mic
                    else -> SendMode.Idle
                },
                onPlus = {
                    // Навыки лежат в .dsh/skills проекта и могли только что измениться.
                    onRefreshSkills()
                    commandSheetOpen = true
                    sheetScope.launch {
                        val loaded = runCatching { loadSkills() }.getOrNull().orEmpty()
                        if (loaded.isNotEmpty()) sheetSkills.value = loaded
                    }
                },
                onSend = {
                    when {
                        isThinking && !canSend -> onStopClick()
                        canSend -> send()
                        voiceEnabled -> onVoiceStart()
                    }
                },
                chipsOpen = (composerFocused || canSend) && mention == null,
                visualTransformation = mentionStyles,
                focusRequest = composerFocusTick,
                onFocus = { composerFocused = it },
                chips = {
                    com.dsh.plugin.nativehost.OfficialSlot(slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_MODEL) {
                        ComposerChip(
                            text = if (availableModels.isEmpty()) selectedModel.ifBlank { stringResource(R.string.chat_select_model) }
                            else listOf(selectedModel, effortLabelOverride ?: stringResource(reasoningLevel.labelResId)).filter { it.isNotBlank() }.joinToString(" · "),
                            icon = com.dsh.core.designsystem.DshGlyphs.Cpu, accent = true, chevron = true,
                            onClick = { modelMenuOpen = true },
                        )
                    }
                    com.dsh.plugin.nativehost.OfficialSlot(slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_PERMISSION) {
                        ComposerChip(stringResource(currentPermission.titleResId), com.dsh.core.designsystem.DshGlyphs.Shield, onClick = { permissionMenuOpen = true })
                    }
                    ComposerChip(agentPresetLabel, com.dsh.core.designsystem.DshGlyphs.Spark, onClick = { agentPresetMenuOpen = true })
                    com.dsh.plugin.nativehost.NativeSlotInline(
                        slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_RIGHT,
                        spec = kotlinx.serialization.json.buildJsonObject {
                            put("modelId", selectedModelId)
                            put("reasoningEffort", selectedReasoningId)
                        },
                    )
                    com.dsh.plugin.nativehost.OfficialSlot(slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_PLAN) {}
                },
                above = {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = mention != null && composerFocused,
                        enter = androidx.compose.animation.expandVertically(com.dsh.core.designsystem.DshMotion.snappy()) + fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically(com.dsh.core.designsystem.DshMotion.snappy()) + fadeOut(),
                    ) {
                        val q = mention
                        if (q != null) Column {
                            MentionPanel(
                                query = q,
                                state = mentionUi,
                                onPick = { inputText = applyMention(inputText, q, it) },
                                onDrill = { dir -> inputText = drillMention(inputText, q, dir) },
                                onRetry = { mentionRetry++ },
                            )
                            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(1.dp).background(com.dsh.core.designsystem.Dsh.colors.line))
                        }
                    }
                    if (activeCommandLabel != null) Row(Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp)) {
                        ComposerChip("/$activeCommandLabel", com.dsh.core.designsystem.DshGlyphs.X, accent = true, onClick = { activeCommandToken = null })
                    }
                    if (attachments.isNotEmpty()) AttachmentRail(attachments, onRemoveAttachment)
                },
                leading = {
                    com.dsh.plugin.nativehost.OfficialSlot(slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_LEFT) {}
                    com.dsh.plugin.nativehost.OfficialSlot(slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.COMPOSER_ATTACHMENTS) {}
                },
            )
        }
    }
    // Раскладка `#main`: лента под шапкой, снизу затемнение, док и «парящее» поле ввода.
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Лента начинается на 112 dp от верха экрана, но не выше шапки (статус-бар + 62 + 8).
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // «Нет сети»: плашка под шапкой, лента сдвигается вниз на её высоту.
    val isOnline = online ?: rememberOnline()
    var net by remember { mutableStateOf<NetState?>(if (isOnline) null else NetState.Offline) }
    var netCheck by remember { mutableStateOf(0) }
    LaunchedEffect(isOnline, netCheck, hostDenied) {
        when {
            hostDenied && isOnline -> net = NetState.HostDenied
            !isOnline && netCheck > 0 -> { net = NetState.Checking; kotlinx.coroutines.delay(900); net = NetState.Offline }
            !isOnline -> net = NetState.Offline
            net != null -> { net = NetState.Back; kotlinx.coroutines.delay(1600); net = null }
        }
    }
    val netShift by androidx.compose.animation.core.animateDpAsState(if (net != null) 60.dp else 0.dp, com.dsh.core.designsystem.DshMotion.smooth(), label = "net")
    val THREAD_TOP = maxOf(112.dp, statusTop + 70.dp) + netShift
    var composerTop by remember { mutableStateOf(0) }
    val threadBottom = with(density) { composerTop.toDp() } + 84.dp
    val homeScroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { homeScroll.value > 8 || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 } }
    var lastScroll by remember { mutableStateOf(0) }
    var dockCompact by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex * 10_000 + listState.firstVisibleItemScrollOffset to listState.canScrollForward }.collect { (y, more) ->
            if (!more) dockCompact = false
            else if (y > lastScroll + 12) dockCompact = true
            else if (y < lastScroll - 12) dockCompact = false
            lastScroll = y
        }
    }
    val closeSheets = { actionMessage = null; trajectoryOpen = false; pendingConfirm = null; modelMenuOpen = false; permissionMenuOpen = false; agentPresetMenuOpen = false; toolDetail = null; changeDiffIndex = null; commandSheetOpen = false }
    val effortOptions = reasoningOptions
    val sheet: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = when {
        modelMenuOpen -> {
            {
                ModelSheetBody(
                    groups = remember(modelGroups) {
                        modelGroups.sortedBy { when (it.id) { "deepseek-account" -> 0; "deepseek-official" -> 1; else -> 2 } }
                    },
                    flatModels = availableModels.distinct(),
                    selectedModel = selectedModel,
                    reasoningOptions = effortOptions,
                    selectedReasoningId = selectedReasoningId,
                    onModel = { provider, id, name -> onModelSelected(provider, id, name) },
                    onReasoning = { onReasoningSelected(it) },
                    defaultModelId = defaultModelId,
                )
            }
        }
        permissionMenuOpen -> {
            {
                PermissionSheetBody(currentPermission) { preset ->
                    permissionMenuOpen = false
                    if (preset.confirmTitleResId != null) pendingConfirm = preset
                    else { currentPermission = preset; onPermissionSelected(preset) }
                }
            }
        }
        toolDetail != null -> {
            val d = toolDetail!!
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { ToolDetailBody(d) }
            body
        }
        changeDiffIndex != null -> {
            val file = changes?.files?.getOrNull(changeDiffIndex!!)
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { ChangeDiffBody(file?.display.orEmpty(), changeDiff, changeDiffLoading) }
            body
        }
        commandSheetOpen -> {
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                val ctx = LocalContext.current
                val noLink = stringResource(R.string.ck_no_link)
                val empty = stringResource(R.string.ck_clipboard_empty)
                fun append(text: String) { inputText = (if (inputText.isBlank()) "" else inputText.trimEnd() + " ") + text; composerFocused = true }
                fun clip(): String? = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                fun command(token: String) {
                    commandSheetOpen = false
                    // «Экспорт» — локально: скачать ZIP сессии.
                    if (token == "export") { services.exportSession(); return }
                    // Текстовые команды — чипом в поле; при отправке уходят как /goal и т. п.
                    if (commandLabelRes(token) != null) activeCommandToken = token else append("/$token ")
                }
                PlusSheetBody(
                    onCamera = onTakePhoto?.let { f -> { commandSheetOpen = false; f() } },
                    onPhotos = { commandSheetOpen = false; onPickPhotos() },
                    onFile = { commandSheetOpen = false; onPickAttachments() },
                    onProject = { commandSheetOpen = false; append("@") },
                    onPaste = {
                        commandSheetOpen = false
                        clip()?.let { append(it) } ?: android.widget.Toast.makeText(ctx, empty, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onLink = {
                        commandSheetOpen = false
                        clip()?.takeIf { android.util.Patterns.WEB_URL.matcher(it).matches() }?.let { append(it) }
                            ?: android.widget.Toast.makeText(ctx, noLink, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    terminal = terminalContext,
                    onReference = {
                        commandSheetOpen = false
                        inputText = (if (inputText.isBlank()) "" else inputText.trimEnd() + " ") + "@"
                        composerFocusTick++
                    },
                    onTerminal = {
                        commandSheetOpen = false
                        terminalContext?.let { t -> append("\n```\n$ ${t.command}\n${t.output.takeLast(3000)}\n```\n") }
                    },
                    planMode = planMode,
                    onPlan = { commandSheetOpen = false; onPlanModeChange(!planMode) },
                    onGoal = { command("goal") },
                    onCompact = { command("compact") },
                    commands = buildList {
                        add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Cpu, stringResource(R.string.cmd_model), stringResource(R.string.cmd_model_desc)) { commandSheetOpen = false; modelMenuOpen = true })
                        add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Shield, stringResource(R.string.cmd_permission), stringResource(R.string.cmd_permission_desc)) { commandSheetOpen = false; permissionMenuOpen = true })
                        add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Msg, stringResource(R.string.cmd_feedback), stringResource(R.string.cmd_feedback_desc)) { command("feedback") })
                        add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Share, stringResource(R.string.cmd_export), stringResource(R.string.cmd_export_desc)) { command("export") })
                        sheetSkills.value.forEach { sk -> add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Spark, "/${sk.name}", sk.description.ifBlank { null }) { commandSheetOpen = false; append("/${sk.name} ") }) }
                        hostCommands.forEach { hc -> add(PlusCommand(com.dsh.core.designsystem.DshGlyphs.Slash, "/${hc.name}", hc.description.ifBlank { null }) { command(hc.name) }) }
                    },
                )
            }
            body
        }
        pendingConfirm != null -> {
            val preset = pendingConfirm!!
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                RiskSheetBody(preset, onCancel = { pendingConfirm = null }) {
                    currentPermission = preset
                    onPermissionSelected(preset)
                    pendingConfirm = null
                }
            }
            body
        }
        actionMessage != null -> {
            val m = actionMessage!!
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                val ctx = LocalContext.current
                val copied = stringResource(R.string.ck_copied)
                MessageActionsBody(
                    message = m,
                    onCopy = { text ->
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                        android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onFork = services::forkAt,
                    onDismiss = { actionMessage = null },
                )
            }
            body
        }
        trajectoryOpen -> {
            val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { TrajectoryBody() }
            body
        }
        agentPresetMenuOpen -> {
            { PresetSheetBody(agentPresetSelectedId) { id -> agentPresetMenuOpen = false; onAgentPresetSelected(id) } }
        }
        else -> null
    }
    com.dsh.core.designsystem.DshSheetScaffold(sheet = sheet, onDismiss = closeSheets, modifier = modifier) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.dsh.core.designsystem.Dsh.colors.bg)
    ) {
        // conversation.view: плагины могут дополнять вид сессии целиком (по умолчанию пусто).
        com.dsh.plugin.nativehost.OfficialSlot(
            slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.CONVERSATION_VIEW,
            arrangement = com.dsh.plugin.nativehost.SlotArrangement.BLOCK,
        ) {}
        if (messages.isEmpty() && !isSending) {
            // Новая сессия: приветствие, работающая сессия и карточки «Начать».
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(homeScroll)
                    .padding(start = 20.dp, end = 20.dp, top = THREAD_TOP, bottom = threadBottom),
            ) {
                ChatHome(
                    name = userName,
                    resume = resume,
                    onResume = onResume,
                    // Карточка подставляет запрос в поле, чтобы его можно было дописать.
                    onCard = { prompt -> inputText = prompt; composerFocused = true },
                )
            }
        } else if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    com.dsh.core.designsystem.DshSpinner(com.dsh.core.designsystem.Dsh.colors.accent, 22.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.chat_creating_session), style = ckBody(13.5.sp, 18.sp), color = com.dsh.core.designsystem.Dsh.colors.t3)
                }
            }
        } else {
            LaunchedEffect(listState.firstVisibleItemIndex, messages.size) {
                if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 0) {
                    onLoadOlder()
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = THREAD_TOP, bottom = threadBottom),
                verticalArrangement = Arrangement.Top
            ) {
                items(
                    items = chatRows,
                    key = { row ->
                        when (row) {
                            is ChatRow.Single -> row.message.messageId
                            is ChatRow.Work -> "work_${row.key}"
                        }
                    }
                ) { row ->
                    when (row) {
                        is ChatRow.Work -> WorkBlock(
                            items = row.items,
                            done = row.done,
                            durationMs = row.durationMs,
                            // Пока агент работает — шаги раскрыты, готово — свёрнуто; нажатие переключает.
                            expanded = collapsedTurns[row.key] ?: !row.done,
                            onToggle = { collapsedTurns[row.key] = !(collapsedTurns[row.key] ?: !row.done) },
                            // Долгое нажатие на шапку — «Ход сессии» (события Host).
                            onLongPress = { trajectoryOpen = true },
                            liveReasoning = if (!row.done) streamingReasoning else "",
                            translate = translateReasoning,
                            onOpenDetail = { toolDetail = it },
                            askContent = { t -> AskCard(t.callId, t.body, onAnswerUserQuestion) },
                            approval = if (row.key == runningWorkKey) pendingApproval else null,
                            dangerInfo = dangerInfo,
                            decided = decided,
                            onDecided = ::decide,
                            onOpenDanger = { dangerOpen = true },
                        )
                        is ChatRow.Single -> {
                            val m = row.message
                            Column(
                                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { actionMessage = m }),
                            ) {
                                when (m.role) {
                                    MessageRole.USER -> {
                                        // Картинки пользователя — над пузырём.
                                        val refs = messageImages[m.messageId]
                                        if (!refs.isNullOrEmpty()) MessageImagesRow(images = refs, modifier = Modifier.padding(start = 16.dp))
                                        UserBubble(
                                            m.content,
                                            Modifier.padding(top = 6.dp, bottom = 22.dp),
                                            onRetry = if (m.failed) { { onRetryMessage(m.messageId) } } else null,
                                        )
                                    }
                                    MessageRole.ASSISTANT -> {
                                        // Итог «Изменения» — перед последним ответом, как в прототипе.
                                        if (m.messageId == finalAnswerId) changes?.let { ch ->
                                            ChangedFilesCard(summary = ch, onOpenDiff = { index -> changeDiffIndex = index })
                                        }
                                        val answer = m.content.substringAfter('\u0001')
                                        if (answer.trimStart().startsWith("⚠️")) {
                                            RunErrorCard(answer.trimStart().removePrefix("⚠️").trim())
                                            Spacer(Modifier.height(18.dp))
                                        } else {
                                            AiAnswer(answer)
                                            // conversation.chat.assistant-actions: действия плагинов под ответом.
                                            com.dsh.plugin.nativehost.NativeSlot(
                                                slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.MESSAGE_ACTION,
                                                spec = kotlinx.serialization.json.buildJsonObject {
                                                    put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                                                    put("content", kotlinx.serialization.json.JsonPrimitive(answer))
                                                },
                                            )
                                            val copied = stringResource(R.string.ck_copied)
                                            AnswerActions(
                                                onCopy = {
                                                    val cm = localContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", answer))
                                                    android.widget.Toast.makeText(localContext, copied, android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onRetry = {
                                                    messages.lastOrNull { it.role == MessageRole.USER && it.createdAt <= m.createdAt }?.let { onSendMessage(it.content) }
                                                },
                                                onShare = {
                                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, answer)
                                                    localContext.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                                },
                                            ) {
                                                // Оценка ответа (плагин message-feedback) — теми же кнопками.
                                                if (feedbackFlow != null) MessageFeedbackActions(
                                                    current = feedback[m.messageId],
                                                    onRating = { rating ->
                                                        val existing = feedback[m.messageId]
                                                        services.rate(m.messageId, if (existing?.rating == rating) null else rating, existing?.note)
                                                    },
                                                    onNote = { note ->
                                                        services.rate(m.messageId, feedback[m.messageId]?.rating ?: "positive", note)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    else -> SystemNote(m.content)
                                }
                            }
                        }
                    }
                }

                if (streamingText.isNotBlank()) {
                    item(key = "streaming_assistant") { AiAnswer(streamingText) }
                }
                // Запрос вне хода работы (агент спросил до первого шага) — отдельной карточкой.
                if (pendingApproval != null && runningWorkKey == null) {
                    item(key = "approval_${pendingApproval.eventId}") {
                        if (destructive) DangerAskCard(pendingApproval, dangerInfo) { dangerOpen = true }
                        else ApproveCard(pendingApproval) { allowed -> decide(pendingApproval, allowed) }
                    }
                }
                // conversation.chat.flow / chat.node: места нативных плагинов в конце ленты
                // (сводка сессии, предложения продолжить и т. п.).
                item(key = "native_slot_chat_stream") {
                    com.dsh.plugin.nativehost.NativeSlot(
                        slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.CHAT_STREAM,
                        spec = kotlinx.serialization.json.JsonObject(emptyMap()),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Карточка изменений идёт перед карточкой результатов (в конце того же хода).
                if (changes != null && changes!!.files.isNotEmpty() && finalAnswerId == null) {
                    item(key = "changed_files") {
                        ChangedFilesCard(
                            summary = changes!!,
                            onOpenDiff = { index ->
                                changeDiffIndex = index
                            },
                        )
                    }
                }
                if (workflowRuns.isNotEmpty()) {
                    item(key = "workflow_runs") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            workflowRuns.forEach { run ->
                                WorkflowRunCard(run = run)
                            }
                        }
                    }
                }
                if (deliverables.isNotEmpty()) {
                    item(key = "presented_files") {
                        PresentedFilesRow(
                            files = deliverables,
                            onOpen = onOpenDeliverable,
                        )
                    }
                }
            }
        }





        ChatFade(Modifier.align(Alignment.BottomCenter))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (queuedMessages.isNotEmpty()) {
                QueuedMessagesDock(
                    messages = queuedMessages,
                    running = isThinking,
                    onEdit = { message, text -> onQueueAction(message.id, "edit", text) },
                    onRemove = { message -> onQueueAction(message.id, "remove", null) },
                    onSteer = { message -> onQueueAction(message.id, "steer", null) },

                )
            }
            if (goal != null || todos.isNotEmpty()) PlanDock(goal, todos, onGoalAction)
            // Док прячется, пока открыта клавиатура (`.dock.away`).
            AnimatedVisibility(
                visible = !composerFocused,
                enter = fadeIn() + androidx.compose.animation.scaleIn(com.dsh.core.designsystem.DshMotion.snappy(), initialScale = 0.94f) + slideInVertically { it / 3 },
                exit = fadeOut() + androidx.compose.animation.scaleOut(com.dsh.core.designsystem.DshMotion.snappy(), targetScale = 0.94f) + slideOutVertically { it / 3 },
            ) {
                ChatDock(
                    filesBadge = filesBadge,
                    compact = dockCompact,
                    onFiles = onOpenFiles,
                    onTerminal = onOpenTerminal,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Box(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .onGloballyPositioned { composerTop = (it.parentLayoutCoordinates?.size?.height ?: 0) - it.positionInParent().y.toInt() },
            ) {
                // Пока агент ждёт решения об удалении, вместо поля ввода — панель решения `.decide`.
                androidx.compose.animation.AnimatedContent(
                    targetState = if (destructive) pendingApproval else null,
                    transitionSpec = {
                        (fadeIn(com.dsh.core.designsystem.DshMotion.lively()) + slideInVertically { it / 4 }) togetherWith
                            (fadeOut(com.dsh.core.designsystem.DshMotion.snappy()) + slideOutVertically { it / 4 })
                    },
                    label = "decide",
                ) { a ->
                    if (a != null) DecidePanel(a, dangerInfo, onDecided = { allowed -> decide(a, allowed) })
                    else Column { composerBlock() }
                }
            }
        }
        topBar?.invoke(scrolled)
        androidx.compose.animation.AnimatedVisibility(
            visible = net != null,
            enter = fadeIn(com.dsh.core.designsystem.DshMotion.lively()) + slideInVertically(com.dsh.core.designsystem.DshMotion.lively()) { -it / 2 },
            exit = fadeOut(com.dsh.core.designsystem.DshMotion.smooth()) + slideOutVertically(com.dsh.core.designsystem.DshMotion.smooth()) { -it / 3 },
            modifier = Modifier.padding(top = statusTop + 64.dp, start = 12.dp, end = 12.dp),
        ) {
            NetBar(net ?: NetState.Back, onRetry = { if (net == NetState.HostDenied) onRestartHost() else netCheck++ })
        }
        // Экран «Запрос агента» поверх чата, въезжает справа.
        androidx.compose.animation.AnimatedVisibility(
            visible = dangerOpen && destructive,
            enter = slideInHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
            exit = slideOutHorizontally(com.dsh.core.designsystem.DshMotion.smooth()) { it },
        ) {
            val a = pendingApproval
            if (a != null) DangerScreen(a, dangerInfo, sessionTitle, onBack = { dangerOpen = false }, onDecided = { allowed -> decide(a, allowed) })
        }
    }
    }
}



/**
 * id пресета → название на языке интерфейса; общее для шапки сессии и поля ввода.
 * @param presetId пресет Host (standard/cordis/minimal/ptc).
 */
@Composable
fun agentPresetLabel(presetId: String): String = stringResource(
    when (presetId) {
        "cordis" -> R.string.preset_creative_title
        "minimal" -> R.string.preset_minimal_title
        "ptc" -> R.string.preset_ptc_title
        else -> R.string.preset_standard_title
    },
)









/** Полоса записи голоса: уровень, время, «Отмена» и «Распознать». */
@Composable
private fun VoiceBar(seconds: Int, level: Float, busy: Boolean, onCancel: () -> Unit, onStop: () -> Unit) {
    val c = com.dsh.core.designsystem.Dsh.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .floatShadow(RoundedCornerShape(22.dp), c.isDark)
            .clip(RoundedCornerShape(22.dp))
            .background(c.glass.compositeOn(c.bg))
            .border(1.dp, c.glassLine, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(8) { index ->
                val on = level >= (index + 1) / 8f
                Box(Modifier.width(3.dp).height(if (on) 16.dp else 8.dp).clip(RoundedCornerShape(2.dp)).background(if (on) c.accent else c.s3))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_recording, seconds), style = ckBody(13.sp), color = c.t2)
        }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onCancel).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.action_cancel), style = ckBody(14.sp), color = c.t2) }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent).clickable(enabled = !busy, onClick = onStop).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (busy) R.string.chat_voice_recognizing else R.string.chat_voice_stop_recognize),
                style = ckBody(14.sp, weight = FontWeight.Medium), color = c.onAccent,
            )
        }
    }
}



/**
 * Капсула выбора в поле ввода (модель / доступ):
 * высота 28dp, R8, отступы 8dp слева / 4dp справа, промежуток 4dp, подпись 13sp/20 вторичным цветом,
 * значки меню и стрелки 14dp, нажатие — заливкой, без ряби Material.
 */






