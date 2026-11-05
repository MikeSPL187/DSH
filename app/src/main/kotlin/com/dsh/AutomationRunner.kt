package com.dsh

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.dsh.core.model.MessageRole
import com.dsh.feature.settings.HostPermissionPreset
import com.dsh.feature.settings.logic.AutoRun
import com.dsh.feature.settings.logic.Automation
import com.dsh.feature.settings.logic.AutomationStore
import com.dsh.feature.settings.logic.DshUserPrefs
import com.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.dsh.core.model.ScheduleInfo

/**
 * Расписание автоматизаций: у каждой включённой — одно задание JobScheduler на время
 * следующего запуска с её условиями («только при зарядке», «только по Wi‑Fi»).
 * Если условие не выполнено, Android откладывает задание; запуск позже часа отмечается
 * в истории как перенесённый.
 */
object AutomationScheduler {
    private const val EXTRA_ID = "automation_id"
    private const val EXTRA_AT = "planned_at"

    /** Номера заданий автоматизаций: 0x4155_0000…0x4155_FFFF. */
    private const val JOB_BASE = 0x4155_0000
    private const val JOB_SPAN = 0x1_0000

    /**
     * Свободный номер задания для [id]. Первый кандидат — от хэша id (у одной автоматизации он обычно
     * один и тот же), но 16 бит хэша у разных UUID совпадают: тогда берётся следующий свободный номер.
     * Раньше совпавший номер молча заменял задание другой автоматизации, и та переставала запускаться (DSH-12).
     */
    private fun freeJobId(js: JobScheduler, id: String): Int? {
        val taken = js.allPendingJobs.mapTo(HashSet()) { it.id }
        val start = id.hashCode() and 0xffff
        return (0 until JOB_SPAN).asSequence().map { JOB_BASE or ((start + it) and 0xffff) }.firstOrNull { it !in taken }
    }

    /** Задания этой автоматизации — по её id в данных задания, а не по номеру. */
    private fun jobsOf(js: JobScheduler, id: String): List<Int> =
        js.allPendingJobs.filter { (it.id and 0xffff.inv()) == JOB_BASE && it.extras.getString(EXTRA_ID) == id }.map { it.id }

    private fun prefs(context: Context) = context.getSharedPreferences("automation_scheduler", Context.MODE_PRIVATE)

    fun waitingReason(context: Context, a: Automation): String? {
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        if (a.charge && battery?.isCharging != true) return com.dsh.core.model.Tr.t("Waiting for charging", "Ожидает зарядки")
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (a.wifi && caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true)
            return com.dsh.core.model.Tr.t("Waiting for Wi-Fi", "Ожидает Wi-Fi")
        if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true)
            return com.dsh.core.model.Tr.t("Waiting for network", "Ожидает сети")
        return null
    }

    fun status(context: Context, a: Automation): String {
        if (!a.on) return com.dsh.core.model.Tr.t("Disabled", "Выключено")
        if (prefs(context).getBoolean("error/${a.id}", false))
            return com.dsh.core.model.Tr.t("Could not schedule. Edit and save to retry.", "Не удалось запланировать. Измените и сохраните для повтора.")
        val js = context.getSystemService(JobScheduler::class.java)
        val job = js?.allPendingJobs?.firstOrNull { it.extras.getString(EXTRA_ID) == a.id }
            ?: return com.dsh.core.model.Tr.t("Not scheduled", "Не запланировано")
        val at = job.extras.getLong(EXTRA_AT)
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))
        return if (System.currentTimeMillis() < at) com.dsh.core.model.Tr.t("Scheduled: ", "Запланировано: ") + time
        else waitingReason(context, a) ?: com.dsh.core.model.Tr.t("Waiting for Android", "Ожидает разрешения Android")
    }

    @Synchronized internal fun claimOccurrence(context: Context, id: String, at: Long): Boolean {
        val p = prefs(context)
        if (maxOf(p.getLong("completed/$id", Long.MIN_VALUE), p.getLong("started/$id", Long.MIN_VALUE)) >= at) return false
        return p.edit().putLong("started/$id", at).commit()
    }
    internal fun releaseOccurrence(context: Context, id: String, at: Long) {
        val p = prefs(context)
        if (p.getLong("started/$id", Long.MIN_VALUE) == at) p.edit().remove("started/$id").commit()
    }
    internal fun completeOccurrence(context: Context, id: String, at: Long) {
        prefs(context).edit().putLong("completed/$id", at).apply()
    }

    fun schedule(context: Context, a: Automation, preservePending: Boolean = false) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (js == null) { prefs(context).edit().putBoolean("error/${a.id}", true).apply(); return }
        if (preservePending && jobsOf(js, a.id).isNotEmpty()) return
        jobsOf(js, a.id).forEach(js::cancel)
        val at = AutomationStore.nextRun(a) ?: return
        val jobId = freeJobId(js, a.id) ?: run { prefs(context).edit().putBoolean("error/${a.id}", true).apply(); return }
        val extras = PersistableBundle().apply { putString(EXTRA_ID, a.id); putLong(EXTRA_AT, at) }
        val builder = JobInfo.Builder(jobId, ComponentName(context, AutomationJobService::class.java))
            .setMinimumLatency((at - System.currentTimeMillis()).coerceAtLeast(0))
            .setRequiresCharging(a.charge)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setExtras(extras)
        if (a.wifi && android.os.Build.VERSION.SDK_INT >= 28) {
            builder.setRequiredNetwork(android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
        }
        val accepted = runCatching { js.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        prefs(context).edit().putBoolean("error/${a.id}", !accepted).apply()
    }

    fun cancel(context: Context, id: String) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        jobsOf(js, id).forEach(js::cancel)
    }

    /** Пересчитать все задания (после запуска приложения и изменений). */
    fun rescheduleAll(context: Context) {
        AutomationStore.items.forEach { if (it.on) schedule(context, it, preservePending = true) else cancel(context, it.id) }
    }

    internal fun idOf(params: JobParameters): String? = params.extras.getString(EXTRA_ID)
    internal fun plannedOf(params: JobParameters): Long = params.extras.getLong(EXTRA_AT)
}

