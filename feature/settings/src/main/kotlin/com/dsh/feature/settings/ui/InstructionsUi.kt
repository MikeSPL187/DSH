package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.SubNote
import com.dsh.core.designsystem.kit.ToastState
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.display
import com.dsh.core.designsystem.kit.small
import com.dsh.feature.settings.R
import com.dsh.feature.settings.logic.AgentInstructions
import com.dsh.feature.settings.logic.InstructionScope
import kotlinx.coroutines.launch

/** Строка группы «Инструкции для агента»: имя файла, путь для подписи и вес (null — файла нет). */
data class InstructionRow(val scope: InstructionScope, val name: String, val path: String, val bytes: Long?)

/** «461 Б», «2,1 КБ», «64 КБ» — вес файлов инструкций (1 КБ = 1024 байта, как бюджет Host). */
@Composable
internal fun sizeText(bytes: Long): String = if (bytes < 1024) stringResource(R.string.st_u_b, bytes.toInt())
else stringResource(R.string.st_u_kb_f, java.text.DecimalFormat("0.#").format(bytes / 1024.0))

internal fun instructionScopeTitle(scope: InstructionScope) = when (scope) {
    InstructionScope.GLOBAL -> R.string.st_inst_global
    InstructionScope.PROJECT -> R.string.st_inst_project
    InstructionScope.LOCAL -> R.string.st_inst_local
}

/** Группа на странице «Агент»: подпись с бюджетом справа (`.glabel.gsplit`), три файла, пояснение порядка. */
@Composable
internal fun InstructionsGroup(rows: List<InstructionRow>, onOpen: (InstructionScope) -> Unit) {
    if (rows.isEmpty()) return
    val c = Dsh.colors
    val total = rows.sumOf { it.bytes ?: 0L }
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.st_inst).uppercase(), style = small(12.sp, 16.sp).copy(letterSpacing = 0.06.em), color = c.t3,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            stringResource(R.string.st_inst_budget, sizeText(total), sizeText(AgentInstructions.BUDGET)), style = small(12.5.sp, 16.sp),
            color = if (total > AgentInstructions.BUDGET) c.warn else c.t3,
        )
    }
    Gl {
        rows.forEach { r ->
            val (icon, tile) = when (r.scope) {
                InstructionScope.GLOBAL -> DshGlyphs.Globe to Si.c2
                InstructionScope.PROJECT -> DshGlyphs.Folder to Si.c1
                InstructionScope.LOCAL -> DshGlyphs.Lock to Si.c6
            }
            row {
                StRow(
                    stringResource(instructionScopeTitle(r.scope)), icon = icon, tile = tile, sub = r.path, chevron = true,
                    value = r.bytes?.let { sizeText(it) } ?: stringResource(R.string.st_inst_none), onClick = { onOpen(r.scope) },
                )
            }
        }
    }
    SubNote(stringResource(R.string.st_inst_note))
}

/**
 * Состояние редактора одного файла: что на диске ([saved], null — файла нет), черновик ([draft], null — пустое
 * состояние «Файла пока нет»), режим «Вид» и взведённое удаление. Живёт выше страницы: лист «Выйти без сохранения?»
 * и шапка «Назад» должны видеть несохранённые правки.
 */
@Stable
class InstructionEditor {
    var scope by mutableStateOf(InstructionScope.GLOBAL)
        private set
    var saved by mutableStateOf<String?>(null)
    var draft by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var preview by mutableStateOf(false)
    var deleteArmed by mutableStateOf(false)

    val dirty: Boolean get() = loaded && draft != null && draft != (saved ?: "")

    fun open(target: InstructionScope) {
        scope = target; saved = null; draft = null; loaded = false; failed = false; preview = false; deleteArmed = false
    }
}

/**
 * Страница-редактор: файла нет — «Начать с шаблона» / «Пустой файл»; есть — «Правка | Вид», поле во всю высоту,
 * внизу вес (или «не войдут общие», если вместе больше 64 КБ) и «Сохранить», под ними «Удалить файл» со вторым нажатием.
 */
