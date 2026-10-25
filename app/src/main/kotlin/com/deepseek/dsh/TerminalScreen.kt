package com.deepseek.dsh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.pressScale
import com.deepseek.dsh.core.designsystem.kit.IconBtn
import com.deepseek.dsh.core.designsystem.kit.KBtn
import com.deepseek.dsh.core.designsystem.kit.KSheet
import com.deepseek.dsh.core.designsystem.kit.SBar
import com.deepseek.dsh.core.designsystem.kit.SheetItem
import com.deepseek.dsh.core.designsystem.kit.SheetList
import com.deepseek.dsh.core.designsystem.kit.SheetTitle
import com.deepseek.dsh.core.designsystem.kit.Spinner
import com.deepseek.dsh.core.designsystem.kit.ToastHost
import com.deepseek.dsh.core.designsystem.kit.ToastState
import com.deepseek.dsh.terminal.BlockState
import com.deepseek.dsh.terminal.TermBlock
import com.deepseek.dsh.terminal.TermModel
import com.deepseek.dsh.terminal.Tone
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/** Вкладка `.ttab`: одна оболочка Host. */
data class TermTab(val id: String, val title: String, val live: Boolean)

/** Что показывать в окне вывода, пока нет блоков (или поверх них — закрытие). */
enum class TermStatus { Connecting, Ready, NoSession, Unavailable, Closed }