/** Задание JobScheduler: запускает автоматизацию в фоне и сразу планирует следующий запуск. */
class AutomationJobService : JobService() {
    private val jobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = AutomationScheduler.idOf(params) ?: return false
        val app = applicationContext as DshApplication
        jobs[params.jobId] = app.applicationScope.launch {
            try {
                val a = AutomationStore.get(id)
                if (a == null || !a.on) { jobFinished(params, false); return@launch }
                if (AutomationScheduler.waitingReason(app, a) != null) { jobFinished(params, true); return@launch }
                val accepted = AutomationRunner.run(app, id, AutomationScheduler.plannedOf(params))
                jobFinished(params, !accepted)
                if (accepted) AutomationStore.get(id)?.let { AutomationScheduler.schedule(app, it) }
            } finally {
                if (jobs[params.jobId] === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) jobs.remove(params.jobId)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        val id = AutomationScheduler.idOf(params) ?: return false
        return AutomationStore.get(id)?.on == true &&
            (android.os.Build.VERSION.SDK_INT < 31 || params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }
    override fun onDestroy() { jobs.values.toList().forEach { it.cancel() }; jobs.clear(); super.onDestroy() }
}

/** The persisted jobs survive reboot; wall-clock/time-zone changes need a fresh calculation. */
class AutomationTimeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        AutomationStore.items.forEach { AutomationScheduler.schedule(context, it) }
    }
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
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    internal fun ownsSession(sessionId: String?): Boolean = sessionId != null && activeSessions.containsValue(sessionId)

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

