package com.deepseek.dsh

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.feature.settings.HostPermissionPreset
import com.deepseek.dsh.feature.settings.logic.AutoRun
import com.deepseek.dsh.feature.settings.logic.Automation
import com.deepseek.dsh.feature.settings.logic.AutomationStore
import com.deepseek.dsh.feature.settings.logic.DshUserPrefs
import com.deepseek.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import com.deepseek.dsh.core.model.ScheduleInfo

/**
 * Расписание автоматизаций: у каждой включённой — одно задание JobScheduler на время
 * следующего запуска с её условиями («только при зарядке», «только по Wi‑Fi»).
 * Если условие не выполнено, Android откладывает задание; запуск позже часа отмечается
 * в истории как перенесённый.
 */
object AutomationScheduler {
    private const val EXTRA_ID = "automation_id"
    private const val EXTRA_AT = "planned_at"

    private fun jobId(id: String) = 0x4155_0000 or (id.hashCode() and 0xffff)

    fun schedule(context: Context, a: Automation) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        js.cancel(jobId(a.id))
        val at = AutomationStore.nextRun(a) ?: return
        val extras = PersistableBundle().apply { putString(EXTRA_ID, a.id); putLong(EXTRA_AT, at) }
        val job = JobInfo.Builder(jobId(a.id), ComponentName(context, AutomationJobService::class.java))
            .setMinimumLatency((at - System.currentTimeMillis()).coerceAtLeast(0))
            .setRequiresCharging(a.charge)
            .setRequiredNetworkType(if (a.wifi) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        runCatching { js.schedule(job) }
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(JobScheduler::class.java)?.cancel(jobId(id))
    }

    /** Пересчитать все задания (после запуска приложения и изменений). */
    fun rescheduleAll(context: Context) {
        AutomationStore.items.forEach { if (it.on) schedule(context, it) else cancel(context, it.id) }
    }

    internal fun idOf(params: JobParameters): String? = params.extras.getString(EXTRA_ID)
    internal fun plannedOf(params: JobParameters): Long = params.extras.getLong(EXTRA_AT)
}

