package com.deepseek.dsh.core.protocol

import com.deepseek.dsh.core.model.AccountBalanceInfo
import com.deepseek.dsh.core.model.AccountProfileInfo
import com.deepseek.dsh.core.model.AccountViewInfo
import com.deepseek.dsh.core.model.AutomationTaskItem
import com.deepseek.dsh.core.model.BuiltInPluginInfo
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.ComposerStats
import com.deepseek.dsh.core.model.ModelCatalogInfo
import com.deepseek.dsh.core.model.PluginInventorySnapshotInfo
import com.deepseek.dsh.core.model.PluginManagerSnapshot
import com.deepseek.dsh.core.model.PluginRowInfo
import com.deepseek.dsh.core.model.SessionInfo
import com.deepseek.dsh.core.model.TeamTaskItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
sealed interface RemoteResult<out T> {
    @Serializable
    data class Success<T>(val data: T) : RemoteResult<T>

    @Serializable
    data class Failure(val code: String, val message: String) : RemoteResult<Nothing>
}

/** Сырой кадр сессии (для диагностики). */
@Serializable
data class SessionRawFrame(
    /** Порядковый номер. */
    val index: Int,
    /** Тип кадра (`item`/`end`/…). */
    val type: String,
    /** Исходный JSON. */
    val text: String,
    /** Время получения (epoch ms). */
    val timestamp: Long,
)

@Serializable
sealed interface RemoteEvent {
    @Serializable
    data class Ready(val hostId: String, val generation: Long) : RemoteEvent

    @Serializable
    data class MessageDelta(val sessionId: String, val delta: String) : RemoteEvent
    data class ReasoningDelta(val sessionId: String, val delta: String) : RemoteEvent
    /** Изменение запуска workflow (каждое событие несёт полный снимок). */
    data class WorkflowRunUpdated(
        val sessionId: String,
        val run: com.deepseek.dsh.core.model.WorkflowRunInfo,
    ) : RemoteEvent

    /** Картинки из `user/message` (миниатюры в пузыре). */
    data class UserImages(
        val sessionId: String,
        val messageId: String,
        val images: List<AttachmentImageRef>,
    ) : RemoteEvent

    /** `workspace/changes`: изменения хода записаны, сводку можно загрузить по seq. */
    data class ChangesAnnounced(val sessionId: String, val seq: Long, val turn: Int) : RemoteEvent

    /** `deliverables/presented`: файлы — результат хода. */
    data class DeliverablesPresented(
        val sessionId: String,
        val files: List<PresentedFileInfo>,
    ) : RemoteEvent

    @Serializable
    data class MessageDone(val sessionId: String, val message: ChatMessage) : RemoteEvent

    @Serializable
    data class SessionUpdated(val session: SessionInfo) : RemoteEvent
    data class SessionRunning(val sessionId: String, val running: Boolean) : RemoteEvent

    @Serializable
    data class Error(val code: String, val message: String) : RemoteEvent
}

/**
 * Запрос waterfall от Host, ждущий ответа клиента.
 *
 * Таковы `approval/request` и `user-questions/request`:
 * клиент отвечает через `$events/result` — `result` (или `next`, тогда решает Host).
 *
 * @property hostId Host запроса.
 * @property event имя события (например, `approval/request`).
 * @property eventId id события для ответа.
 * @property agentId агент (сессия) запроса.
 * @property request тело запроса (`toolName`, `reason` и др.).
 */
data class ForwardedWaterfall(
    val hostId: String,
    val event: String,
    val eventId: String,
    val agentId: String?,
    val request: kotlinx.serialization.json.JsonObject,
)

/** Изменённый файл из `changes.summary`. */
data class ChangedFileInfo(
    val path: String,
    val display: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean = false,
    val oversized: Boolean = false,
)

/** `changes.summary`: сводка изменений хода. */
data class ChangesSummaryInfo(
    val turn: Int,
    val seq: Long,
    val files: List<ChangedFileInfo>,
    val total: Int,
    val added: Int,
    val deleted: Int,
)

/** Фрагмент (hunk) diff. */
data class DiffHunkInfo(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
)

/** `changes.diff`: сравнение одного файла. */
data class ChangesDiffInfo(
    val kind: String,
    val path: String,
    val display: String,
    val hunks: List<DiffHunkInfo> = emptyList(),
    val before: Boolean = true,
    val after: Boolean = true,
    val coarse: Boolean = false,
)

