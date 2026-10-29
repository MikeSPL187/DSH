package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.data.AgentMemory
import com.dsh.core.data.MemoryFact
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.FsEmpty
import com.dsh.core.designsystem.kit.FsSearch
import com.dsh.core.designsystem.kit.FsView
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.MemoryFactSheet
import com.dsh.core.designsystem.kit.MemoryFieldIssue
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.StToggle
import com.dsh.core.designsystem.kit.SubNote
import com.dsh.core.designsystem.kit.ToastState
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.display
import com.dsh.core.designsystem.kit.rememberArm
import com.dsh.core.designsystem.kit.rowClick
import com.dsh.core.designsystem.kit.small
import com.dsh.feature.settings.R

/**
 * Факт памяти для экрана: [date] — «12 окт»; [project] — имя папки проекта (null — для всех проектов),
 * [here] — это текущий проект.
 */
@Immutable
data class MemoryRow(val id: Long, val text: String, val date: String, val fromChat: Boolean, val project: String? = null, val here: Boolean = false)

/** «Память»: включена ли, сохраняет ли агент сам, факты и имя текущего проекта (null — проекта нет). */
@Immutable
data class MemoryUi(val on: Boolean = true, val auto: Boolean = false, val rows: List<MemoryRow> = emptyList(), val projectName: String? = null)

/** Причина отказа для листа факта: те же правила, что при сохранении ([AgentMemory.issue]); [here] — в проект. */
internal fun memoryIssue(ui: MemoryUi, text: String, except: Long?, here: Boolean): MemoryFieldIssue? {
    // Область факта для проверки повтора: «*» — текущий проект, имя — другой, null — все проекты.
    fun key(r: MemoryRow) = if (r.here) "*" else r.project
    val target = if (here) "*" else if (except != null) ui.rows.firstOrNull { it.id == except }?.takeIf { it.project != null && !it.here }?.project else null
    val facts = ui.rows.map { MemoryFact(it.id, it.text, 0, project = key(it)) }
    val why = AgentMemory.issue(text, facts, except, target)
        ?: if (except == null && facts.size >= AgentMemory.MAX_FACTS) com.dsh.core.data.MemoryIssue.TOO_LONG else null
    return why?.let { MemoryFieldIssue.valueOf(it.name) }
}

/** Значение строки корня «Память»: «4 факта», «пусто» или «выключена». */
@Composable
internal fun memoryValue(ui: MemoryUi): String = when {
    !ui.on -> stringResource(R.string.st_mem_value_off)
    ui.rows.isEmpty() -> stringResource(R.string.st_mem_value_empty)
    else -> pluralStringResource(R.plurals.st_mem_facts, ui.rows.size, ui.rows.size)
}

/**
 * Страница `mem`: «Помнить между сессиями» и «Сохранять без вопроса»; поиск, если фактов больше шести;
 * группы «Для всех проектов», «Проект …» (у каждой — «Добавить факт») и «Другие проекты»; пустое состояние,
 * пояснение про файлы и секреты, «Очистить память» в два нажатия.
 *
 * @param onOpen открыть лист факта: id или null — новый; второй параметр — новый факт для текущего проекта.
 */
@Composable
internal fun MemoryPage(ui: MemoryUi, a: SettingsActions, toast: ToastState, onOpen: (Long?, Boolean) -> Unit) {
    val c = Dsh.colors
    val onMsg = stringResource(R.string.st_mem_toggled_on)
    val offMsg = stringResource(R.string.st_mem_toggled_off)
    val autoOn = stringResource(R.string.st_mem_auto_toggled_on)
    val autoOff = stringResource(R.string.st_mem_auto_toggled_off)
    val cleared = stringResource(R.string.st_mem_cleared)
    val arm = rememberArm()
    var query by rememberSaveable { mutableStateOf("") }
    FsView {
        Gl {
            row {
                StToggle(
                    stringResource(R.string.st_mem_on), ui.on, { on -> a.setMemoryOn(on); toast.show(if (on) onMsg else offMsg) },
                    icon = DshGlyphs.Mem, tile = Si.c3, sub = stringResource(if (ui.on) R.string.st_mem_on_sub else R.string.st_mem_off_sub),
                )
            }
            if (ui.on) row {
                StToggle(
                    stringResource(R.string.st_mem_auto), ui.auto, { on -> a.setMemoryAuto(on); toast.show(if (on) autoOn else autoOff) },
                    icon = DshGlyphs.get("bolt"), tile = Si.c4, sub = stringResource(if (ui.auto) R.string.st_mem_auto_on_sub else R.string.st_mem_auto_off_sub),
                )
            }
        }
        if (ui.rows.isEmpty()) {
            EmptyMemory { onOpen(null, false) }
        } else {
            if (ui.rows.size > 6) FsSearch(query, { query = it }, stringResource(R.string.st_mem_search), Modifier.padding(top = 16.dp))
            val q = query.trim().lowercase()
            val hits = if (q.isEmpty()) ui.rows else ui.rows.filter { it.text.lowercase().contains(q) }
            if (hits.isEmpty()) {
                FsEmpty(stringResource(R.string.st_nothing))
            } else {
                val adding = q.isEmpty()
                MemoryGroup(stringResource(R.string.st_mem_all), hits.filter { it.project == null }, if (adding) ({ onOpen(null, false) }) else null, onOpen)
                ui.projectName?.let { name ->
                    MemoryGroup(stringResource(R.string.st_mem_project, name), hits.filter { it.here }, if (adding) ({ onOpen(null, true) }) else null, onOpen)
                }
                MemoryGroup(stringResource(R.string.st_mem_other), hits.filter { it.project != null && !it.here }, null, onOpen, showProject = true)
            }
        }
        SubNote(stringResource(R.string.st_mem_note), Modifier.padding(horizontal = 6.dp))
        if (ui.rows.isNotEmpty()) {
            Gl(Modifier.padding(top = 16.dp)) {
                row {
                    StRow(
                        if (arm.armed) pluralStringResource(R.plurals.st_mem_clear_arm, ui.rows.size, ui.rows.size) else stringResource(R.string.st_mem_clear),
                        icon = DshGlyphs.Trash, tile = Si.c5, titleColor = c.danger,
                        onClick = { arm.tap { a.clearMemory(); toast.show(cleared) } },
                    )
                }
            }
        }
    }
}

