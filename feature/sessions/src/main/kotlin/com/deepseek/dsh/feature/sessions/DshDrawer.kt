package com.deepseek.dsh.feature.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.DshSheetItem
import com.deepseek.dsh.core.designsystem.DshSheetList
import com.deepseek.dsh.core.designsystem.DshSheetTitle
import com.deepseek.dsh.core.designsystem.pressScale
import kotlinx.coroutines.launch

// Меню из прототипа (`#drawer`, «Меню v2»): чат уезжает вправо и уменьшается, слева — меню.

/** Открыто ли меню. `open()` / `close()` — как у состояния выдвижного меню Material. */
@Stable
class DshDrawerState {
    var isOpen by mutableStateOf(false)
        private set

    fun open() { isOpen = true }
    fun close() { isOpen = false }
}

/**
 * «Выдвижение» меню: чат сдвигается на ширину меню (86 %), уменьшается до 0,92
 * и скругляется (30 dp); меню въезжает слева с проявлением. Свайп от левого края открывает,
 * свайп по сдвинутому чату или нажатие на него — закрывает.
 */
@Composable
fun DshPushDrawer(state: DshDrawerState, drawer: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.isOpen) { state.close() }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val w = with(LocalDensity.current) { (maxWidth * 0.86f).toPx() }
        // Одно число 0…1 и для пружины, и для пальца: открытие свайпом продолжается без скачка.
        val prog = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(state.isOpen) {
            // Меню и чат не делят клавиатуру. Фокус не снимаем: на Android 8 система тут же
            // отдала бы его первому полю и снова открыла клавиатуру.
            keyboard?.hide()
            if (!dragging) prog.animateTo(if (state.isOpen) 1f else 0f, DshMotion.smooth())
        }
        fun settle(open: Boolean) {
            dragging = false
            if (open) state.open() else state.close()
            scope.launch { prog.animateTo(if (open) 1f else 0f, DshMotion.smooth()) }
        }
        val drag = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { settle(prog.value > 0.5f) },
                onDragCancel = { settle(state.isOpen) },
            ) { _, dx -> scope.launch { prog.snapTo((prog.value + dx / w).coerceIn(0f, 1f)) } }
        }
        val p = prog.value
        Box(
            Modifier.width(maxWidth * 0.86f).fillMaxHeight()
                .graphicsLayer { translationX = -0.3f * size.width * (1f - p); alpha = p },
        ) { if (p > 0.001f) drawer() }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = w * p
                    val s = 1f - 0.08f * p
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    shape = RoundedCornerShape((30 * p).dp)
                    clip = p > 0f
                    shadowElevation = if (p > 0f) 28.dp.toPx() * p else 0f
                },
        ) {
            content()
            // Сдвинутый чат: касание закрывает, свайп влево тянет обратно.
            if (state.isOpen || p > 0f) Box(
                Modifier.fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, null) { settle(false) }
                    .then(drag),
            )
            // Край слева (24 dp): свайп вправо открывает меню.
            if (!state.isOpen && p == 0f) Box(Modifier.width(24.dp).fillMaxHeight().then(drag))
        }
    }
}

private val CssLeading = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private fun body(size: TextUnit, lh: TextUnit = size * 1.25f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.body, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)
private fun display(size: TextUnit, lh: TextUnit = size * 1.25f) =
    TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)

/** Строка сессии в меню: название, состояние и время. */
data class DrawerSession(
    val id: String,
    val title: String,
    val time: String,
    val updatedAt: Long,
    val running: Boolean = false,
    val waiting: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Первая строка последнего ответа агента. */
    val preview: String? = null,
)

/**
 * Содержимое меню `#drawer`: DSH и состояние Host, поиск и «Новая сессия», проект,
 * «Плагины» и «Автоматизация», сессии по дням (закреплённые сверху), внизу — профиль и настройки.
 */