/** Ссылка на картинку, принятую Host в сессии (без байтов). */
data class AttachmentImageRef(
    val attachmentId: String,
    val mediaType: String,
    val width: Int = 0,
    val height: Int = 0,
    val name: String? = null,
)

/** Картинка внутри prompt (PromptContentPart image, base64). */
data class PromptImagePart(
    val mediaType: String,
    val base64: String,
    val name: String? = null,
)

/** Открытое подключение к терминалу (двусторонний поток `terminal/attach`). */
interface TerminalConnection {
    /** Кадры терминала (сырой JSON snapshot / output / state / ack). */
    val frames: Flow<String>
    /** Отправить ввод (sequence растёт сам). */
    fun write(data: String)
    /** Изменить размер. */
    fun resize(cols: Int, rows: Int)
    /** Отключиться (процесс остаётся, можно вернуться). */
    fun close()
}

/** Окружение терминала (`terminal/environment`). */
data class TerminalEnvironmentInfo(
    val cwd: String,
    val maxCols: Int,
    val maxRows: Int,
)

/** Один результат из `deliverables/presented`. */
data class PresentedFileInfo(
    val path: String,
    val description: String? = null,
)

/** Пересланное событие Host (имя + параметры). */
data class ForwardedEvent(
    val event: String,
    val args: kotlinx.serialization.json.JsonArray,
)