/** Группа `.glabel.gsplit` («Для всех проектов · 3 факта») и строки фактов; [onAdd] — строка «Добавить факт». */
@Composable
private fun MemoryGroup(title: String, rows: List<MemoryRow>, onAdd: (() -> Unit)?, onOpen: (Long?, Boolean) -> Unit, showProject: Boolean = false) {
    if (rows.isEmpty() && onAdd == null) return
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = small(12.sp, 16.sp).copy(letterSpacing = 0.06.em), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        if (rows.isNotEmpty()) Text(pluralStringResource(R.plurals.st_mem_facts, rows.size, rows.size), style = small(12.5.sp, 16.sp), color = c.t3, modifier = Modifier.padding(start = 8.dp))
    }
    Gl {
        rows.forEach { m -> row { FactRow(m, showProject) { onOpen(m.id, false) } } }
        if (onAdd != null) row { StRow(stringResource(R.string.st_mem_add), icon = DshGlyphs.Plus, tile = Si.c6, chevron = true, onClick = onAdd) }
    }
}

/** `.mem-row`: факт в две строки, ниже — дата и откуда (у чужого проекта — его имя). */
@Composable
private fun FactRow(m: MemoryRow, showProject: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).rowClick(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.text, style = body(15.sp), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val where = if (showProject && m.project != null) m.project else stringResource(if (m.fromChat) R.string.st_mem_from_chat else R.string.st_mem_manual)
            Text("${m.date} · $where", style = small(), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
    }
}

/** `.in-empty` памяти: плитка 56, «Пока ничего не запомнено», как пополнить, «Добавить факт». */
@Composable
private fun EmptyMemory(onAdd: () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Si.c3), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Mem, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(R.string.st_mem_empty), style = display(19.sp, 24.sp), color = c.t1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Text(
            stringResource(R.string.st_mem_empty_hint), style = body(14.5.sp, 21.75.sp), color = c.t2, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 18.dp),
        )
        KBtn(stringResource(R.string.st_mem_add), onAdd, Modifier.fillMaxWidth(), kind = BtnKind.Primary, height = 50.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

/**
 * Лист факта из настроек: новый ([row] = null; [forProject] — сразу «Этот проект») или правка с удалением.
 * Выбор «Все проекты / проект» — если есть текущий проект или факт принадлежит проекту.
 */
@Composable
internal fun ColumnScope.MemorySheet(ui: MemoryUi, row: MemoryRow?, forProject: Boolean, a: SettingsActions, toast: ToastState, close: () -> Unit) {
    val saved = stringResource(R.string.st_mem_saved)
    val edited = stringResource(R.string.st_mem_edited)
    val deleted = stringResource(R.string.st_mem_deleted)
    // Второй вариант — текущий проект или чужой проект факта (его можно только вернуть в «Все проекты» и обратно).
    val projectLabel = when {
        row?.project != null && !row.here -> row.project
        ui.projectName != null -> stringResource(com.dsh.core.designsystem.R.string.ds_mem_scope_here)
        else -> null
    }
    val scopes = projectLabel?.let { listOf(stringResource(com.dsh.core.designsystem.R.string.ds_mem_scope_all), it) }.orEmpty()
    val initialScope = if (row?.project != null || (row == null && forProject && ui.projectName != null)) 1 else 0
    val foreign = row?.project != null && !row.here
    MemoryFactSheet(
        initial = row?.text.orEmpty(), isNew = row == null,
        issue = { text, scope -> memoryIssue(ui, text, row?.id, here = scope == 1 && !foreign) },
        onSave = { text, scope ->
            // 0 — все проекты; 1 — текущий проект, а у факта чужого проекта — тот же проект.
            val target = when { scope == 0 -> MemoryTarget.ALL; foreign -> MemoryTarget.KEEP; else -> MemoryTarget.HERE }
            if (a.saveMemory(row?.id, text, target)) { close(); toast.show(if (row == null) saved else edited) }
        },
        onCancel = close,
        onDelete = row?.let { r -> { a.removeMemory(r.id); close(); toast.show(deleted) } },
        scopes = scopes, initialScope = initialScope,
    )
}

/** Где помнить факт при сохранении: для всех проектов, в текущем проекте или там же, где был. */
enum class MemoryTarget { ALL, HERE, KEEP }