@Composable
fun DshDrawerContent(
    hostLive: Boolean,
    project: String,
    projectLetter: String,
    onProject: () -> Unit,
    sessions: List<DrawerSession>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onSessionMenu: (DrawerSession) -> Unit,
    onNew: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    searchHits: List<Pair<String, String>>,
    pluginsOn: Int,
    automationsOn: Int,
    onPlugins: () -> Unit,
    onAutomation: () -> Unit,
    userName: String,
    footerSub: String,
    onSettings: () -> Unit,
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
    archivedCount: Int = 0,
    showArchived: Boolean = false,
    onToggleArchived: () -> Unit = {},
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Dsh.colors
    var i = 0
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `.dhead`
        Row(Modifier.rise(i++).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(c.accent2, c.accent))),
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Dsh, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text("DSH", style = display(17.sp, 21.sp), color = c.t1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
                        if (hostLive) Box(Modifier.size(13.dp).clip(CircleShape).background(c.okSoft))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (hostLive) c.ok else c.t3))
                    }
                    Text(stringResource(if (hostLive) R.string.dw_host_on else R.string.dw_host_off), style = body(12.5.sp), color = c.t3)
                }
            }
        }
        // `.drow`: поиск и «Новая сессия»
        Row(Modifier.rise(i++), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Поле получает фокус только по касанию: иначе Android 8 сам ставит его туда и открывает клавиатуру.
            var armed by remember { mutableStateOf(false) }
            val requester = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(armed) { if (armed) requester.requestFocus() }
            Row(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(14.dp)).background(c.s2)
                    .clickable(remember { MutableInteractionSource() }, null) { armed = true }
                    .padding(start = 12.dp, end = if (query.isNotEmpty()) 1.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(DshGlyphs.Search, null, tint = c.t3, modifier = Modifier.size(18.dp))
                val desc = stringResource(R.string.dw_search)
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        query, onQuery, singleLine = true, textStyle = body(15.sp).copy(color = c.t1), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc }
                            .focusRequester(requester)
                            .focusProperties { canFocus = armed }
                            .onFocusChanged { if (!it.isFocused && armed && query.isEmpty()) armed = false },
                        decorationBox = { inner -> if (query.isEmpty()) Text(desc, style = body(15.sp), color = c.t3); inner() },
                    )
                    if (!armed) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { armed = true })
                }
                if (query.isNotEmpty()) Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onQuery("") },
                    contentAlignment = Alignment.Center,
                ) { Icon(DshGlyphs.X, stringResource(R.string.dw_clear), tint = c.t3, modifier = Modifier.size(18.dp)) }
            }
            val ia = remember { MutableInteractionSource() }
            val newDesc = stringResource(R.string.dw_new)
            Box(
                Modifier.size(42.dp).pressScale(ia, 0.92f).clip(RoundedCornerShape(14.dp)).background(c.t1)
                    .clickable(ia, ripple(), role = Role.Button, onClick = onNew).semantics { contentDescription = newDesc },
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.New, null, tint = c.bg, modifier = Modifier.size(22.dp)) }
        }
        // `.proj`
        Row(
            Modifier.rise(i++).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onProject).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Text(projectLetter, style = display(15.sp), color = c.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dw_project), style = body(11.5.sp, 14.4.sp), color = c.t3)
                Text(project, style = body(14.5.sp, 18.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(DshGlyphs.Updown, null, tint = c.t3, modifier = Modifier.size(18.dp))
        }
        // `.dscroll`
        Column(Modifier.weight(1f).padding(horizontal = 0.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (updateVersion != null) DLink(DshGlyphs.Down, stringResource(R.string.dw_update, updateVersion), null, Modifier.rise(i++), accent = true, onClick = onUpdate)
            DLink(DshGlyphs.Puzzle, stringResource(R.string.dw_plugins), pluginsOn.takeIf { it > 0 }?.toString(), Modifier.rise(i++), onClick = onPlugins)
            DLink(DshGlyphs.Clock, stringResource(R.string.dw_automation), automationsOn.takeIf { it > 0 }?.toString(), Modifier.rise(i++), onClick = onAutomation)
            extra()
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val local = sessions.filter { it.title.lowercase().contains(q) }
                val hits = searchHits.filter { (id, _) -> local.none { it.id == id } }
                Group(stringResource(R.string.dw_found))
                if (local.isEmpty() && hits.isEmpty()) Text(stringResource(R.string.dw_nothing), style = body(14.sp), color = c.t3, modifier = Modifier.padding(10.dp))
                local.forEach { s -> SessRow(s, s.id == selectedId, s.preview, onSelect, onSessionMenu) }
                hits.forEach { (id, snippet) ->
                    SessRow(DrawerSession(id, snippet.substringBefore(" · "), "", 0), id == selectedId, snippet.substringAfter(" · ", ""), onSelect, {})
                }
            } else {
                val now = java.util.Calendar.getInstance()
                fun dayStart(offsetDays: Int): Long = (now.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.DAY_OF_YEAR, -offsetDays)
                }.timeInMillis
                val visible = sessions.filter { it.archived == showArchived }
                val pinned = visible.filter { it.pinned }
                val rest = visible.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
                val groups = listOf(
                    R.string.dw_pinned to pinned,
                    R.string.dw_today to rest.filter { it.updatedAt >= dayStart(0) },
                    R.string.dw_yesterday to rest.filter { it.updatedAt in dayStart(1) until dayStart(0) },
                    R.string.dw_week to rest.filter { it.updatedAt in dayStart(7) until dayStart(1) },
                    R.string.dw_earlier to rest.filter { it.updatedAt < dayStart(7) },
                )
                if (visible.isEmpty()) Text(
                    stringResource(if (showArchived) R.string.dw_no_archived else R.string.dw_empty), style = body(14.sp, 20.sp), color = c.t3,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp),
                )
                groups.forEach { (label, list) ->
                    if (list.isNotEmpty()) {
                        Group(stringResource(label), Modifier.rise(i++))
                        list.forEach { s -> SessRow(s, s.id == selectedId, s.preview, onSelect, onSessionMenu, Modifier.rise(i++)) }
                    }
                }
                if (archivedCount > 0 || showArchived) Box(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable(role = Role.Button, onClick = onToggleArchived).padding(10.dp),
                ) {
                    Text(
                        if (showArchived) stringResource(R.string.dw_back_to_sessions) else stringResource(R.string.dw_archive, archivedCount),
                        style = body(13.5.sp, 17.sp), color = c.accent,
                    )
                }
            }
        }
        // `.dfoot`
        Row(
            Modifier.rise(i++).fillMaxWidth().drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .clickable(role = Role.Button, onClick = onSettings).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF0B37E), Color(0xFFE2767A)), start = Offset.Zero, end = Offset(100f, 100f))),
                contentAlignment = Alignment.Center,
            ) { Text(userName.take(1).uppercase(), style = body(14.sp, weight = FontWeight.SemiBold), color = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(userName, style = body(14.sp, 17.5.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(footerSub, style = body(12.sp, 15.sp), color = c.t3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(DshGlyphs.Gear, stringResource(R.string.dw_settings), tint = c.t3, modifier = Modifier.size(22.dp))
        }
    }
}

/** Появление пунктов меню слева с задержкой (как в прототипе: 30 + 18 мс на пункт). */
@Composable
private fun Modifier.rise(index: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(30L + index * 18L); p.animateTo(1f, DshMotion.smooth()) }
    return graphicsLayer { alpha = p.value; translationX = -(1f - p.value) * 14.dp.toPx() }
}

/** `.dlink`: иконка, название, число справа. */
@Composable
private fun DLink(icon: ImageVector, title: String, count: String?, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (accent) c.accent else c.t2, modifier = Modifier.size(22.dp))
        Text(title, style = body(14.5.sp, 18.sp, FontWeight.Medium), color = if (accent) c.accent else c.t1, modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = body(12.5.sp, 16.sp), color = c.t3)
    }
}