/** Фоновая задача из потока `job/list`. */
data class HostJobInfo(
    val id: String,
    val kind: String,
    val label: String,
    val owner: String?,
    val status: String,
    val progress: String?,
    val detail: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

/** Совпадение `session/search`. */
data class SessionSearchHit(val sessionId: String, val snippet: String)

/** Аккаунт DeepSeek через Host: состояние, вход, профиль, баланс (`account/...`). */
interface AccountApi {
    suspend fun getAccountState(hostId: String): RemoteResult<AccountViewInfo>

    /**
     * Подписка `account/watch`: состояние аккаунта на Host (вход, данные сохранены, выход).
     *
     * Обновляет UI во время входа сразу, без опроса.
     *
     * @param hostId идентификатор Host.
     * @returns `AccountViewInfo` на каждое изменение состояния.
     */
    fun watchAccount(hostId: String): Flow<AccountViewInfo>

    suspend fun startAccountSignIn(hostId: String, callbackOrigin: String): RemoteResult<AccountViewInfo>

    suspend fun signOutAccount(hostId: String): RemoteResult<Boolean>

    suspend fun getAccountProfile(hostId: String): RemoteResult<AccountProfileInfo?>

    suspend fun getAccountBalance(hostId: String): RemoteResult<AccountBalanceInfo>
}

/** Плагины Host: инвентарь, менеджер, строки, установка, включение, RPC плагина. */
interface PluginsApi {
    suspend fun getPluginInventory(hostId: String): RemoteResult<List<BuiltInPluginInfo>>

    suspend fun getPluginInventorySnapshot(hostId: String): RemoteResult<PluginInventorySnapshotInfo>

    suspend fun getPluginManagerSnapshot(hostId: String): RemoteResult<PluginManagerSnapshot>

    suspend fun getPluginRows(hostId: String): RemoteResult<List<PluginRowInfo>>

    suspend fun installPlugin(
        hostId: String,
        packageName: String,
        requestId: String? = null,
    ): RemoteResult<Boolean>

    suspend fun uninstallPlugin(hostId: String, packageName: String): RemoteResult<Boolean>

    suspend fun setBundleEnabled(hostId: String, packageName: String, enabled: Boolean): RemoteResult<Boolean>

    /**
     * Включить или выключить строку Loader в профиле (плагин не из bundle).
     *
     * @param entryId entryId из `pluginManager/listPlugins`, например `include:<id>`.
     * @param enabled нужное состояние.
     */
    suspend fun setPluginEnabled(hostId: String, entryId: String, enabled: Boolean): RemoteResult<Boolean>

    /**
     * Вызов маршрута плагина /api/<method> на Host (payload как есть, без обёртки args).
     *
     * Плагин регистрирует свои RPC как HTTP-маршруты Host (например, subscription-oauth.status);
     * тело — {type,rpcId,method,payload}, payload — параметры плагина. Приложение
     * берёт так данные и передаёт их в props статической разметки — код плагина не выполняется.
     */
    suspend fun pluginRpc(hostId: String, method: String, payloadJson: String): RemoteResult<String>
}

/** Настройки Host и каталог моделей (`settings/...`, `llm/...`). */
interface SettingsApi {
    suspend fun getModelCatalog(hostId: String): RemoteResult<ModelCatalogInfo>

    suspend fun openSettingsDocument(hostId: String): RemoteResult<Boolean>

    suspend fun getRemoteSettings(hostId: String): RemoteResult<Map<String, String>>

    suspend fun updateRemoteSettings(hostId: String, ns: String, patchJson: String): RemoteResult<Boolean>
}

/** Команда агента и задачи: команда сессии, общие задачи, автоматизации Host. */
interface TeamApi {
    suspend fun getAutomationTasks(hostId: String): RemoteResult<List<AutomationTaskItem>>

    suspend fun getTeamTasks(hostId: String): RemoteResult<List<TeamTaskItem>>

    /** Проекция `agentTeam` сессии (команда); null, если команды нет. */
    suspend fun getAgentTeam(
        hostId: String,
        sessionId: String,
    ): RemoteResult<com.deepseek.dsh.core.model.AgentTeamSummary?>
}

/** Фоновые задачи сессии (`job/list`, `job/follow`, `job/kill`). */
interface JobsApi {
    /** Подписка `job/list`: все видимые фоновые задачи сессии (полный набор в кадре). */
    fun jobList(hostId: String, sessionId: String): Flow<List<HostJobInfo>>

    /** Подписка `job/follow`: вывод задачи (сырой JSON opened/output/status). */
    fun jobFollow(hostId: String, sessionId: String?, jobId: String, from: Long? = null): Flow<String>

    /** `job/kill`: остановить фоновую задачу вручную. */
    suspend fun killJob(hostId: String, sessionId: String, jobId: String): RemoteResult<String>
}

/** Терминалы Host (`terminal/...`): окружение, открытие PTY, закрытие. */
interface TerminalApi {
    /** `terminal/environment`: текущая папка и предельный размер. */
    suspend fun terminalEnvironment(
        hostId: String,
        sessionId: String,
    ): RemoteResult<TerminalEnvironmentInfo>

    /** Открыть (или переиспользовать) терминал и подключиться; возвращает подключение для ввода. */
    suspend fun openTerminal(
        hostId: String,
        sessionId: String,
        cols: Int,
        rows: Int,
        terminalId: String = "main",
    ): RemoteResult<TerminalConnection>

    /** `terminal/close`: закрыть терминал и завершить его процесс. */
    suspend fun closeTerminal(hostId: String, sessionId: String, id: String): RemoteResult<Boolean>
}

/** Изменения файлов сессии и выгрузка журнала сессии. */
interface ChangesApi {
    /** `/api/session.export`: скачать ZIP с журналом сессии. */
    suspend fun downloadSessionExport(
        hostId: String,
        sessionId: String,
    ): RemoteResult<ByteArray>

    /** `/api/changes.summary`: файлы, изменённые за ход. */
    suspend fun fetchChangesSummary(
        hostId: String,
        sessionId: String,
        seq: Long,
    ): RemoteResult<ChangesSummaryInfo>

    /** `/api/changes.diff`: построчное сравнение изменённого файла. */
    suspend fun fetchChangesDiff(
        hostId: String,
        sessionId: String,
        seq: Long,
        index: Int,
    ): RemoteResult<ChangesDiffInfo>
}

/** События Host, пересланные клиенту (approval/request и др.), и ответы на них. */
interface HostEventsApi {
    /**
     * Подписка на имена событий, пересылаемых Host (поток Gateway `$events`).
     *
     * Клиент слушает `llm/adapters-updated`,
     * `settings/document-updated`, обновления учётных данных — и обновляет каталог моделей;
     * здесь берутся только имена, что обновлять — решает слой данных.
     *
     * @param hostId идентификатор Host.
     * @returns поток имён; при обрыве переподключается сам.
     */
    fun forwardedHostEvents(hostId: String): Flow<String>

    /** Тот же поток с параметрами (нужно, например, для прогресса установки плагина). */
    fun forwardedEventPayloads(hostId: String): Flow<ForwardedEvent>

    /**
     * Подписка на запросы waterfall от Host (`approval/request` и др.).
     *
     * Без ответа дольше [ANSWER_TIMEOUT_MS] уходит `next`, чтобы инструмент Host не завис.
     */
    fun forwardedWaterfalls(hostId: String): Flow<ForwardedWaterfall>

    /**
     * Ответить на [ForwardedWaterfall].
     *
     * @param hostId идентификатор Host.
     * @param eventId id события.
     * @param valueJson ответ JSON (например, `"allowed-once"`); null — `next`, решает Host.
     */
    fun answerWaterfall(hostId: String, eventId: String, valueJson: String?)
}

/** Вызовы Host напрямую: аутентификация и произвольный RPC. */
interface HostRpcApi {
    suspend fun authenticate(baseUrl: String, token: String?): RemoteResult<String>

    suspend fun rpcRaw(hostId: String, method: String, argsJson: String): RemoteResult<String>
}

/** Сессии: список, создание, модель и доступ, отправка, поток follow с историей, поиск. */
interface SessionsApi {
    suspend fun listSessions(hostId: String): RemoteResult<List<SessionInfo>>

    suspend fun createSession(hostId: String, title: String, workspacePath: String?, agentPreset: String? = null): RemoteResult<SessionInfo>

    suspend fun deleteSession(hostId: String, sessionId: String): RemoteResult<Boolean>

    suspend fun selectModel(hostId: String, sessionId: String, modelId: String, provider: String? = null, reasoningEffort: String? = null): RemoteResult<Boolean>

    suspend fun selectPermissionPreset(hostId: String, sessionId: String, preset: String): RemoteResult<Boolean>

    suspend fun cancelSession(hostId: String, sessionId: String): Boolean

    suspend fun answerUserQuestion(hostId: String, sessionId: String, callId: String, questionId: String, selected: String): Boolean = false

    suspend fun getSessionStats(hostId: String, sessionId: String): RemoteResult<ComposerStats>

    suspend fun sendMessage(
        hostId: String,
        sessionId: String,
        text: String,
        images: List<PromptImagePart> = emptyList(),
    ): RemoteResult<ChatMessage>

    fun subscribeEvents(hostId: String): Flow<RemoteEvent>

    /**
     * Подписка `workspace/follow`: рабочие папки и их сессии — базовый кадр + изменения.
     *
     * Кадры — сырой JSON (`baseline` / `upsert` / `remove` / `order` / `archived` /
     * `pinned` / `replace`); по ним обновляется список.
     */
    fun workspaceFollow(hostId: String): Flow<String>

    /** Подписка `session/control`: проекции всех сессий + изменения (выбор модели и др.). */
    fun sessionControl(hostId: String): Flow<String>

    /** Открыть `session/follow`: первый кадр snapshot проигрывает историю (с результатами). */
    fun followSession(hostId: String, sessionId: String)

    /** `session/page`: страница ранней истории (при прокрутке к началу). */
    suspend fun loadOlderHistory(hostId: String, sessionId: String): RemoteResult<Boolean>

    /** `session/search`: поиск сессий по содержимому (до 20). */
    suspend fun searchSessions(hostId: String, query: String): RemoteResult<List<SessionSearchHit>>

    /**
     * Подписка на полосу модов Claude Code сессии (поток `claudeCodeMods/watchBand`).
     *
     * @param hostId идентификатор Host.
     * @param sessionId id сессии (агент на Host).
     * @returns сырой JSON `SurfaceSnapshot` на каждую перерисовку; завершается с потоком.
     */
    fun watchClaudeCodeBand(
        hostId: String,
        sessionId: String,
    ): Flow<kotlinx.serialization.json.JsonElement?>

    /**
     * Последние сырые кадры сессии (диагностика), до 500, по времени.
     * @param sessionId id сессии.
     */
    fun rawSessionFrames(sessionId: String): List<SessionRawFrame>
}

/** Весь протокол клиента DSH; реализация — [RemoteClient]. */
interface DshClientProtocol : AccountApi, PluginsApi, SettingsApi, TeamApi, JobsApi, TerminalApi, ChangesApi, HostEventsApi, HostRpcApi, SessionsApi