    suspend fun run(app: DshApplication, id: String, plannedAt: Long = 0): Boolean {
        val a = AutomationStore.get(id) ?: return true
        if (!claim(id, app.getString(R.string.au_run_interrupted))) return false
        try {
            if (plannedAt > 0 && !AutomationScheduler.claimOccurrence(app, id, plannedAt)) return true
            runClaimed(app, id, a, plannedAt)
            if (plannedAt > 0) AutomationScheduler.completeOccurrence(app, id, plannedAt)
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            val sid = activeSessions[id]
            val stopped = sid == null || withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { app.dshRepository.cancelSession(LOCAL_HOST, sid) }.getOrDefault(false)
            }
            if (stopped && plannedAt > 0) AutomationScheduler.releaseOccurrence(app, id, plannedAt)
            throw cancelled
        } finally {
            activeSessions.remove(id)
            release(id)
        }
    }

    private suspend fun runClaimed(app: DshApplication, id: String, a: Automation, plannedAt: Long) {
        val started = System.currentTimeMillis()
        if (plannedAt > 0 && started - plannedAt > 60 * 60_000L) {
            AutomationStore.addRun(id, AutoRun(plannedAt, "skip", null, app.getString(R.string.au_run_postponed, conditionsText(app, a))))
        }
        AutomationStore.addRun(id, AutoRun(started, "run", null, app.getString(R.string.au_run_working)))
        val outcome = try { execute(app, a) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            AutomationStore.addRun(id, AutoRun(started, "err", System.currentTimeMillis() - started, app.getString(R.string.au_run_interrupted)))
            throw cancelled
        } catch (error: Exception) { Outcome(false, error.message ?: app.getString(R.string.au_run_failed), null, false) }
        val finished = System.currentTimeMillis()
        AutomationStore.addRun(id, AutoRun(started, if (outcome.ok) "ok" else "err", finished - started, outcome.summary, outcome.sessionId))
        notifyIfNeeded(app, a, outcome)
    }

    private data class Outcome(val ok: Boolean, val summary: String, val sessionId: String?, val changed: Boolean)

    private suspend fun execute(app: DshApplication, a: Automation): Outcome = withContext(Dispatchers.IO) {
        val repo = app.dshRepository
        val workspace = a.project.ifBlank { StorageAccess.workspaceRoot(app).absolutePath }
        // Host: разбудить и привязать клиента, если приложение закрыто.
        val status = app.runtimeManager.getStatus().takeIf { it.phase == RuntimePhase.RUNNING && it.port != null }
            ?: app.runtimeManager.start(workspace).getOrNull()
            ?: return@withContext Outcome(false, app.getString(R.string.au_run_no_host), null, false)
        if (status.port != null && !app.bindLocalHost(status.port!!, status.token)) {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_host), null, false)
        }
        repo.subscribeRemoteEvents(LOCAL_HOST)

        val session = repo.createSession(LOCAL_HOST, a.name, workspace).getOrElse {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_session, it.message ?: ""), null, false)
        }
        val sid = session.sessionId
        activeSessions[a.id] = sid
        // Права — до промпта, и без запасного пути: не удалось — сессия не запускается. Иначе автоматизация
        // «только читать» в фоне, без пользователя, работала бы с правами сессии по умолчанию (вплоть до полного доступа).
        val preset = presetFor(a.perm)
        if (runCatching { repo.selectPermissionPreset(LOCAL_HOST, sid, preset) }.getOrDefault(false) != true) {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_perm), sid, false)
        }
        repo.hostSettings.refreshModelCatalog(LOCAL_HOST)
        val selection = modelFor(app, a) ?: return@withContext Outcome(false, app.getString(R.string.au_run_no_model), sid, false)
        if (repo.selectModel(LOCAL_HOST, sid, selection.first, selection.second, selection.third).isFailure) {
            return@withContext Outcome(false, app.getString(R.string.au_run_no_model), sid, false)
        }

        val prompt = buildString {
            append(a.prompt.trim())
            if (a.perm == "read") append("\n\n").append(app.getString(R.string.au_run_read_only))
        }
        repo.sendMessage(LOCAL_HOST, sid, prompt).onFailure {
            return@withContext Outcome(false, app.getString(R.string.au_run_send_failed, it.message.orEmpty()), sid, false)
        }
        val key = com.dsh.core.data.SessionKey(LOCAL_HOST, sid)
        val settled = withTimeoutOrNull(TURN_TIMEOUT_MS) {
            repo.runStatesFlow.first { it[key]?.terminal == true }[key]
        }
        val done = settled != null
        if (!done || settled?.status != com.dsh.core.model.RunStatus.SUCCEEDED) runCatching { repo.cancelSession(LOCAL_HOST, sid) }
        if (settled != null && settled.status != com.dsh.core.model.RunStatus.SUCCEEDED) {
            return@withContext Outcome(false, settled.message ?: app.getString(R.string.au_run_failed), sid, false)
        }
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
        val resolved = resolveAutomationModel(a, app.dshRepository.hostSettings.modelCatalogFlow.value, AutomationStore.defaultModel) ?: return null
        return Triple(resolved.modelId!!, resolved.providerId, resolved.reasoningEffort)
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
    projects: List<com.dsh.feature.settings.ui.AutoProject>,
    models: List<String>,
    hostTasks: List<com.dsh.core.model.ScheduleInfo>,
    onOpenSession: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as DshApplication
    val toast = com.dsh.core.designsystem.kit.rememberToast()
    val reasoning = com.dsh.feature.chat.ReasoningLevel.entries.map { context.getString(it.labelResId) }
    androidx.compose.runtime.LaunchedEffect(Unit) { HostSchedules.refresh?.invoke() }
    val modelCatalog by app.dshRepository.hostSettings.modelCatalogFlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(modelCatalog, AutomationStore.defaultModel) {
        AutomationStore.items.filter { it.model.isBlank() }.forEach { legacy ->
            resolveAutomationModel(legacy, modelCatalog, AutomationStore.defaultModel)?.let(AutomationStore::upsert)
        }
    }
    var scheduleTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); scheduleTick++ } }
    val actions = object : com.dsh.feature.settings.ui.AutomationActions {
        override fun scheduleStatus(id: String): String {
            @Suppress("UNUSED_VARIABLE") val tick = scheduleTick
            return AutomationStore.get(id)?.let { AutomationScheduler.status(context, it) }.orEmpty()
        }
        override fun save(a: Automation) {
            val selected = resolveAutomationModel(a, modelCatalog, AutomationStore.defaultModel) ?: a
            AutomationStore.upsert(selected)
            AutomationScheduler.schedule(context, selected)
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
        com.dsh.feature.settings.ui.AgentTask(s.id, s.title.ifBlank { s.prompt.take(60) }, scheduleText(context, s), s.sessionId.ifBlank { null })
    }
    com.dsh.feature.settings.ui.AutomationHomeScreen(
        items = AutomationStore.items, onClose = onClose, actions = actions, agentTasks = tasks, projects = projects,
        models = models.map { it.substringBefore(" · ") }.distinct(), reasoningLevels = reasoning,
        defaultModel = AutomationStore.defaultModel, toast = toast,
    )
}

private fun scheduleText(context: Context, s: com.dsh.core.model.ScheduleInfo): String {
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
        repository: com.dsh.core.data.DshRepository,
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