@Composable
internal fun InstructionsPage(
    rows: List<InstructionRow>,
    editor: InstructionEditor,
    a: SettingsActions,
    toast: ToastState,
    markdown: (@Composable (String) -> Unit)?,
    onDeleted: () -> Unit,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val template = stringResource(R.string.st_inst_template)
    val msgSaved = stringResource(R.string.st_inst_saved)
    val msgDeleted = stringResource(R.string.st_inst_deleted)
    val msgFailed = stringResource(R.string.st_inst_failed)
    LaunchedEffect(editor.scope) {
        if (editor.loaded) return@LaunchedEffect
        val text = a.readInstruction(editor.scope)
        editor.failed = text.isFailure
        editor.saved = text.getOrNull()
        editor.draft = editor.saved
        editor.loaded = true
    }
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        when {
            !editor.loaded -> Unit
            editor.failed -> SubNote(stringResource(R.string.st_inst_too_big))
            editor.draft == null -> EmptyInstructions(
                onTemplate = { editor.draft = template },
                onBlank = { editor.draft = "" },
            )
            else -> {
                val draft = editor.draft.orEmpty()
                ModeSeg(editor.preview) { editor.preview = it; editor.deleteArmed = false }
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(20.dp)),
                ) {
                    if (editor.preview) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            if (markdown != null) markdown(draft) else Text(draft, style = body(15.sp, 23.sp), color = c.t2)
                        }
                    } else {
                        val name = rows.firstOrNull { it.scope == editor.scope }?.name.orEmpty()
                        BasicTextField(
                            draft, { editor.draft = it; editor.deleteArmed = false },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = DshFonts.mono, fontSize = 13.5.sp, lineHeight = 21.sp, color = c.t1),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).semantics { contentDescription = name },
                        )
                    }
                }
                val others = rows.filter { it.scope != editor.scope }.sumOf { it.bytes ?: 0L }
                val size = draft.toByteArray().size.toLong()
                val over = others + size > AgentInstructions.BUDGET
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (over) stringResource(R.string.st_inst_over)
                        else stringResource(if (editor.dirty) R.string.st_inst_unsaved else R.string.st_inst_saved_state, sizeText(size)),
                        style = small(13.sp, 17.sp), color = if (over) c.warn else c.t3, modifier = Modifier.weight(1f),
                    )
                    KBtn(
                        stringResource(R.string.st_inst_save), {
                            scope.launch {
                                if (a.saveInstruction(editor.scope, draft)) { editor.saved = draft; toast.show(msgSaved) } else toast.show(msgFailed)
                            }
                        },
                        kind = BtnKind.Primary, height = 48.dp, radius = 14.dp, horizontal = 22.dp, enabled = editor.dirty,
                    )
                }
                if (editor.saved != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (editor.deleteArmed) R.string.st_inst_delete_confirm else R.string.st_inst_delete),
                            style = body(14.5.sp, 19.sp, FontWeight.Medium), color = c.danger,
                            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) {
                                    if (!editor.deleteArmed) { editor.deleteArmed = true; return@clickable }
                                    scope.launch {
                                        if (a.deleteInstruction(editor.scope)) { editor.saved = null; editor.draft = null; toast.show(msgDeleted); onDeleted() }
                                        else toast.show(msgFailed)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Лист «Выйти без сохранения?» (`#sheet-discard`): «Не сохранять» и «Сохранить» как у листа предела. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.DiscardSheet(onDrop: () -> Unit, onKeep: () -> Unit) {
    com.dsh.core.designsystem.kit.SheetTitle(stringResource(R.string.st_inst_discard_title))
    Text(
        stringResource(R.string.st_inst_discard_hint), style = body(14.sp, 20.sp), color = Dsh.colors.t2,
        modifier = Modifier.offset(y = (-6).dp).padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.st_inst_discard), onDrop, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
        KBtn(stringResource(R.string.st_inst_save), onKeep, Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

/** `.in-empty`: плитка 56, «Файла пока нет», пояснение, «Начать с шаблона» и «Пустой файл» по 50 dp. */
@Composable
private fun EmptyInstructions(onTemplate: () -> Unit, onBlank: () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Si.c1), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Text, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(R.string.st_inst_empty), style = display(19.sp, 24.sp), color = c.t1, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Text(
            stringResource(R.string.st_inst_empty_hint), style = body(14.5.sp, 21.75.sp), color = c.t2, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 18.dp),
        )
        KBtn(stringResource(R.string.st_inst_template_btn), onTemplate, Modifier.fillMaxWidth(), kind = BtnKind.Primary, height = 50.dp, radius = 16.dp, fontSize = 16.sp)
        KBtn(stringResource(R.string.st_inst_blank), onBlank, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost, height = 50.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

/** `.fv-seg`: «Правка | Вид», 40 dp, выбранный — карточка s1 с тенью. */
@Composable
private fun ModeSeg(preview: Boolean, onChange: (Boolean) -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.s2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to R.string.st_inst_edit, true to R.string.st_inst_view).forEach { (isView, label) ->
            val on = preview == isView
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)).background(c.s1) else Modifier)
                    .clickable(role = Role.Tab) { onChange(isView) }
                    .semantics { selected = on; role = Role.Tab },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = body(13.5.sp, 17.sp, FontWeight.Medium), color = if (on) c.t1 else c.t2)
            }
        }
    }
}