/**
 * Экран «Терминал» — `#screen-terminal` прототипа: шапка с папкой, вкладки,
 * окно блоков команд (код выхода, время, «Объяснить / Копировать / Повторить»),
 * подсказки из истории, строка ввода и ряд клавиш Esc Tab Ctrl Alt ↑ ↓ | ~ / -.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    tabs: List<TermTab>,
    activeId: String?,
    model: TermModel?,
    status: TermStatus,
    subtitle: String?,
    connected: Boolean,
    writable: Boolean,
    history: List<String>,
    fontSize: Float,
    toast: ToastState,
    onBack: (() -> Unit)?,
    onFont: () -> Unit,
    onClear: () -> Unit,
    onSelectTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onRun: (String) -> Unit,
    onStdin: (String) -> Unit,
    onRaw: (String) -> Unit,
    onExplain: (TermBlock) -> Unit,
    onCopy: (TermBlock) -> Unit,
    onRetryStatus: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var recall by remember { mutableIntStateOf(-1) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var tabMenu by remember { mutableStateOf<TermTab?>(null) }
    val busy = model?.live != null
    val canType = writable && status == TermStatus.Ready

    fun setInput(t: String) { input = TextFieldValue(t, TextRange(t.length)) }
    fun recallBy(d: Int) {
        if (history.isEmpty()) return
        recall = (recall + d).coerceIn(-1, history.lastIndex)
        setInput(if (recall < 0) "" else history[history.lastIndex - recall])
    }
    fun submit() {
        val t = input.text
        when {
            busy && t.isEmpty() -> { onRaw("\u0003"); toast.show("^C") }
            busy -> onStdin(t)
            t.isBlank() -> return
            else -> onRun(t.trim())
        }
        setInput(""); recall = -1
    }
    /** Залипшие Ctrl / Alt превращают следующую букву в управляющий код. */
    fun typed(v: TextFieldValue) {
        val added = if (v.text.length == input.text.length + 1 && v.text.startsWith(input.text)) v.text.last() else null
        if (added != null && (ctrl || alt)) {
            if (ctrl) {
                when (added.lowercaseChar()) {
                    'c', 'с' -> { onRaw("\u0003"); toast.show("^C") }
                    'l', 'д' -> onClear()
                    else -> {
                        val ch = added.lowercaseChar()
                        if (ch in 'a'..'z') onRaw(((ch - 'a') + 1).toChar().toString())
                    }
                }
                ctrl = false
            } else {
                onRaw("\u001b$added"); alt = false
            }
            return
        }
        input = v
    }

    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SBar(
                title = stringResource(R.string.tm_title),
                subtitle = subtitle,
                onBack = onBack,
                backDescription = stringResource(R.string.action_back),
                live = connected,
            ) {
                IconBtn(DshGlyphs.Text, stringResource(R.string.tm_font), onClick = onFont)
                IconBtn(DshGlyphs.Trash, stringResource(R.string.tm_clear), onClick = onClear)
            }
            // `.ttabs`
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEach { t ->
                    val on = t.id == activeId
                    val bg by animateColorAsState(if (on) c.t1 else c.s2, tween(250), label = "tab")
                    val fg by animateColorAsState(if (on) c.bg else c.t2, tween(250), label = "tabT")
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(bg)
                            .combinedClickable(onLongClick = { tabMenu = t }, onClick = { onSelectTab(t.id) })
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (t.live) Spinner(fg, 10.dp, 1.75.dp)
                        Text(t.title, style = mono(12.5.sp, FontWeight.Normal), color = fg, maxLines = 1)
                    }
                }
                if (status != TermStatus.NoSession) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.s2).clickable(role = Role.Button, onClick = onAddTab),
                        contentAlignment = Alignment.Center,
                    ) { Icon(DshGlyphs.Plus, stringResource(R.string.tm_add), tint = c.t1, modifier = Modifier.size(15.dp)) }
                }
            }
            // `.tout`
            TermOutput(model, status, fontSize, onExplain, onCopy, onRun = { setInput(it) }, onRetry = onRun, onRetryStatus = onRetryStatus, onResize = onResize, modifier = Modifier.weight(1f))
            // `.tdock`
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
                val sugg = listOf("help") + history.asReversed().distinct().filter { it != "help" }.take(4)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEach { s ->
                        Box(
                            Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(10.dp))
                                .clickable(enabled = canType) { setInput(s) }.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(s, style = mono(12.sp, FontWeight.Normal), color = c.t2, maxLines = 1) }
                    }
                }
                // `.tin`
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth()
                        .shadow(14.dp, shape, clip = false, ambientColor = Color(0xFF1C1A46).copy(alpha = if (c.isDark) 0.5f else 0.10f), spotColor = Color(0xFF1C1A46).copy(alpha = if (c.isDark) 0.5f else 0.14f))
                        .clip(shape).background(c.s1).border(1.dp, c.line, shape)
                        .padding(start = 15.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("❯", style = mono(13.5.sp, FontWeight.Normal), color = c.termPrompt)
                    val inputDesc = stringResource(R.string.tm_input)
                    Box(Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(
                            value = input,
                            onValueChange = ::typed,
                            enabled = canType,
                            singleLine = true,
                            textStyle = mono(14.5.sp, FontWeight.Normal).copy(color = c.t1),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }, onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = inputDesc },
                            decorationBox = { inner ->
                                if (input.text.isEmpty()) Text(
                                    stringResource(if (writable || status != TermStatus.Ready) R.string.tm_placeholder else R.string.tm_placeholder_ro),
                                    style = mono(14.5.sp, FontWeight.Normal), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                inner()
                            },
                        )
                    }
                    val stop = busy && input.text.isEmpty()
                    val runBg by animateColorAsState(if (stop) c.t1 else c.accent, tween(250), label = "run")
                    val runFg = if (stop) c.bg else c.onAccent
                    val label = stringResource(if (stop) R.string.tm_stop else if (busy) R.string.tm_send else R.string.tm_run)
                    val ia = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(40.dp).pressScale(ia, 0.9f).clip(CircleShape).background(if (canType) runBg else lerp(c.accent, c.s1, 0.5f))
                            .clickable(ia, ripple(), enabled = canType, role = Role.Button) { submit() }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (stop) DshGlyphs.Stop else DshGlyphs.Up, null, tint = runFg, modifier = Modifier.size(22.dp)) }
                }
                // `.keys`
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyCap("Esc", enabled = canType) { if (input.text.isEmpty() && busy) onRaw("\u001b") else setInput("") }
                    KeyCap("Tab", enabled = canType) {
                        val v = input.text
                        history.asReversed().firstOrNull { it.startsWith(v) && it != v && v.isNotEmpty() }?.let { setInput(it) }
                            ?: if (busy) onRaw("\t") else Unit
                    }
                    KeyCap("Ctrl", on = ctrl, enabled = canType) { ctrl = !ctrl; if (ctrl) alt = false }
                    KeyCap("Alt", on = alt, enabled = canType) { alt = !alt; if (alt) ctrl = false }
                    KeyCap("↑", enabled = canType) { if (busy && input.text.isEmpty()) onRaw("\u001b[A") else recallBy(1) }
                    KeyCap("↓", enabled = canType) { if (busy && input.text.isEmpty()) onRaw("\u001b[B") else recallBy(-1) }
                    listOf("|", "~", "/", "-").forEach { k ->
                        KeyCap(k, enabled = canType) {
                            val t = input.text.substring(0, input.selection.min) + k + input.text.substring(input.selection.max)
                            input = TextFieldValue(t, TextRange(input.selection.min + 1))
                        }
                    }
                }
            }
        }
        ToastHost(toast)
        KSheet(tabMenu != null, onDismiss = { tabMenu = null }) {
            val t = tabMenu
            SheetTitle(t?.title.orEmpty())
            SheetList {
                row { SheetItem(DshGlyphs.X, stringResource(R.string.tm_tab_close), stringResource(R.string.tm_tab_close_sub), iconTint = c.danger) { t?.let { onCloseTab(it.id) }; tabMenu = null } }
            }
        }
    }
}

