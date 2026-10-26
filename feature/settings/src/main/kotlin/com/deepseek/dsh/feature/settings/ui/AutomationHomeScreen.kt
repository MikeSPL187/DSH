package com.deepseek.dsh.feature.settings.ui

import androidx.compose.ui.graphics.compositeOver
import com.deepseek.dsh.core.designsystem.kit.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import com.deepseek.dsh.core.designsystem.pressScale
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshSwitch
import com.deepseek.dsh.feature.settings.R
import com.deepseek.dsh.feature.settings.logic.AutoFreq
import com.deepseek.dsh.feature.settings.logic.AutoRun
import com.deepseek.dsh.feature.settings.logic.Automation
import com.deepseek.dsh.feature.settings.logic.AutomationStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** Действия «Автоматизации». */
interface AutomationActions {
    fun save(a: Automation) {}
    fun delete(id: String) {}
    fun runNow(id: String) {}
    fun openSession(sessionId: String) {}
    /** Отменить задачу, которую агент сам поставил в расписание Host. */
    fun cancelAgentTask(id: String) {}
}

/** Задача из расписания Host (`schedule/catalog`): её создаёт агент в чате. */
data class AgentTask(val id: String, val title: String, val whenText: String, val sessionId: String?)

private object NoAutoActions : AutomationActions

private sealed interface AuPage {
    data object List : AuPage
    data object New : AuPage
    data class Edit(val id: String) : AuPage
}

/** Проект, в котором можно запускать автоматизацию. */
data class AutoProject(val name: String, val path: String)

/**
 * «Автоматизация» по `#screen-automation` прототипа: ближайший запуск с обратным отсчётом,
 * список с переключателями, шаблоны, редактор (когда, условия, права, уведомления,
 * проект и модель), «Запустить сейчас», история, «Дублировать» и «Удалить».
 */
