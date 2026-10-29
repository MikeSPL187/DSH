package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.data.AgentMemory
import com.dsh.core.data.MemoryFact
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
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

/** Факт памяти для экрана: [date] — «12 окт». */
@Immutable
data class MemoryRow(val id: Long, val text: String, val date: String, val fromChat: Boolean)

/** «Память»: включена ли и что в ней; [bytes] — сколько займёт в контексте агента. */
@Immutable
data class MemoryUi(val on: Boolean = true, val rows: List<MemoryRow> = emptyList()) {
    val bytes: Int get() = rows.sumOf { ("- " + it.text + "\n").toByteArray().size }
}

/** Причина отказа для листа факта: те же правила, что при сохранении ([AgentMemory.issue]). */
internal fun memoryIssue(ui: MemoryUi, text: String, except: Long?): MemoryFieldIssue? {
    val facts = ui.rows.map { MemoryFact(it.id, it.text, 0) }
    val why = AgentMemory.issue(text, facts, except)
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
 * Страница `mem`: «Помнить между сессиями», группа «Что агент знает о вас» (факт в две строки, дата и откуда)
 * с «Добавить факт», пустое состояние `.in-empty`, пояснение про файл и секреты, «Очистить память» в два нажатия.
 */
@Composable
internal fun MemoryPage(ui: MemoryUi, a: SettingsActions, toast: ToastState, onOpen: (Long?) -> Unit) {
    val c = Dsh.colors
    val onMsg = stringResource(R.string.st_mem_toggled_on)
    val offMsg = stringResource(R.string.st_mem_toggled_off)
    val cleared = stringResource(R.string.st_mem_cleared)
    val arm = rememberArm()
    FsView {
        Gl {
            row {
                StToggle(
                    stringResource(R.string.st_mem_on), ui.on, { on -> a.setMemoryOn(on); toast.show(if (on) onMsg else offMsg) },
                    icon = DshGlyphs.Mem, tile = Si.c3, sub = stringResource(if (ui.on) R.string.st_mem_on_sub else R.string.st_mem_off_sub),
                )
            }
        }
        if (ui.rows.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.st_mem_known).uppercase(), style = small(12.sp, 16.sp).copy(letterSpacing = 0.06.em), color = c.t3,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(pluralStringResource(R.plurals.st_mem_facts, ui.rows.size, ui.rows.size) + " · " + bytes(ui.bytes.toLong()), style = small(12.5.sp, 16.sp), color = c.t3)
            }
            Gl {
                ui.rows.forEach { m ->
                    row {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).rowClick { onOpen(m.id) }.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.text, style = body(15.sp), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    m.date + " · " + stringResource(if (m.fromChat) R.string.st_mem_from_chat else R.string.st_mem_manual),
                                    style = small(), color = c.t3, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                row { StRow(stringResource(R.string.st_mem_add), icon = DshGlyphs.Plus, tile = Si.c6, chevron = true, onClick = { onOpen(null) }) }
            }
        } else {
            EmptyMemory { onOpen(null) }
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

/** Лист факта из настроек: новый ([row] = null) или правка с удалением. */
@Composable
internal fun ColumnScope.MemorySheet(ui: MemoryUi, row: MemoryRow?, a: SettingsActions, toast: ToastState, close: () -> Unit) {
    val saved = stringResource(R.string.st_mem_saved)
    val edited = stringResource(R.string.st_mem_edited)
    val deleted = stringResource(R.string.st_mem_deleted)
    MemoryFactSheet(
        initial = row?.text.orEmpty(), isNew = row == null,
        issue = { memoryIssue(ui, it, row?.id) },
        onSave = { text -> if (a.saveMemory(row?.id, text)) { close(); toast.show(if (row == null) saved else edited) } },
        onCancel = close,
        onDelete = row?.let { r -> { a.removeMemory(r.id); close(); toast.show(deleted) } },
    )
}