/** Окно `.tout`: рамка 22, фон терминала, блоки команд и состояния. */
@Composable
private fun TermOutput(
    model: TermModel?,
    status: TermStatus,
    fontSize: Float,
    onExplain: (TermBlock) -> Unit,
    onCopy: (TermBlock) -> Unit,
    onRun: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRetryStatus: () -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(22.dp)
    val blocks = model?.blocks.orEmpty()
    val list = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(shape).background(c.termBg).border(1.dp, c.line, shape),
    ) {
        // Ширина окна в символах — оболочке, чтобы `ls` раскладывал колонки по экрану.
        val charW = remember(fontSize) { measurer.measure("0000000000", mono(fontSize.sp, FontWeight.Normal)).size.width / 10f }
        val cols = with(density) { ((maxWidth - 28.dp).toPx() / charW).toInt().coerceIn(20, 300) }
        val rows = with(density) { (maxHeight.toPx() / (fontSize.sp.toPx() * 1.6f)).toInt().coerceIn(5, 200) }
        LaunchedEffect(cols, rows) { onResize(cols, rows) }
        // Новый вывод и открытая клавиатура — к низу, если читатель и так был внизу.
        var atEnd by remember { mutableStateOf(true) }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, more) -> if (scrolling) atEnd = !more }
        }
        val tail = blocks.lastOrNull()
        LaunchedEffect(blocks.size, tail?.version, tail?.explain?.text, tail?.folded, maxHeight) {
            if (blocks.isEmpty() || !atEnd) return@LaunchedEffect
            list.scrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2)
        }
        if (blocks.isEmpty()) {
            TermPlaceholder(status, onRetryStatus)
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                items(blocks, key = { it.id }) { b ->
                    BlockView(b, fontSize, onExplain, onCopy, onFix = onRun, onRetry = onRetry)
                }
                if (status == TermStatus.Closed || status == TermStatus.Unavailable) item(key = "closed") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (status == TermStatus.Closed) R.string.tm_closed else R.string.tm_unavailable), style = body(13.5.sp), color = c.termDim, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        KBtn(stringResource(if (status == TermStatus.Closed) R.string.tm_reopen else R.string.tm_try_again), onClick = onRetryStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun TermPlaceholder(status: TermStatus, onRetry: () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (status == TermStatus.Connecting) {
            Spinner(c.termPrompt, 18.dp)
            Spacer(Modifier.height(12.dp))
        }
        val text = when (status) {
            TermStatus.Connecting -> R.string.tm_connecting
            TermStatus.Ready -> R.string.tm_empty
            TermStatus.NoSession -> R.string.tm_no_session
            TermStatus.Unavailable -> R.string.tm_unavailable
            TermStatus.Closed -> R.string.tm_closed
        }
        Text(stringResource(text), style = body(13.5.sp, 20.sp), color = c.termDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        if (status == TermStatus.Unavailable || status == TermStatus.Closed) {
            Spacer(Modifier.height(14.dp))
            KBtn(stringResource(if (status == TermStatus.Closed) R.string.tm_reopen else R.string.tm_try_again), onClick = onRetry)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Блок `.blk`: шапка 40 (❯ команда · время), вывод, действия при ошибке, объяснение. */
@Composable
private fun BlockView(
    b: TermBlock,
    fontSize: Float,
    onExplain: (TermBlock) -> Unit,
    onCopy: (TermBlock) -> Unit,
    onFix: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val c = Dsh.colors
    val bg = when (b.state) {
        BlockState.Live -> c.termPrompt.copy(alpha = 0.05f)
        BlockState.Err -> c.termErr.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val fs = fontSize.sp
    val lh = (fontSize * 1.6f).sp
    Column(
        Modifier.fillMaxWidth().background(bg)
            .drawBehind {
                if (b.state == BlockState.Live) drawRect(c.termPrompt, size = Size(3.dp.toPx(), size.height))
                drawRect(c.line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            }
            .padding(start = 14.dp, end = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(interactionSource = null, indication = null, role = Role.Button) { b.folded = !b.folded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("❯", style = mono(fs, FontWeight.Normal, lh), color = c.termPrompt)
            Text(b.cmd, style = mono(fs, FontWeight.Medium, lh), color = c.termText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            BlockMeta(b)
        }
        val text = remember(b.version, c.isDark) { styled(b, c.termText, c.termDim, c.termOk, c.termErr, c.termWarn, c.termPrompt) }
        if (b.folded) {
            Spacer(Modifier.height(6.dp))
        } else {
            if (text.isEmpty()) Spacer(Modifier.height(10.dp)) else {
                SelectionContainer(Modifier.pullUp(2.dp)) {
                    Text(text, style = mono(fs, FontWeight.Normal, lh), color = c.termText, modifier = Modifier.fillMaxWidth().alpha(0.86f))
                }
                b.progress?.let { p ->
                    Box(Modifier.padding(top = 8.dp, bottom = 2.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.termText.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(p / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.termPrompt))
                    }
                }
            }
            if (b.state == BlockState.Err) {
                // `.blk-a`: переносится на новую строку, если не помещается
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (b.explain == null) Mini(DshGlyphs.Spark, stringResource(R.string.tm_explain), ai = true) { onExplain(b) }
                    Mini(DshGlyphs.Copy, stringResource(R.string.tm_copy)) { onCopy(b) }
                    Mini(DshGlyphs.Redo, stringResource(R.string.tm_retry)) { onRetry(b.cmd) }
                }
            }
            b.explain?.let { e ->
                val shown = remember(e) { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn(com.deepseek.dsh.core.designsystem.DshMotion.smooth()) + slideInVertically(com.deepseek.dsh.core.designsystem.DshMotion.smooth()) { it / 8 }) {
                    val eshape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.padding(top = 10.dp).fillMaxWidth().clip(eshape).background(c.s1)
                            .border(1.dp, lerp(c.line, c.accent, 0.3f), eshape).padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(DshGlyphs.Spark, null, tint = c.accent, modifier = Modifier.size(15.dp))
                            Text("DSH", style = body(12.sp, 16.sp, FontWeight.SemiBold).copy(letterSpacing = 0.02.em), color = c.accent)
                        }
                        when {
                            e.error != null -> {
                                Text(e.error.orEmpty(), style = body(13.5.sp, 20.sp), color = c.danger)
                                Row(Modifier.padding(top = 9.dp)) { Mini(DshGlyphs.Redo, stringResource(R.string.tm_retry)) { onExplain(b) } }
                            }
                            e.text.isEmpty() && !e.done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Spinner(c.accent, 12.dp, 1.75.dp)
                                Text(stringResource(R.string.tm_explain_wait), style = body(13.5.sp, 20.sp), color = c.t3)
                            }
                            else -> Text(e.text, style = body(13.5.sp, 20.sp), color = c.t1)
                        }
                        e.fix?.let { fix ->
                            Row(Modifier.padding(top = 9.dp)) { Mini(DshGlyphs.Term, fix, ai = true) { onFix(fix) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Справа в шапке блока: ✓ время · ✗ код · крутилка и секунды. */
@Composable
private fun BlockMeta(b: TermBlock) {
    val c = Dsh.colors
    val style = body(11.sp, 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (b.state) {
            BlockState.Live -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(b.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                Spinner(c.termPrompt, 10.dp, 1.75.dp)
                Text(stringResource(R.string.tm_sec, ((now - b.startedAt) / 1000).coerceAtLeast(0).toString()), style = style, color = c.termPrompt)
            }
            BlockState.Ok -> {
                Icon(DshGlyphs.Check, null, tint = c.termOk, modifier = Modifier.size(13.dp))
                Text(duration(b.durationMs), style = style, color = c.termDim)
            }
            BlockState.Err -> {
                Icon(DshGlyphs.X, null, tint = c.termErr, modifier = Modifier.size(13.dp))
                Text(if (b.code == 130) stringResource(R.string.tm_interrupted) else stringResource(R.string.tm_code, b.code ?: 1), style = style, color = c.termDim)
            }
            BlockState.Plain -> Unit
        }
    }
}

@Composable
private fun duration(ms: Long): String {
    val s = ms / 1000.0
    if (s >= 60) return stringResource(R.string.tm_min_sec, (ms / 60000).toInt(), ((ms / 1000) % 60).toInt())
    val f = DecimalFormat(if (s < 10) "0.00" else "0.0", DecimalFormatSymbols.getInstance())
    return stringResource(R.string.tm_sec, f.format(s))
}

/** Кнопка `.mini`: 36, радиус 10, 12,5; `ai` — акцентная. */
@Composable
private fun Mini(icon: ImageVector, text: String, ai: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val fg = if (ai) c.accent else c.t1
    Row(
        Modifier.height(36.dp).pressScale(ia, 0.96f).clip(RoundedCornerShape(10.dp)).background(if (ai) c.accentSoft else c.s2)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = body(12.5.sp, 16.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Клавиша `.keys button`: 42 × ≥46, радиус 11, моно 13; залипшая — акцентом. */
@Composable
private fun KeyCap(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) c.accent else c.s2, tween(200), label = "key")
    Box(
        Modifier.height(42.dp).widthIn(min = 46.dp).pressScale(ia, 0.9f).clip(RoundedCornerShape(11.dp)).background(bg)
            .clickable(ia, ripple(), enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = mono(13.sp, FontWeight.Normal), color = if (on) c.onAccent else c.t1) }
}

/** Вывод с цветами `.d/.g/.r/.y/.b`; пустые строки в конце убираются. */
private fun styled(b: TermBlock, plain: Color, dim: Color, ok: Color, err: Color, warn: Color, pr: Color): AnnotatedString {
    val lines = b.out.lines.map { (t, tones) ->
        val line = t.trimEnd()
        // Сообщение самой оболочки об ошибке («bash: adb: команда не найдена») — красным, как `.r`.
        if (b.state == BlockState.Err && SHELL_ERR.containsMatchIn(line) && tones.all { it == Tone.Plain }) line to List(line.length) { Tone.Err } else line to tones
    }.dropLastWhile { it.first.isEmpty() }
    return buildAnnotatedString {
        lines.forEachIndexed { li, (t, tones) ->
            if (li > 0) append('\n')
            var i = 0
            while (i < t.length) {
                val tone = tones.getOrElse(i) { Tone.Plain }
                var j = i + 1
                while (j < t.length && tones.getOrElse(j) { Tone.Plain } == tone) j++
                val color = when (tone) { Tone.Plain -> plain; Tone.Dim -> dim; Tone.Ok -> ok; Tone.Err -> err; Tone.Warn -> warn; Tone.Pr -> pr }
                if (tone == Tone.Plain) append(t.substring(i, j)) else pushStyleAndAppend(SpanStyle(color = color), t.substring(i, j))
                i = j
            }
        }
    }
}

private val SHELL_ERR = Regex("""^-?(bash|sh|zsh|dash): """)

private fun AnnotatedString.Builder.pushStyleAndAppend(style: SpanStyle, text: String) {
    val start = length
    append(text)
    addStyle(style, start, length)
}

/** Поднять содержимое на [d] и укоротить на столько же (`margin-top` < 0 в прототипе). */
private fun Modifier.pullUp(d: Dp) = layout { m, cs ->
    val p = m.measure(cs)
    val px = d.roundToPx()
    layout(p.width, (p.height - px).coerceAtLeast(0)) { p.place(0, -px) }
}

/** Межстрочный интервал как в CSS: поровну сверху и снизу каждой строки, без обрезки у первой и последней. */
private val CssLeading = androidx.compose.ui.text.style.LineHeightStyle(
    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
)

private fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal, lh: TextUnit = size * 1.3f) =
    TextStyle(fontFamily = DshFonts.mono, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)

private fun body(size: TextUnit, lh: TextUnit = size * 1.3f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.body, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)
