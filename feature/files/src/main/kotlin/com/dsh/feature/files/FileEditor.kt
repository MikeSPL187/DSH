package com.dsh.feature.files

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Правка файла: что открыто, текст в поле и сохранённая версия; «не сохранено» — когда они разошлись. */
@Stable
internal class FileEditor {
    var path by mutableStateOf<String?>(null)
    var doc by mutableStateOf<EditDoc?>(null)
    var value by mutableStateOf(TextFieldValue(""))
    var problem by mutableStateOf<EditProblem?>(null)
    var saving by mutableStateOf(false)
    val dirty: Boolean get() = doc != null && value.text != doc?.text

    // История: шаг — слово (пауза [STEP_MS] или пробел/перевод строки), а не каждая буква; не больше [MAX_HISTORY].
    private val undoStack = androidx.compose.runtime.mutableStateListOf<TextFieldValue>()
    private val redoStack = androidx.compose.runtime.mutableStateListOf<TextFieldValue>()
    private var lastEdit = 0L
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset(p: String) { path = p; doc = null; value = TextFieldValue(""); problem = null; saving = false; clearHistory() }

    private fun clearHistory() { undoStack.clear(); redoStack.clear(); lastEdit = 0L }

    fun load(file: File) {
        openForEdit(file)
            .onSuccess { doc = it; value = TextFieldValue(it.text); problem = null; clearHistory() }
            .onFailure { problem = (it as? EditException)?.problem ?: EditProblem.UNREADABLE }
    }

    /** Новое значение поля: при смене текста — шаг истории (только движение курсора историю не трогает). */
    fun change(next: TextFieldValue, now: Long = System.currentTimeMillis()) {
        val prev = value
        if (next.text != prev.text) snapshot(prev, force = false, now = now)
        value = next
    }

    private fun snapshot(prev: TextFieldValue, force: Boolean, now: Long) {
        if (force || undoStack.isEmpty() || now - lastEdit > STEP_MS || prev.text.lastOrNull()?.isWhitespace() == true) {
            undoStack.add(prev)
            if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        }
        redoStack.clear()
        lastEdit = now
    }

    fun undo() { if (undoStack.isEmpty()) return; redoStack.add(value); value = undoStack.removeAt(undoStack.lastIndex); lastEdit = 0L }

    fun redo() { if (redoStack.isEmpty()) return; undoStack.add(value); value = redoStack.removeAt(redoStack.lastIndex); lastEdit = 0L }

    /** Вставить [text] вместо выделения (отдельный шаг истории), курсор — после вставки. */
    fun insert(text: String) {
        val v = value
        val s = minOf(v.selection.start, v.selection.end)
        val e = maxOf(v.selection.start, v.selection.end)
        snapshot(v, force = true, now = System.currentTimeMillis())
        value = TextFieldValue(v.text.substring(0, s) + text + v.text.substring(e), TextRange(s + text.length))
    }

    private companion object {
        const val STEP_MS = 800L
        const val MAX_HISTORY = 200
    }
}

/** Клавиши для кода (`.fe-keys`): Tab — 4 пробела. */
private val KEYS = listOf("\t", "{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":", "\"", "'", "/", "|", "&", "-", "_")

/**
 * Экран правки (`.fe-view`): моноширинное поле с переносом строк, «строка N, столбец M», ряд клавиш для кода.
 * Файл не текст или слишком большой — честное объяснение вместо поля.
 */
@Composable
internal fun EditView(file: File, editor: FileEditor) {
    val c = Dsh.colors
    LaunchedEffect(file.path) { if (editor.doc == null && editor.problem == null) withContext(Dispatchers.IO) { editor.load(file) } }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        val problem = editor.problem
        if (problem != null) {
            Text(
                stringResource(
                    when (problem) {
                        EditProblem.TOO_BIG -> R.string.fe_too_big
                        EditProblem.NOT_TEXT -> R.string.fe_not_text
                        EditProblem.UNREADABLE -> R.string.fe_unreadable
                    },
                ),
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, lineHeight = 21.sp), color = c.t2,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp),
            )
            return@Column
        }
        var focused by androidx.compose.runtime.remember { mutableStateOf(false) }
        val edge by animateColorAsState(if (focused) c.accent else c.line, tween(250), label = "fe-edge")
        val shape = RoundedCornerShape(16.dp)
        val label = stringResource(R.string.fe_area)
        BasicTextField(
            editor.value, { editor.change(it) },
            textStyle = TextStyle(fontFamily = DshFonts.mono, fontSize = 13.5.sp, lineHeight = 21.sp, color = c.t1),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            enabled = editor.doc != null,
            modifier = Modifier.weight(1f).fillMaxWidth().clip(shape).background(c.s1).border(1.5.dp, edge, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        )
        val before = editor.value.text.substring(0, editor.value.selection.start.coerceIn(0, editor.value.text.length))
        val line = before.count { it == '\n' } + 1
        val col = before.length - (before.lastIndexOf('\n') + 1) + 1
        Text(
            stringResource(R.string.fe_pos, line, col), style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.sp), color = c.t3,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tab = stringResource(R.string.fe_tab)
            HistoryKey(com.dsh.core.designsystem.DshGlyphs.Undo, stringResource(R.string.fe_undo), editor.canUndo) { editor.undo() }
            HistoryKey(com.dsh.core.designsystem.DshGlyphs.Redo, stringResource(R.string.fe_redo), editor.canRedo) { editor.redo() }
            KEYS.forEach { k ->
                Box(
                    Modifier.height(40.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(11.dp)).background(c.s2)
                        .clickable(enabled = editor.doc != null, role = Role.Button) { editor.insert(if (k == "\t") "    " else k) }
                        .semantics { if (k == "\t") contentDescription = tab }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (k == "\t") "⇥" else k, style = TextStyle(fontFamily = DshFonts.mono, fontSize = 14.sp), color = c.t1) }
            }
        }
    }
}

/** ↶ / ↷ в ряду клавиш: недоступная — бледная. */
@Composable
private fun HistoryKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    Box(
        Modifier.height(40.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(11.dp)).background(c.s2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, null, tint = if (enabled) c.t1 else c.t3.copy(alpha = 0.55f), modifier = Modifier.size(18.dp)) }
}

/** Лист «Выйти без сохранения?» (`#sheet-discard`): «Не сохранять» / «Сохранить». */
@Composable
internal fun ColumnScope.EditDiscardSheet(onDrop: () -> Unit, onKeep: () -> Unit) {
    DshSheetTitle(stringResource(R.string.fe_discard_title))
    Text(
        stringResource(R.string.fe_discard_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = Dsh.colors.t2,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.fe_drop), onDrop, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp)
        KBtn(stringResource(R.string.fe_save), onKeep, Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

/** Лист «Файл изменился» (`#sheet-feconflict`): «Открыть новый» / «Перезаписать». */
@Composable
internal fun ColumnScope.EditConflictSheet(onReload: () -> Unit, onOverwrite: () -> Unit) {
    DshSheetTitle(stringResource(R.string.fe_conflict_title))
    Text(
        stringResource(R.string.fe_conflict_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = Dsh.colors.t2,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.fe_reload), onReload, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp)
        KBtn(stringResource(R.string.fe_overwrite), onOverwrite, Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
    }
}