/** Задание JobScheduler: запускает автоматизацию в фоне и сразу планирует следующий запуск. */
class AutomationJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val id = AutomationScheduler.idOf(params) ?: return false
        val app = applicationContext as DshApplication
        // Сама работа идёт в области приложения: Host — foreground-сервис, задание ему не нужно.
        app.applicationScope.launch {
            AutomationRunner.run(app, id, AutomationScheduler.plannedOf(params))
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/**
 * Один запуск автоматизации: Host → новая сессия в проекте → права и модель →
 * промпт → ожидание конца хода → итог в историю, уведомление по правилу, следующий запуск.
 */
object AutomationRunner {
    private const val LOCAL_HOST = "local-host"
    private const val TURN_TIMEOUT_MS = 30 * 60_000L

    /** Автоматизации, которые сейчас идут в этом процессе. */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Занять запуск [id]; false — он уже идёт. Запись «run» в истории без живого запуска осталась от убитого
     * процесса (перезагрузка, нехватка памяти, смахивание): раньше по ней автоматизация считалась идущей
     * и больше не запускалась никогда. Теперь такая запись отмечается ошибкой [interrupted].
     */
    internal fun claim(id: String, interrupted: String): Boolean {
        if (!active.add(id)) return false
        AutomationStore.get(id)?.history?.firstOrNull()?.takeIf { it.status == "run" }?.let { stale ->
            AutomationStore.addRun(id, stale.copy(status = "err", result = interrupted))
        }
        return true
    }

    internal fun release(id: String) { active.remove(id) }

    suspend fun run(app: DshApplication, id: String, plannedAt: Long = System.currentTimeMillis()) {
        val a = AutomationStore.get(id) ?: return
        if (!claim(id, app.getString(R.string.au_run_interrupted))) return
        try {
            runClaimed(app, id, a, plannedAt)
        } finally {
            release(id)
        }
    }

    private suspend fun runClaimed(app: DshApplication, id: String, a: Automation, plannedAt: Long) {
        val started = System.currentTimeMillis()
        if (plannedAt > 0 && started - plannedAt > 60 * 60_000L) {
            AutomationStore.addRun(id, AutoRun(plannedAt, "skip", null, app.getString(R.string.au_run_postponed, conditionsText(app, a))))
        }
        AutomationStore.addRun(id, AutoRun(started, "run", null, app.getString(R.string.au_run_working)))
        val outcome = runCatching { execute(app, a) }.getOrElse { Outcome(false, it.message ?: app.getString(R.string.au_run_failed), null, false) }
        val finished = System.currentTimeMillis()
        AutomationStore.addRun(id, AutoRun(started, if (outcome.ok) "ok" else "err", finished - started, outcome.summary, outcome.sessionId))
        notifyIfNeeded(app, a, outcome)
        AutomationStore.get(id)?.let { AutomationScheduler.schedule(app, it) }
    }

    private data class Outcome(val ok: Boolean, val summary: String, val sessionId: String?, val changed: Boolean)

    private suspend fun execute(app: DshApplication, a: Automation): Outcome = withContext(Dispatchers.IO) {
        val repo = app.dshRepository
        val workspace = a.project.ifBlank { StorageAccess.workspaceRoot(app).absolutePath }
        // Host: разбудить и привязать клиента, если приложение закрыто.
        val status = app.runtimeManager.getStatus().takeIf { it.phase == RuntimePhase.RUNNING && it.port != null }
            ?: app.runtimeManager.start(workspace).getOrNull()
            ?: return@withContext Outcome(false, app.getString(R.string.au_run_no_host), null, false)
        if (app.localHostPort <= 1 && status.port != null) app.bindLocalHost(status.port!!, status.token)
        repo.subscribeRemoteEvents(LOCAL_HOST)

        val session = repo.createSession(LOCAL_HOST, a.name, workspace).getOrElse {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_session, it.message ?: ""), null, false)
        }
        val sid = session.sessionId
        // Права — до промпта, и без запасного пути: не удалось — сессия не запускается. Иначе автоматизация
        // «только читать» в фоне, без пользователя, работала бы с правами сессии по умолчанию (вплоть до полного доступа).
        val preset = presetFor(a.perm)
        if (runCatching { repo.selectPermissionPreset(LOCAL_HOST, sid, preset) }.getOrDefault(false) != true) {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_perm), sid, false)
        }
        modelFor(app, a)?.let { (model, provider, effort) -> runCatching { repo.selectModel(LOCAL_HOST, sid, model, provider, effort) } }

        val prompt = buildString {
            append(a.prompt.trim())
            if (a.perm == "read") append("\n\n").append(app.getString(R.string.au_run_read_only))
        }
        repo.sendMessage(LOCAL_HOST, sid, prompt)
        // Ход начался — и закончился.
        withTimeoutOrNull(90_000) { repo.runningSessionIdsFlow.first { sid in it } }
        val done = withTimeoutOrNull(TURN_TIMEOUT_MS) { repo.runningSessionIdsFlow.first { sid !in it } } != null
        if (!done) runCatching { repo.cancelSession(LOCAL_HOST, sid) }
        val messages = withTimeoutOrNull(10_000) { repo.getMessages(LOCAL_HOST, sid).first { list -> list.any { it.role == MessageRole.ASSISTANT } } }.orEmpty()
        val answer = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty().substringAfterLast('\u0001')
        val changed = messages.any { m -> m.role == MessageRole.TOOL && Regex("\u0002(?:write|edit|apply_patch|patch|create)[^\u0002]*\u0002ok", RegexOption.IGNORE_CASE).containsMatchIn(m.content) }
        val summary = answer.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }?.take(140)
        when {
            !done -> Outcome(false, app.getString(R.string.au_run_timeout), sid, changed)
            answer.startsWith("⚠️") -> Outcome(false, answer.removePrefix("⚠️").trim().take(140), sid, changed)
            summary == null -> Outcome(false, app.getString(R.string.au_run_empty), sid, changed)
            else -> Outcome(true, summary, sid, changed)
        }
    }

    /** Пресет прав Host: «только читать» или «править в рабочей папке». */
    private fun presetFor(perm: String): String {
        val options = HostPermissionPreset.options.map { it.value }
        return if (perm == "edit") options.firstOrNull { "workspace" in it || ("write" in it && "danger" !in it) } ?: "workspace-write"
        else options.firstOrNull { "read" in it } ?: "read-only"
    }

    /** Модель по названию из каталога Host: (id, провайдер, уровень рассуждения). */
    private fun modelFor(app: DshApplication, a: Automation): Triple<String, String?, String?>? {
        val name = a.model.substringBefore(" · ").trim().ifBlank { return null }
        val catalog = app.dshRepository.hostSettings.modelCatalogFlow.value
        for (g in catalog.groups) {
            val m = g.models.firstOrNull { it.name == name || it.id == name } ?: continue
            val effort = m.reasoningEfforts.getOrNull(a.reasoning)?.id ?: m.defaultReasoningEffort
            return Triple(m.id, g.id, effort)
        }
        return null
    }

    private fun conditionsText(context: Context, a: Automation): String = listOfNotNull(
        if (a.charge) context.getString(R.string.au_run_no_charge) else null,
        if (a.wifi) context.getString(R.string.au_run_no_wifi) else null,
    ).joinToString(", ").ifBlank { context.getString(R.string.au_run_phone_busy) }

    private fun notifyIfNeeded(context: Context, a: Automation, o: Outcome) {
        if (DshUserPrefs.notifAuto == "never") return
        val rule = if (DshUserPrefs.notifAuto == "always" && a.notify == "err") "err" else a.notify
        val send = when (rule) {
            "always" -> true
            "changes" -> !o.ok || o.changed
            else -> !o.ok
        }
        if (!send) return
        DshNotifications.notify(
            context,
            context.getString(if (o.ok) R.string.au_run_notif_ok else R.string.au_run_notif_err, a.name),
            o.summary,
        )
    }
}