@Composable
fun AutomationHomeScreen(
    items: List<Automation>,
    onClose: () -> Unit,
    actions: AutomationActions = NoAutoActions,
    agentTasks: List<AgentTask> = emptyList(),
    projects: List<AutoProject> = emptyList(),
    models: List<String> = emptyList(),
    reasoningLevels: List<String> = emptyList(),
    defaultModel: Pair<String, Int> = "" to 0,
    now: Long? = null,
    initialEdit: String? = null,
    initialTemplate: Int? = null,
    toast: ToastState = rememberToast(),
) {
    val templates = templates()
    val stack = remember { PageStack<AuPage>(AuPage.List).also { if (initialEdit != null) it.push(AuPage.Edit(initialEdit)) } }
    var draft by remember { mutableStateOf<Automation?>(null) }
    LaunchedEffect(initialTemplate) {
        initialTemplate?.let { t -> draft = fromTemplate(templates[t], t, projects, defaultModel); stack.push(AuPage.New) }
    }
    var clock by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    LaunchedEffect(now) { if (now == null) while (true) { delay(30_000); clock = System.currentTimeMillis() } }
    var sheet by remember { mutableStateOf<String?>(null) }
    var rise by remember { mutableStateOf(true) }
    var fresh by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { delay(900); rise = false }
    val active = items.count { it.on }
    val savedMsg = stringResource(R.string.au_saved)
    val whenFmt = whenText()

    fun current(): Automation? = when (val t = stack.top) { AuPage.New -> draft; is AuPage.Edit -> items.find { it.id == t.id }; else -> null }
    fun update(a: Automation) { if (stack.top == AuPage.New) draft = a else actions.save(a) }

    DshStackScreen(
        stack = stack,
        onClose = onClose,
        sheetOpen = sheet != null,
        bar = { p, back ->
            val a = current()
            val (title, sub) = when (p) {
                AuPage.List -> stringResource(R.string.au_title) to (pluralStringResource(R.plurals.au_active, active, active) + " · " + stringResource(R.string.au_without_you))
                AuPage.New -> stringResource(R.string.au_new) to (projects.firstOrNull { it.path == a?.project }?.name ?: projects.firstOrNull()?.name)
                is AuPage.Edit -> (a?.name ?: "") to a?.let { x -> AutomationStore.nextRun(x, clock)?.let { stringResource(R.string.au_next_at, nextLabel(it, clock)) } ?: stringResource(R.string.au_off) }
            }
            SBar(title, sub, back, backDescription = stringResource(R.string.st_back)) {
                when (p) {
                    AuPage.List -> IconBtn(DshGlyphs.Plus, stringResource(R.string.au_new)) { draft = fromTemplate(templates.last(), templates.lastIndex, projects, defaultModel); stack.push(AuPage.New) }
                    AuPage.New -> {
                        val ok = draft?.let { it.name.isNotBlank() && it.prompt.isNotBlank() } == true
                        KBtn(
                            stringResource(R.string.au_done), {
                                val d = draft ?: return@KBtn
                                actions.save(d); fresh = d.id; draft = null
                                stack.resetTo(AuPage.List)
                                toast.show(savedMsg.format(d.name, whenFmt(d).lowercase()))
                            },
                            Modifier.padding(end = 6.dp), kind = BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 14.dp, enabled = ok,
                        )
                    }
                    else -> Spacer(Modifier.size(44.dp))
                }
            }
        },
        overlay = {
            ToastHost(toast)
            KSheet(sheet == "model", onDismiss = { sheet = null }) {
                val a = current()
                if (a != null) ModelPickSheet(stringResource(R.string.au_model), models, a.model, reasoningLevels, a.reasoning) { m, r -> update(a.copy(model = m, reasoning = r)); sheet = null }
            }
            KSheet(sheet == "project", onDismiss = { sheet = null }) {
                val a = current()
                SheetTitle(stringResource(R.string.au_project))
                Gl(Modifier.selectableGroup()) { projects.forEach { pr -> row { Rad(pr.name, pr.path, a?.project == pr.path) { a?.let { update(it.copy(project = pr.path)) }; sheet = null } } } }
            }
        },
    ) { p ->
        CompositionLocalProvider(LocalRise provides (rise && p == AuPage.List)) {
            when (p) {
                AuPage.List -> AutoList(items, agentTasks, clock, templates, actions, toast, fresh,
                    open = { stack.push(AuPage.Edit(it)) },
                    newFrom = { i -> draft = fromTemplate(templates[i], i, projects, defaultModel); stack.push(AuPage.New) })
                AuPage.New -> draft?.let { d -> AutoEdit(d, true, projects, clock, actions, toast, onChange = { draft = it }, onSheet = { sheet = it }, onDuplicate = {}, onDeleted = {}) }
                is AuPage.Edit -> items.find { it.id == p.id }?.let { a ->
                    AutoEdit(
                        a, false, projects, clock, actions, toast, onChange = actions::save, onSheet = { sheet = it },
                        onDuplicate = { copy -> draft = copy; stack.replaceTop(AuPage.New) },
                        onDeleted = { stack.pop() },
                    )
                }
            }
        }
    }
}

// ---------- Шаблоны ----------

private data class Tpl(val icon: String, val tone: String, val name: String, val sub: String, val freq: AutoFreq, val time: String, val perm: String, val notify: String, val charge: Boolean, val prompt: String)

@Composable
private fun templates(): List<Tpl> {
    val n = stringArrayResource(R.array.au_tpl_names)
    val s = stringArrayResource(R.array.au_tpl_subs)
    val p = stringArrayResource(R.array.au_tpl_prompts)
    return listOf(
        Tpl("msg", "b", n[0], s[0], AutoFreq.Work, "09:00", "read", "always", false, p[0]),
        Tpl("check", "g", n[1], s[1], AutoFreq.Daily, "02:00", "read", "err", true, p[1]),
        Tpl("shield", "o", n[2], s[2], AutoFreq.Week, "10:00", "read", "changes", false, p[2]),
        Tpl("flow", "", n[3], s[3], AutoFreq.Daily, "09:00", "read", "err", false, ""),
    )
}

private fun fromTemplate(t: Tpl, index: Int, projects: List<AutoProject>, model: Pair<String, Int>) = Automation(
    id = "au" + System.currentTimeMillis(), name = if (index == 3) "" else t.name, icon = t.icon, tone = t.tone, freq = t.freq, time = t.time,
    days = if (t.freq == AutoFreq.Work) listOf(0, 1, 2, 3, 4) else listOf(0), charge = t.charge, perm = t.perm, notify = t.notify,
    project = projects.firstOrNull()?.path.orEmpty(), model = model.first, reasoning = model.second, prompt = t.prompt,
)

