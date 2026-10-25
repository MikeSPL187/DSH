package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.ComposerStats
import com.deepseek.dsh.core.model.GoalInfo
import com.deepseek.dsh.core.model.MessageFeedbackInfo
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.core.model.QueuedMessageInfo
import com.deepseek.dsh.core.model.SessionInfo
import com.deepseek.dsh.core.model.TodoEntryInfo
import com.deepseek.dsh.core.model.Tr
import com.deepseek.dsh.core.model.WorkflowRunInfo
import com.deepseek.dsh.core.protocol.AttachmentImageRef
import com.deepseek.dsh.core.protocol.ChangesDiffInfo
import com.deepseek.dsh.core.protocol.ChangesSummaryInfo
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.PresentedFileInfo
import com.deepseek.dsh.core.protocol.PromptImagePart
import com.deepseek.dsh.core.protocol.RemoteEvent
import com.deepseek.dsh.core.protocol.RemoteResult
import com.deepseek.dsh.core.protocol.SessionRawFrame
import com.deepseek.dsh.core.protocol.SessionSearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

/**
 * Данные приложения: Host (через [DshClientProtocol]) и локальный кэш Room.
 *
 * Сам репозиторий отвечает за сессии, сообщения, рабочие папки и открытую сессию,
 * а также разводит события Host по областям. Остальное — в областях:
 * [plugins], [account], [tasks], [terminals], [commands], [approvals], [hostSettings],
 * [speech], [translation].
 */