/** «Автоматизация» поверх хранилища, планировщика и расписания Host. */
@androidx.compose.runtime.Composable
internal fun DshAutomationRoute(
    projects: List<com.deepseek.dsh.feature.settings.ui.AutoProject>,
    models: List<String>,
    hostTasks: List<com.deepseek.dsh.core.model.ScheduleInfo>,
    onOpenSession: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as DshApplication
    val toast = com.deepseek.dsh.core.designsystem.kit.rememberToast()
    val reasoning = com.deepseek.dsh.feature.chat.ReasoningLevel.entries.map { context.getString(it.labelResId) }
    androidx.compose.runtime.LaunchedEffect(Unit) { HostSchedules.refresh?.invoke() }
    val actions = object : com.deepseek.dsh.feature.settings.ui.AutomationActions {
        override fun save(a: Automation) {
            val withLabel = if (a.model.isNotBlank() && " · " !in a.model && reasoning.isNotEmpty()) a.copy(model = a.model + " · " + reasoning.getOrElse(a.reasoning) { reasoning.first() }) else a
            AutomationStore.upsert(withLabel)
            AutomationScheduler.schedule(context, withLabel)
        }
        override fun delete(id: String) { AutomationScheduler.cancel(context, id); AutomationStore.delete(id) }
        override fun runNow(id: String) {
            AutomationStore.get(id)?.let { toast.show(context.getString(R.string.au_run_started, it.name)) }
            app.applicationScope.launch { AutomationRunner.run(app, id, 0) }
        }
        override fun openSession(sessionId: String) = onOpenSession(sessionId)
        override fun cancelAgentTask(id: String) { hostTasks.firstOrNull { it.id == id }?.let(HostSchedules::cancel) }
    }
    val tasks = hostTasks.filter { it.status.isBlank() || it.status == "pending" || it.status == "scheduled" || it.status == "active" }.map { s ->
        com.deepseek.dsh.feature.settings.ui.AgentTask(s.id, s.title.ifBlank { s.prompt.take(60) }, scheduleText(context, s), s.sessionId.ifBlank { null })
    }
    com.deepseek.dsh.feature.settings.ui.AutomationHomeScreen(
        items = AutomationStore.items, onClose = onClose, actions = actions, agentTasks = tasks, projects = projects,
        models = models.map { it.substringBefore(" · ") }.distinct(), reasoningLevels = reasoning,
        defaultModel = AutomationStore.defaultModel, toast = toast,
    )
}

private fun scheduleText(context: Context, s: com.deepseek.dsh.core.model.ScheduleInfo): String {
    val time = s.time.take(5)
    return when (s.kind) {
        "daily" -> context.getString(R.string.au_sched_daily, time)
        "weekly" -> context.getString(R.string.au_sched_weekly, time)
        "every" -> context.getString(R.string.au_sched_every, android.text.format.DateUtils.formatElapsedTime(s.everySeconds))
        "cron" -> context.getString(R.string.au_sched_cron, s.expression)
        else -> context.getString(R.string.au_sched_at, s.scheduledAt.replace('T', ' ').take(16))
    }
}

/**
 * Мост к расписанию Host: задачи, которые агент поставил сам.
 *
 * Список — `schedule/catalog`, отмена — `schedule/delete`.
 */
object HostSchedules {
    /** Расписания Host как поток (после отмены обновляется сам). */
    var flow: kotlinx.coroutines.flow.StateFlow<List<ScheduleInfo>>? = null
        private set

    /** Прочитать при входе на страницу (о новых напоминаниях Host клиенту не сообщает). */
    var refresh: (suspend () -> Unit)? = null
        private set

    /** Вызывается один раз из эффекта сессии в MainAppHost. */
    fun wire(
        repository: com.deepseek.dsh.core.data.DshRepository,
        hostId: String,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        flow = repository.tasks.scheduleCatalogFlow
        refresh = { runCatching { repository.tasks.refreshScheduleCatalog(hostId) } }
        scope.launch { refresh?.invoke() }
        canceller = { schedule ->
            scope.launch {
                runCatching {
                    repository.tasks.deleteSchedule(hostId, schedule.sessionId, schedule.id)
                }
            }
        }
    }

    private var canceller: ((ScheduleInfo) -> Unit)? = null

    fun cancel(schedule: ScheduleInfo) {
        canceller?.invoke(schedule)
    }
}