// ---------- Подписи ----------

/** «Каждый день в 03:00», «По будням в 08:45», «По понедельникам в 09:00», «Каждый час». */
@Composable
private fun whenText(): (Automation) -> String {
    val hour = stringResource(R.string.au_when_hour)
    val daily = stringResource(R.string.au_when_daily)
    val work = stringResource(R.string.au_when_work)
    val week = stringArrayResource(R.array.au_when_week)
    return { a ->
        when (a.freq) {
            AutoFreq.Hour -> hour
            AutoFreq.Daily -> daily.format(a.time)
            AutoFreq.Work -> work.format(a.time)
            AutoFreq.Week -> week[(a.days.firstOrNull() ?: 0).coerceIn(0, 6)].format(a.time)
        }
    }
}

@Composable
private fun whenShort(a: Automation): String {
    val days = stringArrayResource(R.array.au_days)
    return when (a.freq) {
        AutoFreq.Hour -> stringResource(R.string.au_when_hour)
        AutoFreq.Daily -> stringResource(R.string.au_short_daily, a.time)
        AutoFreq.Work -> stringResource(R.string.au_short_work, a.time)
        AutoFreq.Week -> days[(a.days.firstOrNull() ?: 0).coerceIn(0, 6)] + " " + a.time
    }
}

@Composable
private fun conds(a: Automation, short: Boolean): String = listOfNotNull(
    if (a.charge) stringResource(if (short) R.string.au_c_charge_s else R.string.au_c_charge) else null,
    if (a.wifi) stringResource(if (short) R.string.au_c_wifi_s else R.string.au_c_wifi) else null,
).joinToString(", ")

/** «завтра в 03:00», «пн в 08:45», «сегодня в 15:00». */
@Composable
private fun nextLabel(at: Long, now: Long, short: Boolean = false): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = "%02d:%02d".format(d.hour, d.minute)
    val day = when (d.toLocalDate()) {
        today -> stringResource(R.string.au_today)
        today.plusDays(1) -> stringResource(R.string.au_tomorrow)
        else -> stringArrayResource(R.array.au_days)[d.dayOfWeek.value - 1].lowercase()
    }
    return stringResource(if (short) R.string.au_day_time else R.string.au_day_at, day, time)
}

@Composable
private fun leftLabel(at: Long, now: Long): String {
    val m = ((at - now) / 60_000).coerceAtLeast(1)
    return if (m >= 60) stringResource(R.string.au_in_hm, m / 60, m % 60) else stringResource(R.string.au_in_m, m)
}

@Composable
private fun runDay(at: Long, now: Long, withTime: Boolean): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d.toLocalDate()) {
        today -> stringResource(R.string.au_today_cap)
        today.minusDays(1) -> stringResource(R.string.au_yesterday_cap)
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()).format(d).trimEnd('.')
    }
    return if (withTime) "$day, %02d:%02d".format(d.hour, d.minute) else day
}

@Composable
private fun duration(ms: Long?): String = when {
    ms == null -> "—"
    ms < 60_000 -> stringResource(R.string.au_dur_s, (ms / 1000).coerceAtLeast(1))
    else -> if ((ms / 1000) % 60 == 0L) stringResource(R.string.au_dur_m, ms / 60_000) else stringResource(R.string.au_dur_ms, ms / 60_000, (ms / 1000) % 60)
}

// ---------- Список ----------