class DshRepository(
    private val dao: DshDao,
    private val client: DshClientProtocol,
    private val scope: CoroutineScope,
) {
    private val calls = HostCalls(client)

    val plugins = PluginsRepository(client, calls)
    val account = AccountRepository(client, calls)
    val tasks = TasksRepository(client, calls, scope)
    val terminals = TerminalRepository(client, calls)
    val commands = CommandsRepository(calls)
    val approvals = ApprovalsRepository(client, scope)
    val hostSettings = HostSettingsRepository(client, calls)
    val speech = SpeechRepository(calls)
    val translation = TranslationRepository(calls)

    /** Сырой вызов Host для настроек, которые разбирает вызывающий (`settings/describe` и т. п.). */
    suspend fun rpcRaw(hostId: String, method: String, argsJson: String): Result<String> =
        when (val res = client.rpcRaw(hostId, method, argsJson)) {
            is RemoteResult.Success -> Result.success(res.data)
            is RemoteResult.Failure -> Result.failure(IllegalStateException("${res.code}: ${res.message}"))
        }

    // ── Сессии ────────────────────────────────────────────────────────────────

    /** Сессии, известные Host (последний `session/list`). */
    val liveSessionIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Сессии, в которых сейчас идёт ход. */
    val runningSessionIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Сессии, где ход закончился, пока пользователь смотрел другую. */
    val unreadSessionIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Модель каждой сессии из проекций `session/control`. */
    private val _sessionModelFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionModelFlow: StateFlow<Map<String, String>> = _sessionModelFlow.asStateFlow()

    private val _sessionStatsFlow = MutableStateFlow<Map<String, ComposerStats>>(emptyMap())

    /** Рабочие папки Host по порядку (`workspace/follow`). */
    private val _hostWorkspacesFlow = MutableStateFlow<List<HostWorkspaceInfo>>(emptyList())
    val hostWorkspacesFlow: StateFlow<List<HostWorkspaceInfo>> = _hostWorkspacesFlow.asStateFlow()
    private val workspaceTree = WorkspaceTree()
    private var workspaceRefreshJob: Job? = null

    fun getSessions(hostId: String): Flow<List<SessionInfo>> =
        dao.getSessionsForHost(hostId).map { entities ->
            entities.map { entity ->
                SessionInfo(
                    hostId = entity.hostId,
                    sessionId = entity.sessionId,
                    title = entity.title ?: Tr.t("Untitled session", "Сессия без названия"),
                    workspacePath = entity.workspacePath,
                    status = entity.status ?: "idle",
                    updatedAt = entity.updatedAt ?: System.currentTimeMillis(),
                    archived = entity.archived != 0,
                    pinned = entity.pinned != 0,
                    model = entity.model,
                    permissionPreset = entity.permissionPreset,
                )
            }
        }

    suspend fun refreshSessions(hostId: String) {
        val sessions = (client.listSessions(hostId) as? RemoteResult.Success)?.data ?: return
        liveSessionIdsFlow.value = sessions.map { it.sessionId }.toSet()
        dao.insertSessions(sessions.map { it.toEntity() })
    }

    suspend fun createSession(
        hostId: String,
        title: String,
        workspacePath: String?,
        agentPreset: String? = null,
    ): Result<SessionInfo> =
        client.createSession(hostId, title, workspacePath, agentPreset).toResult().onSuccess { session ->
            liveSessionIdsFlow.update { it + session.sessionId }
            dao.insertSessions(listOf(session.toEntity(archived = false, pinned = false)))
        }

    /** Переименовать сессию (`session/rename`). */
    suspend fun renameSession(hostId: String, sessionId: String, title: String): Boolean =
        sessionCall(hostId, "session/rename", buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        })

    /**
     * Ветвить сессию (`session/fork`) с последнего полного хода или с [atSeq].
     * @return id новой сессии; null — Host отказал.
     */
    suspend fun forkSession(hostId: String, sessionId: String, atSeq: Int? = null): String? {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                if (atSeq != null) put("atSeq", atSeq)
            })
        }
        val newSessionId = (calls.value(hostId, "session/fork", args) as? JsonObject)?.str("sessionId")
        if (newSessionId != null) refreshSessions(hostId)
        return newSessionId
    }

    /** Закрепить или открепить сессию (`workspace/pinSession` / `unpinSession`). */
    suspend fun setSessionPinned(hostId: String, sessionId: String, pinned: Boolean): Boolean =
        sessionCall(
            hostId,
            if (pinned) "workspace/pinSession" else "workspace/unpinSession",
            buildJsonObject { put("sessionId", sessionId) },
        )

    /** Архивировать сессию или вернуть из архива (`workspace/archiveSession` / `unarchiveSession`). */
    suspend fun setSessionArchived(hostId: String, sessionId: String, archived: Boolean): Boolean {
        val method = if (archived) "workspace/archiveSession" else "workspace/unarchiveSession"
        val ok = calls.rpc(hostId, method, requestOf(buildJsonObject { put("sessionId", sessionId) })).isSuccess
        if (ok) {
            // `session/list` архивных сессий не возвращает — локальную строку надо отметить
            // самим, иначе фильтр «Скрыть архивные» не увидит изменения.
            dao.updateSessionArchived(hostId, sessionId, if (archived) 1 else 0)
            refreshSessions(hostId)
        }
        return ok
    }

    /** Поиск сессий по содержимому (`session/search`). */
    suspend fun searchSessions(hostId: String, query: String): List<SessionSearchHit> =
        (client.searchSessions(hostId, query) as? RemoteResult.Success)?.data.orEmpty()

    suspend fun selectModel(
        hostId: String,
        sessionId: String,
        model: String,
        provider: String? = null,
        reasoningEffort: String? = null,
        displayName: String? = null,
    ): Result<Unit> =
        // Кэш меняется только после согласия Host: при ошибке остаётся прежняя модель.
        client.selectModel(hostId, sessionId, model, provider, reasoningEffort).toResult()
            .map { }
            .onSuccess { dao.updateSessionModel(hostId, sessionId, displayName ?: model) }

    suspend fun selectPermissionPreset(hostId: String, sessionId: String, preset: String) {
        dao.updateSessionPermission(hostId, sessionId, preset)
        client.selectPermissionPreset(hostId, sessionId, preset)
    }

    suspend fun cancelSession(hostId: String, sessionId: String): Boolean {
        runningSessionIdsFlow.update { it - sessionId }
        return client.cancelSession(hostId, sessionId)
    }

    suspend fun answerUserQuestion(hostId: String, sessionId: String, callId: String, questionId: String, selected: String) {
        client.answerUserQuestion(hostId, sessionId, callId, questionId, selected)
    }

    fun getStatsForSession(sessionId: String): Flow<ComposerStats> =
        _sessionStatsFlow.map { it[sessionId] ?: ComposerStats() }

    suspend fun refreshSessionStats(hostId: String, sessionId: String) {
        val stats = (client.getSessionStats(hostId, sessionId) as? RemoteResult.Success)?.data ?: return
        _sessionStatsFlow.update { it + (sessionId to stats) }
    }

    private suspend fun sessionCall(hostId: String, method: String, request: JsonObject): Boolean {
        val ok = calls.rpc(hostId, method, requestOf(request)).isSuccess
        if (ok) refreshSessions(hostId)
        return ok
    }

    // ── Рабочие папки ─────────────────────────────────────────────────────────

    /** Зарегистрировать папку как рабочую (`workspace/create`). */
    suspend fun createWorkspace(hostId: String, path: String): Boolean =
        calls.rpc(hostId, "workspace/create", requestOf(buildJsonObject { put("path", path) })).isSuccess

    /** Снять регистрацию рабочей папки, не удаляя её (`workspace/delete`). */
    suspend fun deleteWorkspace(hostId: String, workspaceId: String): Boolean =
        calls.rpc(hostId, "workspace/delete", requestOf(buildJsonObject { put("workspaceId", workspaceId) })).isSuccess

    suspend fun workspaceRename(hostId: String, workspaceId: String, title: String): Result<String> =
        calls.rpc(hostId, "workspace/rename", requestOf(buildJsonObject {
            put("workspaceId", workspaceId)
            put("title", title)
        }))

    /** Переставить рабочую папку перед [beforeWorkspaceId] (null — в конец) (`workspace/insertBefore`). */
    suspend fun moveWorkspace(hostId: String, workspaceId: String, beforeWorkspaceId: String?): Boolean =
        calls.rpc(hostId, "workspace/insertBefore", requestOf(buildJsonObject {
            put("workspaceId", workspaceId)
            if (beforeWorkspaceId != null) put("beforeWorkspaceId", beforeWorkspaceId)
        })).isSuccess

    // ── Сообщения ─────────────────────────────────────────────────────────────

    private val _doneMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)

    /** Готовые сообщения всех сессий, включая вызовы инструментов: по ним ведётся журнал вызовов. */
    val doneMessages: SharedFlow<ChatMessage> = _doneMessages.asSharedFlow()

    fun getMessages(hostId: String, sessionId: String): Flow<List<ChatMessage>> =
        dao.getMessages(hostId, sessionId).map { entities ->
            // Старые версии сохраняли служебные вставки Host и дубли «своё сообщение + эхо Host».
            val cleaned = entities.filterNot { it.role == "USER" && isInjectedContextMessage(it.contentJson.orEmpty()) }
            var previous: MessageEntity? = null
            cleaned.filter { entity ->
                val prev = previous
                previous = entity
                !(prev != null && entity.role == "USER" && prev.role == "USER" &&
                    prev.contentJson == entity.contentJson &&
                    kotlin.math.abs((entity.createdAt ?: 0L) - (prev.createdAt ?: 0L)) < 1_500)
            }.map { entity ->
                ChatMessage(
                    hostId = entity.hostId,
                    sessionId = entity.sessionId,
                    messageId = entity.messageId,
                    role = runCatching { MessageRole.valueOf(entity.role ?: "USER") }.getOrDefault(MessageRole.USER),
                    content = entity.contentJson ?: "",
                    seq = entity.seq ?: 0L,
                    createdAt = entity.createdAt ?: System.currentTimeMillis(),
                )
            }
        }

    /** Первая строка последнего ответа агента по каждой сессии, без разметки Markdown. */
    fun sessionPreviews(hostId: String): Flow<Map<String, String>> =
        dao.lastAssistantMessages(hostId).map { rows ->
            rows.mapNotNull { row ->
                val line = row.content.orEmpty().lineSequence()
                    .map { it.replace(MARKDOWN_MARKS, "").replace(MARKDOWN_LINK, "$1").trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
                line?.let { row.sessionId to it.take(120) }
            }.toMap()
        }

    /**
     * Отправить сообщение. В историю оно попадёт, когда Host проиграет его обратно
     * (`user/message` после claim): своя оптимистичная запись дала бы дубль с другим id.
     * Мгновенный пузырь показывает вызывающий. Если Host отклонит prompt, в чат
     * записывается видимая ошибка.
     */
    fun sendMessage(hostId: String, sessionId: String, content: String, images: List<PromptImagePart> = emptyList()) {
        scope.launch(Dispatchers.IO) {
            dao.touchSession(hostId, sessionId, System.currentTimeMillis())
            val result = client.sendMessage(hostId, sessionId, content, images)
            if (result is RemoteResult.Failure) {
                val now = System.currentTimeMillis()
                dao.insertMessages(listOf(MessageEntity(
                    hostId = hostId,
                    sessionId = sessionId,
                    messageId = "msg_error_$now",
                    role = MessageRole.ASSISTANT.name,
                    seq = 0L,
                    contentJson = "⚠️ " + Tr.t("Send failed: ${result.message}", "Не удалось отправить: ${result.message}"),
                    createdAt = now,
                    status = "error",
                )))
            }
        }
    }

    /** Догрузить более раннюю историю (при прокрутке к началу); false — истории больше нет. */
    suspend fun loadOlderMessages(hostId: String, sessionId: String): Boolean =
        (client.loadOlderHistory(hostId, sessionId) as? RemoteResult.Success)?.data ?: false

    // ── Открытая сессия ───────────────────────────────────────────────────────

    private var viewedSessionId: String? = null

    /** Текст ответа, который печатается сейчас (база при переподключении + живые фрагменты). */
    private val _streamingTextFlow = MutableStateFlow("")
    val streamingTextFlow: StateFlow<String> = _streamingTextFlow.asStateFlow()

    /** Рассуждение, которое печатается сейчас. */
    private val _streamingReasoningFlow = MutableStateFlow("")
    val streamingReasoningFlow: StateFlow<String> = _streamingReasoningFlow.asStateFlow()

    /** План агента (проекция `todos`). */
    private val _todosFlow = MutableStateFlow<List<TodoEntryInfo>>(emptyList())
    val todosFlow: StateFlow<List<TodoEntryInfo>> = _todosFlow.asStateFlow()

    /** Цель сессии (проекция `goal`). */
    private val _goalFlow = MutableStateFlow<GoalInfo?>(null)
    val goalFlow: StateFlow<GoalInfo?> = _goalFlow.asStateFlow()

    /** Сообщения, отправленные во время хода, — ждут следующего (проекция `inbox`). */
    private val _queuedMessagesFlow = MutableStateFlow<List<QueuedMessageInfo>>(emptyList())
    val queuedMessagesFlow: StateFlow<List<QueuedMessageInfo>> = _queuedMessagesFlow.asStateFlow()

    /** Изменения файлов за последний ход (`workspace/changes` + `changes.summary`). */
    private val _changesFlow = MutableStateFlow<ChangesSummaryInfo?>(null)
    val changesFlow: StateFlow<ChangesSummaryInfo?> = _changesFlow.asStateFlow()
    private var changesSeq: Long = 0L

    /** Файлы — результат хода (`deliverables/presented`). */
    private val _deliverablesFlow = MutableStateFlow<List<PresentedFileInfo>>(emptyList())
    val deliverablesFlow: StateFlow<List<PresentedFileInfo>> = _deliverablesFlow.asStateFlow()

    /** Запуски workflow по сессиям, в порядке создания. */
    private val _workflowRunsFlow = MutableStateFlow<Map<String, List<WorkflowRunInfo>>>(emptyMap())
    val workflowRunsFlow: StateFlow<Map<String, List<WorkflowRunInfo>>> = _workflowRunsFlow.asStateFlow()

    /** Картинки в сообщениях пользователя по id сообщения. */
    private val _messageImagesFlow = MutableStateFlow<Map<String, List<AttachmentImageRef>>>(emptyMap())
    val messageImagesFlow: StateFlow<Map<String, List<AttachmentImageRef>>> = _messageImagesFlow.asStateFlow()

    /** Оценки ответов по id сообщения. */
    private val _messageFeedbackFlow = MutableStateFlow<Map<String, MessageFeedbackInfo>>(emptyMap())
    val messageFeedbackFlow: StateFlow<Map<String, MessageFeedbackInfo>> = _messageFeedbackFlow.asStateFlow()

    /** Открыть сессию: `session/follow` проигрывает историю и результаты, плюс оценки ответов. */
    fun watchSession(hostId: String, sessionId: String) {
        client.followSession(hostId, sessionId)
        scope.launch(Dispatchers.IO) { refreshMessageFeedback(hostId, sessionId) }
    }

    /** Пользователь смотрит [sessionId] (null — ни одну): состояние прошлой сессии сбрасывается. */
    fun markSessionViewed(sessionId: String?) {
        if (sessionId != viewedSessionId) {
            _streamingTextFlow.value = ""
            _streamingReasoningFlow.value = ""
            _todosFlow.value = emptyList()
            _goalFlow.value = null
            _deliverablesFlow.value = emptyList()
            _changesFlow.value = null
            changesSeq = 0L
            _queuedMessagesFlow.value = emptyList()
            _messageFeedbackFlow.value = emptyMap()
            _messageImagesFlow.value = emptyMap()
            _workflowRunsFlow.value = emptyMap()
        }
        viewedSessionId = sessionId
        if (sessionId != null) unreadSessionIdsFlow.update { it - sessionId }
    }

    /**
     * Изменить сообщение в очереди (`session/updateQueue`).
     * @param action `edit` (новый [text]), `remove` или `steer` (отправить в текущий ход).
     */
    suspend fun updateQueue(hostId: String, sessionId: String, itemId: String, action: String, text: String? = null): Boolean {
        val request = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", buildJsonObject {
                put("kind", action)
                if (action == "edit") {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text.orEmpty())
                        })
                    })
                }
            })
        }
        return calls.ok(hostId, "session/updateQueue", requestOf(request))
    }

    /** Построчное сравнение файла [index] из последних изменений (`changes.diff`). */
    suspend fun loadChangeDiff(hostId: String, sessionId: String, index: Int): ChangesDiffInfo? {
        val seq = changesSeq.takeIf { it > 0L } ?: return null
        return (client.fetchChangesDiff(hostId, sessionId, seq, index) as? RemoteResult.Success)?.data
    }

    /** Байты принятой Host картинки (`session/attachment`, base64). */
    suspend fun loadAttachmentImage(hostId: String, sessionId: String, attachmentId: String): ByteArray? {
        val request = buildJsonObject {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }
        val value = calls.value(hostId, "session/attachment", requestOf(request)) as? JsonObject ?: return null
        val data = (value.takeIf { it["data"] != null } ?: value["value"] as? JsonObject ?: value).str("data")
            ?: return null
        return runCatching { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }.getOrNull()
    }

    /** ZIP с журналом сессии (`session.export`). */
    suspend fun exportSessionLog(hostId: String, sessionId: String): ByteArray? =
        (client.downloadSessionExport(hostId, sessionId) as? RemoteResult.Success)?.data

    /** Последние сырые кадры сессии (диагностика). */
    fun rawSessionFrames(sessionId: String): List<SessionRawFrame> = client.rawSessionFrames(sessionId)

    /** Полоса модов Claude Code сессии: снимок на каждую перерисовку. */
    fun claudeCodeBand(hostId: String, sessionId: String): Flow<ClaudeCodeBandSnapshot?> =
        client.watchClaudeCodeBand(hostId, sessionId).map(::parseBandSnapshot)

    /**
     * Нажать кнопку на полосе модов.
     * @param generation поколение снимка: на устаревшую кнопку Host ответит отказом.
     */
    suspend fun pressClaudeCodeBand(hostId: String, sessionId: String, generation: Long, actionId: String): Result<ClaudeCodeBandSnapshot?> {
        val args = buildJsonObject {
            put("agentId", sessionId)
            put("generation", generation)
            put("actionId", actionId)
        }
        return calls.valueOrThrow(hostId, "claudeCodeMods/pressBand", args).mapCatching(::parseBandSnapshot)
    }

    /**
     * Оценить ответ: берём у Host текущую версию записи, затем put или delete,
     * обновлённый список — в [messageFeedbackFlow]. [rating] null — снять оценку.
     */
    suspend fun rateMessage(hostId: String, sessionId: String, messageId: String, rating: String?, note: String?) {
        val version = listMessageFeedback(hostId, sessionId)[messageId]?.version
        runCatching {
            if (rating != null) {
                calls.ok(hostId, "messageFeedback/put", requestOf(buildJsonObject {
                    put("sessionId", sessionId)
                    put("messageId", messageId)
                    put("rating", rating)
                    if (!note.isNullOrBlank()) put("note", note)
                    // null — записи ещё нет.
                    if (version == null) put("ifVersion", JsonNull) else put("ifVersion", version)
                }))
            } else if (version != null) {
                calls.ok(hostId, "messageFeedback/delete", requestOf(buildJsonObject {
                    put("sessionId", sessionId)
                    put("messageId", messageId)
                    put("ifVersion", version)
                }))
            }
        }
        refreshMessageFeedback(hostId, sessionId)
    }

    suspend fun refreshMessageFeedback(hostId: String, sessionId: String) {
        _messageFeedbackFlow.value = listMessageFeedback(hostId, sessionId)
    }

    private suspend fun listMessageFeedback(hostId: String, sessionId: String): Map<String, MessageFeedbackInfo> {
        val request = requestOf(buildJsonObject { put("sessionId", sessionId) })
        var value = calls.value(hostId, "messageFeedback/list", request) as? JsonObject ?: return emptyMap()
        // Ответ messageFeedback обёрнут ещё раз: {ok, value: {items}}.
        if (value["items"] == null) value = value["value"] as? JsonObject ?: value
        val items = value["items"] as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val messageId = obj.str("messageId") ?: return@mapNotNull null
            messageId to MessageFeedbackInfo(
                messageId = messageId,
                rating = obj.str("rating").orEmpty(),
                note = obj.str("note"),
                version = obj.str("version").orEmpty(),
            )
        }.toMap()
    }

    // ── События Host ──────────────────────────────────────────────────────────

    private val subscribedHosts = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Подписаться на все потоки событий Host. Повторный вызов для того же Host ничего
     * не делает — иначе события пришли бы дважды.
     */
    fun subscribeRemoteEvents(hostId: String) {
        if (!subscribedHosts.add(hostId)) return
        scope.launch(Dispatchers.IO) { client.subscribeEvents(hostId).collect { onSessionEvent(hostId, it) } }
        scope.launch(Dispatchers.IO) { client.forwardedHostEvents(hostId).collect { onHostEvent(hostId, it) } }
        scope.launch(Dispatchers.IO) { client.watchAccount(hostId).collect(account::onAccountView) }
        scope.launch(Dispatchers.IO) { client.forwardedWaterfalls(hostId).collect { approvals.onWaterfall(hostId, it) } }
        scope.launch(Dispatchers.IO) { client.forwardedEventPayloads(hostId).collect(plugins::onEventPayload) }
        scope.launch(Dispatchers.IO) { client.sessionControl(hostId).collect(::onControlFrame) }
        scope.launch(Dispatchers.IO) {
            client.workspaceFollow(hostId).collect { frame ->
                val root = runCatching { hostJson.parseToJsonElement(frame) as? JsonObject }.getOrNull()
                    ?: return@collect
                _hostWorkspacesFlow.value = workspaceTree.apply(root)
                // Серия кадров — одно обновление списка сессий.
                workspaceRefreshJob?.cancel()
                workspaceRefreshJob = scope.launch {
                    delay(250)
                    refreshSessions(hostId)
                }
            }
        }
    }

    /** События сессий: ход, фрагменты текста, сообщения, изменения, результаты. */
    private suspend fun onSessionEvent(hostId: String, event: RemoteEvent) {
        val viewed = viewedSessionId
        when (event) {
            is RemoteEvent.SessionRunning -> {
                runningSessionIdsFlow.update { if (event.running) it + event.sessionId else it - event.sessionId }
                if (!event.running) {
                    if (event.sessionId == viewed) clearStreaming()
                    else unreadSessionIdsFlow.update { it + event.sessionId }
                }
            }
            is RemoteEvent.MessageDelta -> {
                runningSessionIdsFlow.update { it + event.sessionId }
                if (event.sessionId == viewed) _streamingTextFlow.update { it + event.delta }
            }
            is RemoteEvent.ReasoningDelta -> {
                runningSessionIdsFlow.update { it + event.sessionId }
                if (event.sessionId == viewed) _streamingReasoningFlow.update { it + event.delta }
            }
            is RemoteEvent.WorkflowRunUpdated -> if (event.sessionId == viewed) {
                _workflowRunsFlow.update { all ->
                    val runs = all[event.sessionId].orEmpty().toMutableList()
                    val index = runs.indexOfFirst { it.runId == event.run.runId }
                    if (index >= 0) runs[index] = event.run else runs += event.run
                    all + (event.sessionId to runs)
                }
            }
            is RemoteEvent.UserImages -> if (event.sessionId == viewed) {
                _messageImagesFlow.update { it + (event.messageId to event.images) }
            }
            is RemoteEvent.ChangesAnnounced -> if (event.sessionId == viewed) {
                changesSeq = event.seq
                scope.launch(Dispatchers.IO) {
                    val summary = (client.fetchChangesSummary(hostId, event.sessionId, event.seq) as? RemoteResult.Success)?.data
                    if (summary != null && viewedSessionId == event.sessionId) _changesFlow.value = summary
                }
            }
            is RemoteEvent.DeliverablesPresented -> if (event.sessionId == viewed) {
                _deliverablesFlow.update { (it + event.files).distinctBy { file -> file.path } }
            }
            is RemoteEvent.MessageDone -> {
                // «Идёт ход» решают только turn/start и turn/end: иначе сообщение после
                // turn/end вернуло бы сессию в работу, и кнопка «Стоп» залипла бы.
                val message = event.message
                if (message.sessionId == viewed && message.role == MessageRole.ASSISTANT) clearStreaming()
                _doneMessages.tryEmit(message)
                dao.touchSession(message.hostId, message.sessionId, System.currentTimeMillis())
                dao.insertMessages(listOf(MessageEntity(
                    hostId = message.hostId,
                    sessionId = message.sessionId,
                    messageId = message.messageId,
                    role = message.role.name,
                    seq = message.seq,
                    contentJson = message.content,
                    createdAt = message.createdAt,
                    status = "done",
                )))
            }
            is RemoteEvent.SessionUpdated -> dao.insertSessions(listOf(event.session.toEntity()))
            else -> Unit
        }
    }

    /** Пересланные события Host: что-то изменилось — перечитать затронутое. */
    private suspend fun onHostEvent(hostId: String, name: String) {
        when (name) {
            // Те же события слушает выбор модели в веб-клиенте.
            "llm/adapters-updated", "settings/document-updated",
            "credentials/record-updated", "credentials/reference-updated" -> hostSettings.refreshModelCatalog(hostId)
            "plugin-manager/changed" -> plugins.onPluginsChanged(hostId)
            "plugin-market/changed" -> plugins.refreshBridgePluginCatalog()
            "schedule/changed" -> tasks.onSchedulesChanged(hostId)
            "deepseek-account/session-expired" -> account.onSessionExpired()
            "deepseek-account/model-sign-in-required" -> account.onModelSignInRequired()
            "commands/change" -> viewedSessionId?.let { commands.refreshHostCommands(hostId, it) }
            "api-session/added", "api-session/removed", "api-session/status",
            "api-session/activity", "api-session/error",
            "agent-preset/selected", "goal/activation-changed" -> refreshSessions(hostId)
        }
    }

    /** Кадр `session/control`: модели всех сессий и проекции открытой. */
    private fun onControlFrame(text: String) {
        val root = runCatching { hostJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val viewed = viewedSessionId
        when (root.str("type")) {
            "baseline" -> {
                val projections = (root["value"] as? JsonObject)?.get("projections") as? JsonObject ?: return
                _sessionModelFlow.value = projections.mapNotNull { (sessionId, projection) ->
                    val values = (projection as? JsonObject)?.get("values") as? JsonObject
                    if (sessionId == viewed) {
                        _todosFlow.value = parseTodos(values?.get("todos"))
                        _goalFlow.value = parseGoal(values?.get("goal"))
                        _queuedMessagesFlow.value = parseQueue(values?.get("inbox"))
                    }
                    parseSelectedModel(values?.get("modelSelection"))?.let { sessionId to it }
                }.toMap()
            }
            "projection" -> {
                val sessionId = root.str("sessionId") ?: return
                val value = root["value"]
                when (root.str("key")) {
                    "modelSelection" -> parseSelectedModel(value)?.let { model ->
                        _sessionModelFlow.update { it + (sessionId to model) }
                    }
                    "todos" -> if (sessionId == viewed) _todosFlow.value = parseTodos(value)
                    "goal" -> if (sessionId == viewed) _goalFlow.value = parseGoal(value)
                    "inbox" -> if (sessionId == viewed) _queuedMessagesFlow.value = parseQueue(value)
                }
            }
        }
    }

    private fun clearStreaming() {
        _streamingTextFlow.value = ""
        _streamingReasoningFlow.value = ""
    }

    private companion object {
        val MARKDOWN_MARKS = Regex("[*_`#>]+")
        val MARKDOWN_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    }
}

/** Большинство вызовов сессий и папок принимают аргументы в виде `{request: {...}}`. */
private fun requestOf(request: JsonObject) = buildJsonObject { put("request", request) }

/** Служебная вставка Host (контекст для модели), а не ввод пользователя — в чате не показывается. */
private fun isInjectedContextMessage(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("Current runtime context") ||
        trimmed.startsWith("Time sampled while preparing turn") ||
        trimmed.startsWith("Browser time zone for this request:")
}

private fun SessionInfo.toEntity(archived: Boolean = this.archived, pinned: Boolean = this.pinned) = SessionEntity(
    hostId = hostId,
    sessionId = sessionId,
    title = title,
    workspacePath = workspacePath,
    status = status,
    updatedAt = updatedAt,
    lastSeq = 0L,
    archived = if (archived) 1 else 0,
    pinned = if (pinned) 1 else 0,
    model = model,
    permissionPreset = permissionPreset,
)
