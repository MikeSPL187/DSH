package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.AgentTeamSummary
import com.deepseek.dsh.core.model.AutomationTaskItem
import com.deepseek.dsh.core.model.ScheduleInfo
import com.deepseek.dsh.core.model.TeamTaskItem
import com.deepseek.dsh.core.model.Tr
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.HostJobInfo
import com.deepseek.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Работа агента вне чата: команда, задачи, расписания и фоновые процессы сессии. */
class TasksRepository internal constructor(
    private val client: DshClientProtocol,
    private val calls: HostCalls,
    private val scope: CoroutineScope,
) {
    private val _automationTasksFlow = MutableStateFlow<List<AutomationTaskItem>>(emptyList())
    val automationTasksFlow: StateFlow<List<AutomationTaskItem>> = _automationTasksFlow.asStateFlow()

    private val _teamTasksFlow = MutableStateFlow<List<TeamTaskItem>>(emptyList())
    val teamTasksFlow: StateFlow<List<TeamTaskItem>> = _teamTasksFlow.asStateFlow()

    /** Команда текущей сессии (`agentTeam`); null — команды нет. */
    private val _agentTeamFlow = MutableStateFlow<AgentTeamSummary?>(null)
    val agentTeamFlow: StateFlow<AgentTeamSummary?> = _agentTeamFlow.asStateFlow()

    /** Все расписания Host с привязкой к сессиям и состоянием (`schedule/catalog`). */
    private val _scheduleCatalogFlow = MutableStateFlow<List<ScheduleInfo>>(emptyList())
    val scheduleCatalogFlow: StateFlow<List<ScheduleInfo>> = _scheduleCatalogFlow.asStateFlow()

    /** Фоновые процессы, видимые в текущей сессии (`job/list`). */
    private val _jobsFlow = MutableStateFlow<List<HostJobInfo>>(emptyList())
    val jobsFlow: StateFlow<List<HostJobInfo>> = _jobsFlow.asStateFlow()

    private var jobsHostId: String? = null
    private var jobsSessionId: String? = null
    private var jobsJob: Job? = null

    /** Перечитать команду сессии; без сессии — очистить. */
    suspend fun refreshAgentTeam(hostId: String, sessionId: String?) {
        _agentTeamFlow.value = sessionId?.takeIf { it.isNotBlank() }
            ?.let { (client.getAgentTeam(hostId, it) as? RemoteResult.Success)?.data }
    }

    suspend fun refreshAutomationTasks(hostId: String) {
        (client.getAutomationTasks(hostId) as? RemoteResult.Success)?.data?.let { _automationTasksFlow.value = it }
    }

    suspend fun refreshTeamTasks(hostId: String) {
        (client.getTeamTasks(hostId) as? RemoteResult.Success)?.data?.let { _teamTasksFlow.value = it }
    }

    /** Перечитать расписания (`schedule/catalog`; сессии не активируются). */
    suspend fun refreshScheduleCatalog(hostId: String) {
        val items = calls.value(hostId, "schedule/catalog") as? JsonArray ?: return
        _scheduleCatalogFlow.value = items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ScheduleInfo(
                id = obj.str("id") ?: return@mapNotNull null,
                title = obj.str("title").orEmpty(),
                prompt = obj.str("prompt").orEmpty(),
                scheduledAt = obj.str("scheduledAt").orEmpty(),
                sessionId = obj.str("sessionId").orEmpty(),
                status = obj.str("status").orEmpty(),
                kind = obj.str("kind").orEmpty(),
                afterSeconds = obj.str("afterSeconds")?.toLongOrNull() ?: 0,
                everySeconds = obj.str("everySeconds")?.toLongOrNull() ?: 0,
                time = obj.str("time").orEmpty(),
                weekdays = (obj["weekdays"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty(),
                expression = obj.str("expression").orEmpty(),
            )
        }
    }

    /** Удалить расписание (`schedule/delete`). */
    suspend fun deleteSchedule(hostId: String, sessionId: String, id: String): Boolean {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("sessionId", sessionId)
                put("id", id)
            })
        }
        val ok = calls.rpc(hostId, "schedule/delete", args).getOrNull()?.contains("\"deleted\":true") == true
        if (ok) refreshScheduleCatalog(hostId)
        return ok
    }

    /** Следить за фоновыми процессами сессии; при смене сессии подписка меняется. */
    fun watchJobs(hostId: String, sessionId: String) {
        if (jobsHostId == hostId && jobsSessionId == sessionId) return
        jobsJob?.cancel()
        jobsHostId = hostId
        jobsSessionId = sessionId
        _jobsFlow.value = emptyList()
        jobsJob = scope.launch(Dispatchers.IO) {
            keepSubscribed { client.jobList(hostId, sessionId).collect { _jobsFlow.value = it } }
        }
    }

    /** Перестать следить (ушли из сессии). */
    fun unwatchJobs() {
        jobsJob?.cancel()
        jobsJob = null
        jobsHostId = null
        jobsSessionId = null
        _jobsFlow.value = emptyList()
    }

    /**
     * Вывод фонового процесса (`job/follow`). Обрыв соединения — не исключение у сборщика
     * (оно роняло приложение), а последний кадр `status` «соединение потеряно»: вывод встал не молча.
     */
    fun followJob(hostId: String, sessionId: String?, jobId: String, from: Long? = null): Flow<String> =
        client.jobFollow(hostId, sessionId, jobId, from)
            .catch { e ->
                if (e is CancellationException) throw e
                emit(buildJsonObject { put("type", "status"); put("job", buildJsonObject { put("status", Tr.t("connection lost", "соединение потеряно")) }) }.toString())
            }

    /** Остановить фоновый процесс (`job/kill`). */
    suspend fun killJob(hostId: String, sessionId: String, jobId: String): String? =
        (client.killJob(hostId, sessionId, jobId) as? RemoteResult.Success)?.data

    /** Расписания изменились (`schedule/changed`). */
    internal suspend fun onSchedulesChanged(hostId: String) {
        refreshAutomationTasks(hostId)
        refreshTeamTasks(hostId)
    }
}