/** `.dgroup`: «ЗАКРЕПЛЕНО», «СЕГОДНЯ»… */
@Composable
private fun Group(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = body(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = Dsh.colors.t3,
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * `.sess`: название 14,5/500 и строка состояния (агент работает — акцентом с «дышащей»
 * точкой, ждёт решения — жёлтым), время справа. Долгое нажатие — действия.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessRow(s: DrawerSession, on: Boolean, snippet: String?, onSelect: (String) -> Unit, onMenu: (DrawerSession) -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (on) c.s2 else Color.Transparent)
            .combinedClickable(onClick = { onSelect(s.id) }, onLongClick = { onMenu(s) }, role = Role.Button)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title, style = body(14.5.sp, 17.4.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (line, color) = when {
                s.waiting -> stringResource(R.string.dw_waiting) to c.warn
                s.running -> stringResource(R.string.dw_running) to c.accent
                !snippet.isNullOrBlank() -> snippet to c.t3
                else -> null to c.t3
            }
            if (line != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.waiting || s.running) {
                    val pulse = if (on && s.running) {
                        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sd")
                        t.animateFloat(1f, 0.55f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(550), androidx.compose.animation.core.RepeatMode.Reverse), label = "s").value
                    } else 1f
                    Box(Modifier.padding(end = 6.dp).size(6.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(CircleShape).background(color))
                }
                Text(line, style = body(12.5.sp, 15.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s.time.isNotBlank()) Text(s.time, style = body(12.sp, 15.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Папка проекта в меню и её сессии. */
data class WorkspaceFolder(
    val id: String,
    val name: String,
    val path: String = "",
    val sessions: List<SessionDisplayItem> = emptyList(),
    val isExpanded: Boolean = true,
)

/** Сессия Host в списке проекта. */
data class SessionDisplayItem(
    val sessionId: String,
    val title: String,
    val timeAgo: String,
    val isActiveDot: Boolean = false,
    val isRunning: Boolean = false,
    val hasUnread: Boolean = false,
    val updatedAt: Long = 0L,
    val archived: Boolean = false,
    val pinned: Boolean = false,
)

/**
 * Лист «Проект» (нажатие на `.proj`): папки DSH-Workspace — выбранная с галочкой,
 * ниже «Новый проект», для выбранной — «Переименовать», «Выше»/«Ниже» и «Удалить».
 */
@Composable
fun ColumnScope.ProjectSheetBody(
    projects: List<WorkspaceFolder>,
    selectedName: String,
    displayName: (String) -> String,
    onSelect: (WorkspaceFolder) -> Unit,
    onAdd: () -> Unit,
    onRename: (WorkspaceFolder) -> Unit,
    onMove: (WorkspaceFolder, Int) -> Unit,
    onDelete: (WorkspaceFolder) -> Unit,
) {
    val c = Dsh.colors
    DshSheetTitle(stringResource(R.string.dw_project))
    if (projects.isNotEmpty()) DshSheetList {
        projects.forEachIndexed { idx, f ->
            DshSheetItem(
                DshGlyphs.Folder, displayName(f.name), { onSelect(f) }, first = idx == 0,
                sub = pluralStringResource(R.plurals.dw_sessions, f.sessions.size, f.sessions.size),
                trailing = if (f.name == selectedName) ({ Icon(DshGlyphs.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) }) else null,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    DshSheetList { DshSheetItem(DshGlyphs.Plus, stringResource(R.string.dw_new_project), onAdd, first = true) }
    val current = projects.firstOrNull { it.name == selectedName }
    if (current != null) {
        val index = projects.indexOf(current)
        Spacer(Modifier.height(10.dp))
        DshSheetList {
            DshSheetItem(DshGlyphs.Pencil, stringResource(R.string.dw_rename), { onRename(current) }, first = true)
            if (index > 0) DshSheetItem(DshGlyphs.Up, stringResource(R.string.dw_move_up), { onMove(current, -1) })
            if (index < projects.lastIndex) DshSheetItem(DshGlyphs.Down, stringResource(R.string.dw_move_down), { onMove(current, 1) })
        }
        Spacer(Modifier.height(10.dp))
        DshSheetList { DshSheetItem(DshGlyphs.Trash, stringResource(R.string.dw_delete_project), { onDelete(current) }, first = true, danger = true) }
    }
}

/** Лист действий с сессией (долгое нажатие на `.sess`). */
@Composable
fun ColumnScope.SessionSheetBody(
    session: DrawerSession,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
) {
    DshSheetTitle(session.title)
    DshSheetList {
        DshSheetItem(DshGlyphs.Pencil, stringResource(R.string.dw_rename), onRename, first = true)
        DshSheetItem(DshGlyphs.Pin, stringResource(if (session.pinned) R.string.dw_unpin else R.string.dw_pin), onPin)
        DshSheetItem(DshGlyphs.Git, stringResource(R.string.dw_fork), onFork, sub = stringResource(R.string.dw_fork_sub))
    }
    Spacer(Modifier.height(10.dp))
    DshSheetList {
        DshSheetItem(DshGlyphs.Box, stringResource(if (session.archived) R.string.dw_unarchive else R.string.dw_to_archive), onArchive, first = true)
    }
}

/** Время в строке сессии, как `.tm`: сегодня и вчера — «09:20», на этой неделе — «пн», раньше — «12 окт». */
fun drawerTime(updatedAt: Long, now: Long = System.currentTimeMillis(), locale: java.util.Locale = java.util.Locale.getDefault()): String {
    if (updatedAt <= 0L) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    val pattern = when {
        updatedAt >= today - 24L * 60 * 60 * 1000 -> "HH:mm"
        updatedAt >= today - 6L * 24 * 60 * 60 * 1000 -> "EEE"
        else -> "d MMM"
    }
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(updatedAt)).trimEnd('.').lowercase(locale)
}