@Composable
private fun AutoIc(icon: String, tone: String, off: Boolean = false) {
    val c = Dsh.colors
    val blue = Color(0xFF2F7DE1)
    val (bg, fg) = when (tone) {
        "g" -> c.okSoft.compositeOver(c.s1) to c.ok
        "o" -> c.warnSoft.compositeOver(c.s1) to c.warn
        "b" -> mix(blue, 0.14f, c.s1) to mix(blue, 0.65f, c.t1)
        else -> c.accentSoft.compositeOver(c.s1) to c.accent
    }
    val gray = { x: Color -> val l = 0.2126f * x.red + 0.7152f * x.green + 0.0722f * x.blue; Color(l, l, l, x.alpha) }
    Box(
        Modifier.size(40.dp).graphicsLayer { alpha = if (off) 0.55f else 1f }.clip(RoundedCornerShape(12.dp)).background(if (off) gray(bg) else bg),
        contentAlignment = Alignment.Center,
    ) { Icon(DshGlyphs.get(icon), null, tint = if (off) gray(fg) else fg, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun AutoList(
    items: List<Automation>,
    agentTasks: List<AgentTask>,
    now: Long,
    templates: List<Tpl>,
    a: AutomationActions,
    toast: ToastState,
    fresh: String?,
    open: (String) -> Unit,
    newFrom: (Int) -> Unit,
) {
    val c = Dsh.colors
    val next = items.mapNotNull { x -> AutomationStore.nextRun(x, now)?.let { x to it } }.minByOrNull { it.second }
    FsView {
        if (next != null) {
            val (x, at) = next
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier.fillMaxWidth().rise(0).clip(shape).background(accentCardBrush(0.35f)).border(1.dp, mix(c.accent, 0.22f, c.line), shape)
                    .rowClick { open(x.id) }.padding(17.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Clock, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.au_next), style = small(12.5.sp, 16.sp), color = c.t2)
                    Text(x.name, style = display(18.sp, 23.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
                    Text(nextLabel(at, now) + " · " + leftLabel(at, now), style = small(13.sp, 17.sp, FontWeight.Medium), color = mix(c.accent, 0.8f, c.t1))
                }
                Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
            }
        }
        IconNote(DshGlyphs.Shield, stringResource(R.string.au_host_note), Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp).rise(1))
        FsSec(stringResource(R.string.au_mine), Modifier.rise(2), count = items.size.toString())
        if (items.isEmpty()) FsEmpty(stringResource(R.string.au_empty))
        else Gl(Modifier.rise(3)) { items.forEach { x -> row { AutoRow(x, now, x.id == fresh, a, toast) { open(x.id) } } } }
        if (agentTasks.isNotEmpty()) {
            FsSec(stringResource(R.string.au_agent_tasks), count = agentTasks.size.toString())
            val cancelled = stringResource(R.string.au_cancelled)
            Gl {
                agentTasks.forEach { t ->
                    row {
                        Gr(t.title.ifBlank { "—" }, sub = t.whenText, leading = { Gic(DshGlyphs.Clock) }, onClick = t.sessionId?.let { sid -> { a.openSession(sid) } }) {
                            KBtn(stringResource(R.string.au_cancel), { a.cancelAgentTask(t.id); toast.show(cancelled) }, height = 40.dp, radius = 10.dp, fontSize = 13.5.sp, horizontal = 12.dp, ink = c.danger)
                        }
                    }
                }
            }
            SubNote(stringResource(R.string.au_agent_tasks_note))
        }
        FsSec(stringResource(R.string.au_from_tpl), Modifier.rise(4))
        Column(Modifier.rise(5), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            templates.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    pair.forEachIndexed { k, t ->
                        val shape = RoundedCornerShape(18.dp)
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 104.dp)
                                .pressScale(interaction, 0.97f)
                                .clip(shape).background(c.s1).border(1.dp, c.line, shape)
                                .clickable(interaction, ripple(), role = Role.Button) { newFrom(row * 2 + k) }.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AutoIc(t.icon, t.tone)
                            Text(t.name, style = body(14.5.sp, 19.sp, FontWeight.Medium), color = c.t1)
                            Text(t.sub, style = small(12.sp, 16.sp), color = c.t3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoRow(x: Automation, now: Long, fresh: Boolean, a: AutomationActions, toast: ToastState, onClick: () -> Unit) {
    val c = Dsh.colors
    val bg = remember { androidx.compose.animation.Animatable(if (fresh) c.accentSoft else Color.Transparent) }
    LaunchedEffect(fresh) { if (fresh) { bg.snapTo(c.accentSoft); bg.animateTo(Color.Transparent, tween(1_600)) } }
    val onMsg = stringResource(R.string.au_turned_on, x.name)
    val offMsg = stringResource(R.string.au_turned_off, x.name)
    val last = x.history.firstOrNull { it.status != "run" }
    val next = AutomationStore.nextRun(x, now)
    Row(
        Modifier.fillMaxWidth().background(bg.value).heightIn(min = 72.dp).rowClick(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AutoIc(x.icon, x.tone, off = !x.on)
        Column(Modifier.weight(1f)) {
            Text(x.name, style = body(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cs = conds(x, short = true)
            Text(whenShort(x) + if (cs.isNotEmpty()) " · $cs" else "", style = small(12.5.sp, 16.sp), color = c.t3)
            val nextText = if (next != null) stringResource(R.string.au_then, nextLabel(next, now, short = true)) else stringResource(R.string.au_off_lc)
            Text(
                buildAnnotatedString {
                    if (last != null) {
                        val (mark, col) = when (last.status) { "ok" -> "✓" to c.ok; "err" -> "✕" to c.danger; else -> "↷" to c.warn }
                        withStyle(SpanStyle(color = col)) { append("$mark " + runDay(last.at, now, false).lowercase()) }
                        append(" · ")
                    }
                    append(nextText)
                },
                style = small(12.5.sp, 16.sp), color = c.t3,
            )
        }
        Tsw(x.on, { on -> a.save(x.copy(on = on)); toast.show(if (on) onMsg else offMsg) }, stringResource(R.string.au_enable, x.name))
    }
}

// ---------- Редактор ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoEdit(
    x: Automation,
    isNew: Boolean,
    projects: List<AutoProject>,
    now: Long,
    a: AutomationActions,
    toast: ToastState,
    onChange: (Automation) -> Unit,
    onSheet: (String) -> Unit,
    onDuplicate: (Automation) -> Unit,
    onDeleted: () -> Unit,
) {
    val c = Dsh.colors
    val arm = rememberArm()
    val whenFmt = whenText()
    val running = x.history.firstOrNull()?.status == "run"
    val doneMsg = stringResource(R.string.au_deleted, x.name)
    val copySuffix = stringResource(R.string.au_copy_suffix)
    FsView {
        if (isNew) {
            Input(x.name, { onChange(x.copy(name = it.take(40))) }, stringResource(R.string.au_name_ph), single = true, Modifier.padding(bottom = 0.dp))
        }
        GLabel(stringResource(R.string.au_what))
        Input(x.prompt, { onChange(x.copy(prompt = it)) }, stringResource(R.string.au_prompt_ph), single = false)
        val hints = stringArrayResource(R.array.au_hints)
        val adds = stringArrayResource(R.array.au_hint_adds)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hints.forEachIndexed { i, h ->
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.s2).rowClick { onChange(x.copy(prompt = (x.prompt.trimEnd() + adds[i]).trim())) }.padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(h, style = body(13.sp, 17.sp), color = c.t2) }
            }
        }
        GLabel(stringResource(R.string.au_when))
        val freqs = listOf(AutoFreq.Daily, AutoFreq.Work, AutoFreq.Week, AutoFreq.Hour)
        Segm(
            stringArrayResource(R.array.au_freqs).toList(), freqs.indexOf(x.freq),
            { i ->
                val f = freqs[i]
                onChange(x.copy(freq = f, days = when (f) { AutoFreq.Work -> listOf(0, 1, 2, 3, 4); AutoFreq.Week -> if (x.days.size == 1) x.days else listOf(0); else -> x.days }))
            },
            fontSize = 13.sp,
        )
        if (x.freq == AutoFreq.Week) {
            val days = stringArrayResource(R.array.au_days)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { i, d ->
                    val on = x.days.firstOrNull() == i
                    val bg by animateColorAsState(if (on) c.accent else c.s2, tween(200), label = "day")
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(bg).selectable(on, role = Role.RadioButton) { onChange(x.copy(days = listOf(i))) },
                        contentAlignment = Alignment.Center,
                    ) { Text(d, style = small(12.5.sp, 16.sp, FontWeight.Medium), color = if (on) c.onAccent else c.t2) }
                }
            }
        }
        if (x.freq != AutoFreq.Hour) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.au_time), style = body(14.5.sp, 19.sp), color = c.t2, modifier = Modifier.weight(1f))
                TimePill(x.time) { onChange(x.copy(time = it)) }
            }
        }
        val cs = conds(x, short = false)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.t1, fontWeight = FontWeight.Medium)) { append(whenFmt(x)) }
                if (cs.isNotEmpty()) append(", $cs")
            },
            style = small(13.sp, 18.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
        )
        Gl(Modifier.padding(top = 12.dp)) {
            row { Gr(stringResource(R.string.au_charge), sub = stringResource(R.string.au_charge_sub), leading = { Gic(DshGlyphs.Bolt) }, onClick = { onChange(x.copy(charge = !x.charge)) }) { DshSwitch(x.charge, { onChange(x.copy(charge = it)) }, Modifier.padding(start = 0.dp), contentDescription = stringResource(R.string.au_charge)) } }
            row { Gr(stringResource(R.string.au_wifi), sub = stringResource(R.string.au_wifi_sub), leading = { Gic(DshGlyphs.Globe) }, onClick = { onChange(x.copy(wifi = !x.wifi)) }) { DshSwitch(x.wifi, { onChange(x.copy(wifi = it)) }, contentDescription = stringResource(R.string.au_wifi)) } }
        }
        GLabel(stringResource(R.string.au_may))
        Gl(Modifier.selectableGroup()) {
            row { Rad(stringResource(R.string.au_p_read), stringResource(R.string.au_p_read_sub), x.perm == "read") { onChange(x.copy(perm = "read")) } }
            row { Rad(stringResource(R.string.au_p_edit), stringResource(R.string.au_p_edit_sub), x.perm == "edit") { onChange(x.copy(perm = "edit")) } }
            row { RadNever(stringResource(R.string.au_p_never), stringResource(R.string.au_p_never_sub)) }
        }
        GLabel(stringResource(R.string.au_notify))
        val notes = listOf("always", "err", "changes")
        Segm(stringArrayResource(R.array.au_notify).toList(), notes.indexOf(x.notify), { onChange(x.copy(notify = notes[it])) })
        GLabel(stringResource(R.string.au_where))
        Gl {
            row { Gr(stringResource(R.string.au_project), leading = { Gic(DshGlyphs.Folder) }, value = projects.firstOrNull { it.path == x.project }?.name ?: x.project.substringAfterLast('/').ifBlank { "—" }, chevron = true, onClick = { onSheet("project") }) }
            row { Gr(stringResource(R.string.au_model), leading = { Gic(DshGlyphs.Cpu) }, value = x.model.ifBlank { "—" }, chevron = true, onClick = { onSheet("model") }) }
        }
        if (!isNew) {
            KBtn(
                stringResource(if (running) R.string.au_running else R.string.au_run_now), { a.runNow(x.id) },
                Modifier.fillMaxWidth().padding(top = 20.dp), kind = BtnKind.Primary, icon = DshGlyphs.Play, height = 50.dp, radius = 16.dp, busy = running,
            )
            FsSec(stringResource(R.string.au_history), count = x.history.size.toString())
            if (x.history.isEmpty()) {
                Gl { row { Gr(stringResource(R.string.au_history_empty), sub = stringResource(R.string.au_history_empty_sub)) } }
            } else {
                Gl {
                    x.history.forEach { r ->
                        row { HistoryRow(r, now) { r.sessionId?.let(a::openSession) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KBtn(stringResource(R.string.au_duplicate), { onDuplicate(x.copy(id = "au" + System.currentTimeMillis(), name = (x.name + copySuffix).take(40), history = emptyList(), createdAt = System.currentTimeMillis())) }, Modifier.weight(1f), kind = BtnKind.Surface, icon = DshGlyphs.Copy, height = 46.dp, fontSize = 14.sp, horizontal = 6.dp)
                KBtn(
                    stringResource(if (arm.armed) R.string.au_delete_arm else R.string.au_delete),
                    { arm.tap { a.delete(x.id); toast.show(doneMsg); onDeleted() } },
                    Modifier.weight(1f), kind = BtnKind.Surface, icon = DshGlyphs.Trash, height = 46.dp, fontSize = 14.sp, ink = c.danger, horizontal = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(r: AutoRun, now: Long, onClick: () -> Unit) {
    val c = Dsh.colors
    val (ic, tone) = when (r.status) { "ok" -> DshGlyphs.Check to Tone.Ok; "err" -> DshGlyphs.X to Tone.Red; "skip" -> DshGlyphs.Redo to Tone.Warn; else -> DshGlyphs.Clock to Tone.Plain }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).rowClick(enabled = r.sessionId != null, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Gic(ic, tone)
        Column(Modifier.weight(1f)) {
            Text(if (r.status == "run") stringResource(R.string.au_now) else runDay(r.at, now, true), style = body(14.5.sp, 19.sp), color = c.t1)
            Text(r.result, style = small(), color = if (r.status == "err") c.danger else c.t3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (r.status == "run") "…" else duration(r.durationMs), style = small(12.5.sp, 16.sp), color = c.t3)
    }
}

/** Поле `.au-name` / `.au-prompt`: рамка 1,5, при фокусе — акцент и мягкое кольцо. */
@Composable
private fun Input(value: String, onChange: (String) -> Unit, placeholder: String, single: Boolean, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) c.accent else c.line, tween(150), label = "in")
    val shape = RoundedCornerShape(16.dp)
    val style = if (single) display(19.sp, 24.sp) else body(15.sp, 22.5.sp)
    Box(
        modifier.fillMaxWidth()
            .then(if (focused) Modifier.border(4.dp, c.accentSoft, RoundedCornerShape(19.dp)).padding(0.dp) else Modifier)
            .clip(shape).background(c.s1).border(1.5.dp, border, shape)
            .heightIn(min = if (single) 52.dp else 104.dp)
            .padding(horizontal = 14.dp, vertical = if (single) 0.dp else 12.dp),
        contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = c.t3)
        BasicTextField(
            value, onChange, singleLine = single, textStyle = style.copy(color = c.t1), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
        )
    }
}

/** Время `.au-tp`: часы и минуты (шаг 15) — нажатие открывает список. */
@Composable
private fun TimePill(time: String, onChange: (String) -> Unit) {
    val c = Dsh.colors
    val h = time.take(2).toIntOrNull() ?: 9
    val m = time.takeLast(2).toIntOrNull() ?: 0
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.s2).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TimePart("%02d".format(h), (0..23).map { "%02d".format(it) }, stringResource(R.string.au_hours)) { onChange("$it:%02d".format(m)) }
        Text(":", style = body(17.sp, 22.sp, FontWeight.SemiBold), color = c.t3)
        TimePart("%02d".format(m), listOf("00", "15", "30", "45"), stringResource(R.string.au_minutes)) { onChange("%02d:$it".format(h)) }
    }
}

@Composable
private fun TimePart(value: String, options: List<String>, description: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.height(40.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).rowClick { open = true }.semantics { contentDescription = "$description $value" }.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text(value, style = body(17.sp, 22.sp, FontWeight.SemiBold), color = Dsh.colors.t1) }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 280.dp).background(Dsh.colors.s1)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, style = body(16.sp, 20.sp, if (o == value) FontWeight.SemiBold else FontWeight.Normal), color = if (o == value) Dsh.colors.accent else Dsh.colors.t1) },
                    onClick = { open = false; onPick(o) },
                )
            }
        }
    }
}

/** Лист выбора модели (как «Модель по умолчанию» в «Настройках»). */
@Composable
internal fun ModelPickSheet(title: String, models: List<String>, current: String, levels: List<String>, level: Int, onPick: (String, Int) -> Unit) {
    var m by remember(current) { mutableStateOf(current) }
    var r by remember(level) { mutableStateOf(level) }
    SheetTitle(title)
    if (models.isEmpty()) FsEmpty(stringResource(R.string.st_models_empty))
    else Gl(Modifier.selectableGroup()) { models.forEach { name -> row { Rad(name, null, name == m) { m = name } } } }
    if (levels.isNotEmpty()) {
        GLabel(stringResource(R.string.st_reasoning))
        Segm(levels, r, { r = it })
    }
    KBtn(stringResource(R.string.st_done), { onPick(m, r) }, Modifier.fillMaxWidth().padding(top = 16.dp), kind = BtnKind.Primary, height = 50.dp, radius = 16.dp, enabled = m.isNotBlank())
}
