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
import androidx.compose.ui.draw.rotate
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

    // ── Найти и заменить ──
    var findOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    /** Номер текущего совпадения; −1 — нет. */
    var current by mutableStateOf(-1)
    val matches: List<Int> get() = findAll(value.text, query)

    /** Открыть поиск: выделенное слово (в одну строку) становится запросом. */
    fun openFind() {
        val v = value
        val sel = v.text.substring(minOf(v.selection.start, v.selection.end), maxOf(v.selection.start, v.selection.end))
        if (sel.isNotEmpty() && '\n' !in sel) query = sel
        findOpen = true
        seek(0)
    }

    /** К совпадению: [move] = 0 — первое от курсора, ±1 — следующее/предыдущее по кругу; выделяет его. */
    fun seek(move: Int) {
        val m = matches
        if (m.isEmpty()) { current = -1; return }
        current = if (move == 0) m.indexOfFirst { it >= minOf(value.selection.start, value.selection.end) }.let { if (it < 0) 0 else it }
        else ((if (current < 0) 0 else current) + move + m.size) % m.size
        val at = m[current]
        value = value.copy(selection = TextRange(at, at + query.length))
    }

    fun replaceCurrent() {
        val m = matches
        if (m.isEmpty()) return
        val at = m[current.coerceIn(0, m.lastIndex)]
        snapshot(value, force = true, now = System.currentTimeMillis())
        val t = value.text
        value = TextFieldValue(t.substring(0, at) + replacement + t.substring(at + query.length), TextRange(at + replacement.length))
        seek(0)
    }

    /** Заменить все (один шаг отмены); возвращает число замен. */
    fun replaceEverything(): Int {
        val (text, n) = replaceAll(value.text, query, replacement)
        if (n == 0) return 0
        snapshot(value, force = true, now = System.currentTimeMillis())
        value = TextFieldValue(text, TextRange(minOf(value.selection.start, text.length)))
        current = -1
        return n
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
        if (editor.findOpen) FindBar(editor)
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
            // Подсветка синтаксиса, как в просмотре (файлы до [HIGHLIGHT_MAX] — на каждое нажатие она пересчитывается),
            // и совпадения поиска (текущее — ярче): поле не в фокусе выделения не показывает.
            visualTransformation = EditorLook(
                ext = file.extension.lowercase(),
                colors = c,
                query = if (editor.findOpen) editor.query else "",
                matches = if (editor.findOpen) editor.matches else emptyList(),
                current = editor.current,
            ),
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
            HistoryKey(com.dsh.core.designsystem.DshGlyphs.Search, stringResource(R.string.fe_find_open), editor.doc != null) { if (editor.findOpen) editor.findOpen = false else editor.openFind() }
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

/** Больше — без подсветки синтаксиса в правке. */
private const val HIGHLIGHT_MAX = 50_000

/**
 * Вид текста в правке: подсветка синтаксиса ([highlight] просмотра — текст тот же, меняются только цвета) и
 * совпадения поиска — все мягким акцентом, текущее плотнее. Смещения не меняются.
 */
private data class EditorLook(
    val ext: String,
    val colors: com.dsh.core.designsystem.DshColors,
    val query: String,
    val matches: List<Int>,
    val current: Int,
) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val base = if (text.length <= HIGHLIGHT_MAX) highlight(text.text.split('\n'), ext, colors).takeIf { it.length == text.length } ?: text else text
        val b = androidx.compose.ui.text.AnnotatedString.Builder(base)
        if (query.isNotEmpty()) matches.forEachIndexed { i, at ->
            if (at + query.length <= text.length) {
                b.addStyle(androidx.compose.ui.text.SpanStyle(background = if (i == current) colors.accent.copy(alpha = 0.45f) else colors.accentSoft), at, at + query.length)
            }
        }
        return androidx.compose.ui.text.input.TransformedText(b.toAnnotatedString(), androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

/** `.fe-find`: «Найти» (N из M, ↑ ↓, ✕) и «Заменить на» («Заменить», «Все»). */
@Composable
private fun FindBar(editor: FileEditor) {
    val c = Dsh.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = editor.matches
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FindField(editor.query, { editor.query = it; editor.seek(0) }, stringResource(R.string.fe_find), Modifier.weight(1f))
            Text(
                when {
                    editor.query.isEmpty() -> ""
                    m.isEmpty() -> stringResource(R.string.fe_find_none)
                    else -> stringResource(R.string.fe_find_n, editor.current + 1, m.size)
                },
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp), color = if (editor.query.isNotEmpty() && m.isEmpty()) c.danger else c.t3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(min = 44.dp),
            )
            FindIcon(com.dsh.core.designsystem.DshGlyphs.Up, stringResource(R.string.fe_find_prev), m.isNotEmpty()) { editor.seek(-1) }
            FindIcon(com.dsh.core.designsystem.DshGlyphs.Up, stringResource(R.string.fe_find_next), m.isNotEmpty(), rotate = 180f) { editor.seek(1) }
            FindIcon(com.dsh.core.designsystem.DshGlyphs.get("x"), stringResource(R.string.fe_find_close), true) { editor.findOpen = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FindField(editor.replacement, { editor.replacement = it }, stringResource(R.string.fe_replace_with), Modifier.weight(1f))
            KBtn(stringResource(R.string.fe_replace), { editor.replaceCurrent() }, kind = BtnKind.Ghost, height = 40.dp, radius = 12.dp, fontSize = 13.5.sp, horizontal = 12.dp, enabled = m.isNotEmpty())
            KBtn(
                stringResource(R.string.fe_replace_all), {
                    val n = editor.replaceEverything()
                    if (n > 0) android.widget.Toast.makeText(ctx, ctx.getString(R.string.fe_replaced, n), android.widget.Toast.LENGTH_SHORT).show()
                },
                kind = BtnKind.Ghost, height = 40.dp, radius = 12.dp, fontSize = 13.5.sp, horizontal = 12.dp, enabled = m.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun FindField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val c = Dsh.colors
    var focused by androidx.compose.runtime.remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(40.dp).clip(shape).background(c.s1).border(1.5.dp, if (focused) c.accent else c.line, shape).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val style = TextStyle(fontFamily = DshFonts.mono, fontSize = 14.sp, color = c.t1)
        if (value.isEmpty()) Text(placeholder, style = style.copy(fontFamily = DshFonts.body, color = c.t3), maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = style, cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
        )
    }
}

@Composable
private fun FindIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, rotate: Float = 0f, onClick: () -> Unit) {
    val c = Dsh.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon, null, tint = if (enabled) c.t1 else c.t3.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp).then(if (rotate != 0f) Modifier.rotate(rotate) else Modifier),
        )
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
