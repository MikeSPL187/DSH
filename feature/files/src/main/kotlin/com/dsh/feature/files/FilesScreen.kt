package com.dsh.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshButton
import com.dsh.core.designsystem.DshButtonKind
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshMotion
import com.dsh.core.designsystem.DshSheetHint
import com.dsh.core.designsystem.DshSheetItem
import com.dsh.core.designsystem.DshSheetList
import com.dsh.core.designsystem.DshSheetScaffold
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.DshSourceTiles
import com.dsh.core.designsystem.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Что открыто в стеке экрана «Файлы». */
private sealed interface Entry {
    data class Dir(val path: String) : Entry
    data class Doc(val path: String) : Entry
    data class Diff(val path: String) : Entry
    data object Changes : Entry
    data object Search : Entry
}

/** Открытый лист. */
private sealed interface Sheet {
    data class Actions(val path: String) : Sheet
    data object Add : Sheet
    data class Rename(val path: String) : Sheet
    data class Delete(val path: String) : Sheet
    data class NewItem(val folder: Boolean) : Sheet
    data object Clone : Sheet
}

/**
 * Экран «Файлы» — точная копия `#screen-files` прототипа.
 *
 * Вкладки «Файлы» и «Изменения», недавние файлы, папки с крошками, просмотр файла
 * с подсветкой, дифф с отменой изменения, поиск по проекту, листы действий и «Добавить в папку».
 *
 * @param root корень проекта (папка с git).
 * @param rootLabel имя проекта для шапки и крошек.
 * @param refreshKey меняется, когда файлы на диске изменились (наблюдатель в приложении).
 * @param git git проекта; null — Linux ещё не готов.
 * @param onClose «Назад» из корня.
 * @param onAttach прикрепить файл к сообщению и открыть чат.
 * @param onAsk спросить агента о файле (прикрепить и подставить вопрос).
 * @param onReview отправить агенту просьбу проверить изменения (новая сессия) и открыть чат.
 * @param commitMessage написать сообщение коммита по текущим изменениям; null — недоступно.
 *   Возвращает текст или причину, почему не вышло.
 * @param onShare системное «Поделиться».
 * @param onImport импорт с телефона в папку.
 * @param onToast короткое сообщение внизу.
 */
@Composable
fun FilesScreen(
    root: File,
    rootLabel: String,
    refreshKey: Int,
    git: FilesGit?,
    onClose: () -> Unit,
    onAttach: (File) -> Unit,
    onAsk: (File) -> Unit,
    onReview: (String) -> Unit,
    commitMessage: (suspend (GitInfo) -> Result<String>)?,
    onShare: (File) -> Unit,
    onImport: (File) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    /** Оформленный Markdown (рендерер чата); null — файлы .md только кодом. */
    markdown: (@Composable (String) -> Unit)? = null,
    /** Открыть HTML-файл живой страницей во встроенном браузере; null — кнопки нет. */
    onOpenInBrowser: ((File) -> Unit)? = null,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    var stack by remember { mutableStateOf(listOf<Entry>(if (initialTab == 0) Entry.Dir("") else Entry.Changes)) }
    var forward by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val version = refreshKey + tick

    val gitInfo by produceState<GitInfo?>(null, git, version) { value = runCatching { git?.status() }.getOrNull() }
    val changes = gitInfo?.changes.orEmpty()
    val changedPaths = remember(changes) { changes.associateBy { it.path } }

    fun push(e: Entry) { forward = true; stack = stack + e }
    fun pop() {
        if (stack.size <= 1) onClose() else { forward = false; stack = stack.dropLast(1) }
    }
    fun setTab(i: Int) {
        tab = i
        forward = true
        stack = listOf(if (i == 0) Entry.Dir("") else Entry.Changes)
    }
    fun file(path: String) = if (path.isEmpty()) root else File(root, path)
    fun refresh() { tick++ }

    val revertedText = stringResource(R.string.fs_reverted)
    val top = stack.last()
    val searching = top == Entry.Search
    BackHandler(enabled = sheet == null && stack.size > 1) { pop() }

    DshSheetScaffold(
        sheet = sheet?.let { s -> { SheetBody(s, root, changedPaths, ::file, onDone = { sheet = null }, onSheet = { sheet = it }, git, onAsk, onAttach, onShare, onImport, onToast, ::refresh, currentDir = (stack.lastOrNull { it is Entry.Dir } as? Entry.Dir)?.path ?: "") } },
        onDismiss = { sheet = null },
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
            // `.sbar`: «Назад», заголовок с подписью, поиск и «+».
            val (title, sub) = titles(top, root, rootLabel, gitInfo)
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconBtn(DshGlyphs.Left, stringResource(R.string.fs_back)) {
                    if (searching && stack.size <= 1) setTab(tab) else pop()
                }
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(title, style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 19.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, lineHeight = 15.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val canFind = top is Entry.Dir || top == Entry.Changes
                IconBtn(DshGlyphs.Search, stringResource(R.string.fs_find), visible = canFind) { query = ""; push(Entry.Search) }
                IconBtn(DshGlyphs.Plus, stringResource(R.string.fs_add), visible = top is Entry.Dir) { sheet = Sheet.Add }
            }
            // `.fs-head`: вкладки или поле поиска.
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                if (searching) {
                    SearchField(query, { query = it }, onClose = { setTab(tab) })
                } else {
                    Tabs(tab, changes.size, ::setTab)
                }
            }
            AnimatedContent(
                targetState = stack,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val sameRoot = initialState.first()::class == targetState.first()::class && initialState.size != targetState.size
                    if (!sameRoot) {
                        (fadeIn(DshMotion.smooth()) + slideInVertically(DshMotion.smooth()) { it / 40 }) togetherWith fadeOut(DshMotion.snappy())
                    } else if (forward) {
                        slideInHorizontally(DshMotion.smooth()) { it } togetherWith
                            (slideOutHorizontally(DshMotion.smooth()) { -it * 28 / 100 } + fadeOut(DshMotion.smooth(), targetAlpha = 0.6f))
                    } else {
                        (slideInHorizontally(DshMotion.smooth()) { -it * 28 / 100 } + fadeIn(DshMotion.smooth(), initialAlpha = 0.6f)) togetherWith
                            slideOutHorizontally(DshMotion.smooth()) { it }
                    }
                },
                label = "fs-view",
            ) { s ->
                when (val e = s.last()) {
                    is Entry.Dir -> DirView(root, rootLabel, e.path, version, changedPaths, onOpen = { f -> push(if (f.isDirectory) Entry.Dir(relPath(root, f)) else Entry.Doc(relPath(root, f))) }, onCrumb = { p -> forward = false; stack = stack.takeWhile { it !is Entry.Dir || (it as Entry.Dir).path != p } + Entry.Dir(p) }, onMore = { sheet = Sheet.Actions(it) })
                    is Entry.Doc -> DocView(file(e.path), e.path, changedPaths[e.path], onDiff = { push(Entry.Diff(e.path)) }, onAsk = { onAsk(file(e.path)) }, onAttach = { onAttach(file(e.path)) }, onMore = { sheet = Sheet.Actions(e.path) }, markdown = markdown, onOpenInBrowser = onOpenInBrowser)
                    is Entry.Diff -> DiffView(e.path, changedPaths[e.path], git, version, onRevert = { ch ->
                        scope.launch {
                            val ok = runCatching { git?.revert(ch) ?: false }.getOrDefault(false)
                            if (ok) { onToast(revertedText); refresh(); pop() }
                        }
                    })
                    Entry.Changes -> ChangesView(gitInfo, git != null, onReview = onReview, commitMessage = commitMessage, onToast = onToast, onOpen = { push(Entry.Diff(it)) })
                    Entry.Search -> SearchView(root, query, version, changedPaths, onOpen = { f -> push(if (f.isDirectory) Entry.Dir(relPath(root, f)) else Entry.Doc(relPath(root, f))) }, onMore = { sheet = Sheet.Actions(it) })
                }
            }
        }
    }
}

@Composable
private fun titles(top: Entry, root: File, rootName: String, git: GitInfo?): Pair<String, String> {
    return when (top) {
        is Entry.Dir -> if (top.path.isEmpty()) {
            stringResource(R.string.fs_title) to "$rootName · ${objects(listDir(root).size)}"
        } else {
            File(top.path).name to objects(listDir(File(root, top.path)).size)
        }
        is Entry.Doc -> {
            val f = File(root, top.path)
            val dir = if (top.path.contains('/')) top.path.substringBeforeLast('/') + "/" else rootName
            File(top.path).name to "$dir · ${fileMeta(f)}"
        }
        is Entry.Diff -> stringResource(R.string.fs_diff_title) to top.path
        Entry.Changes -> stringResource(R.string.fs_changes_title) to (git?.branch?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.fs_changes_sub, rootName, it) } ?: rootName)
        Entry.Search -> stringResource(R.string.fs_search_title) to stringResource(R.string.fs_search_sub)
    }
}

@Composable
private fun objects(n: Int) = pluralStringResource(R.plurals.fs_objects, n, n)

/** «3,2 КБ · вчера». */
@Composable
internal fun fileMeta(f: File): String {
    val context = LocalContext.current
    val size = android.text.format.Formatter.formatShortFileSize(context, f.length())
    return "$size · ${relativeTime(f.lastModified())}"
}

/** «только что», «5 мин назад», «сегодня, 09:30», «вчера», «3 дня назад», «неделю назад»… */
@Composable
internal fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = (now - ms).coerceAtLeast(0)
    val min = diff / 60_000
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    val days = ((today - ms) / 86_400_000 + 1).toInt()
    return when {
        min < 1 -> stringResource(R.string.fs_now)
        min < 60 -> pluralStringResource(R.plurals.fs_min_ago, min.toInt(), min.toInt())
        ms >= today -> stringResource(R.string.fs_today, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms)))
        days <= 1 -> stringResource(R.string.fs_yesterday)
        days < 7 -> pluralStringResource(R.plurals.fs_days_ago, days, days)
        days < 14 -> stringResource(R.string.fs_week)
        days < 30 -> pluralStringResource(R.plurals.fs_weeks_ago, days / 7, days / 7)
        days < 60 -> stringResource(R.string.fs_month)
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    }
}

// ---------------------------------------------------------------------------
// Шапка
// ---------------------------------------------------------------------------

/** `.iconbtn` 44 dp; невидимая кнопка держит место, как `visibility: hidden`. */
@Composable
private fun IconBtn(icon: ImageVector, label: String, visible: Boolean = true, size: Dp = 44.dp, tint: Color = Dsh.colors.t1, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .clickable(interaction, ripple(), enabled = visible, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (size < 44.dp) 18.dp else 22.dp))
    }
}

/** `.segm.fs-tabs`: «Файлы» и «Изменения» со счётчиком, бегунок едет пружиной. */
@Composable
private fun Tabs(tab: Int, count: Int, onTab: (Int) -> Unit) {
    val c = Dsh.colors
    BoxWithConstraints(
        Modifier
            .padding(top = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.s2)
            .padding(3.dp),
    ) {
        val w = maxWidth / 2
        val x by animateDpAsState(if (tab == 0) 0.dp else w, DshMotion.snappy(), label = "fs-thumb")
        Box(
            Modifier
                .offset(x = x)
                .size(width = w, height = 40.dp)
                .shadow(2.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(RoundedCornerShape(11.dp))
                .background(c.s1),
        )
        Row(Modifier.fillMaxWidth()) {
            listOf(stringResource(R.string.fs_tab_files), stringResource(R.string.fs_tab_changes)).forEachIndexed { i, label ->
                val on = i == tab
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(role = Role.Tab) { if (!on) onTab(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) c.t1 else c.t2)
                    if (i == 1 && count > 0) {
                        Box(
                            Modifier.padding(start = 6.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(10.dp)).background(c.accent).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$count", style = TextStyle(fontFamily = DshFonts.body, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = c.onAccent)
                        }
                    }
                }
            }
        }
    }
}

/** `.fs-search`: поле 44 dp с кнопкой закрытия. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = Dsh.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.padding(top = 2.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(c.s2).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DshGlyphs.Search, null, tint = c.t3, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            val style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, color = c.t1)
            if (query.isEmpty()) Text(stringResource(R.string.fs_search_ph), style = style.copy(color = c.t3), maxLines = 1)
            BasicTextField(query, onQuery, singleLine = true, textStyle = style, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        IconBtn(DshGlyphs.X, stringResource(R.string.fs_search_close), size = 40.dp, tint = c.t3, onClick = onClose)
    }
}

// ---------------------------------------------------------------------------
// Строки и плитки
// ---------------------------------------------------------------------------

private val Mono = TextStyle(fontFamily = DshFonts.mono)

/** `.ft`: плитка типа файла 34 dp. */
@Composable
internal fun FileTile(name: String, dir: Boolean, size: Dp = 34.dp) {
    val c = Dsh.colors
    // «.gitignore» — без расширения, как в прототипе (TXT).
    val ext = if (name.lastIndexOf('.') > 0) name.substringAfterLast('.').lowercase() else ""
    val (bg, ink) = when {
        dir -> c.accentSoft to c.accent
        ext == "kt" || ext == "kts" -> lerp(c.s1, Color(0xFF7F52FF), 0.16f) to lerp(c.t1, Color(0xFF7F52FF), 0.65f)
        ext == "toml" || ext == "xml" -> c.warnSoft to c.warn
        ext == "md" -> lerp(c.s1, Color(0xFF2F7DE1), 0.14f) to lerp(c.t1, Color(0xFF2F7DE1), 0.65f)
        ext == "sh" -> c.okSoft to c.ok
        else -> c.s2 to c.t2
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        if (dir) {
            Icon(DshGlyphs.Folder, null, tint = ink, modifier = Modifier.size(18.dp))
        } else {
            val label = when (ext) {
                "properties" -> "PROP"
                "" -> "TXT"
                else -> ext.uppercase().take(4)
            }
            Text(label, style = Mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em), color = ink, maxLines = 1)
        }
    }
}

/** `.gl`: группа строк на s1. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(18.dp)), content = content)
}

/** `.fr`: строка файла или папки. */
@Composable
private fun FileRow(
    name: String,
    meta: String,
    dir: Boolean,
    first: Boolean,
    change: GitChange?,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Dsh.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileTile(name, dir)
            Column(Modifier.weight(1f)) {
                Text(name, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (change != null && !dir && trailing == null) {
                val added = change.kind == ChangeKind.ADDED
                Text(
                    stringResource(if (added) R.string.fs_badge_new else R.string.fs_badge_mod),
                    style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                    color = if (added) c.ok else c.warn,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (added) c.okSoft else c.warnSoft).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            trailing?.invoke()
            when {
                dir || onMore == null -> Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                else -> IconBtn(DshGlyphs.More, stringResource(R.string.fs_more), size = 40.dp, tint = c.t3, onClick = onMore)
            }
        }
    }
}

/** `.fs-sec`: подпись раздела с числом справа. */
@Composable
private fun Section(label: String, trailing: String? = null) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp)) {
        Text(label.uppercase(), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, letterSpacing = 0.06.em), color = c.t3, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp), color = c.t3)
    }
}

/** `.fs-empty`: пустое состояние по центру. */
@Composable
private fun Empty(text: String, hint: String? = null, icon: ImageVector? = null, iconTint: Color = Dsh.colors.ok) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.padding(bottom = 10.dp).size(32.dp))
        }
        Text(text, style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, lineHeight = 20.sp), color = c.t3, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, lineHeight = 17.sp), color = c.t3, textAlign = TextAlign.Center)
    }
}

/** Кол-во объектов в папке и сколько из них изменено. */
@Composable
private fun dirMeta(root: File, dir: File, changed: Map<String, GitChange>): String {
    val n = listDir(dir).size
    val prefix = relPath(root, dir) + "/"
    val k = changed.keys.count { it.startsWith(prefix) }
    return objects(n) + if (k > 0) " · " + stringResource(R.string.fs_changed_in, k) else ""
}

// ---------------------------------------------------------------------------
// Виды
// ---------------------------------------------------------------------------

private val ViewPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp)

/** Папка: в корне — недавние и «Все файлы», внутри — крошки пути. */
@Composable
private fun DirView(
    root: File,
    rootLabel: String,
    path: String,
    version: Int,
    changed: Map<String, GitChange>,
    onOpen: (File) -> Unit,
    onCrumb: (String) -> Unit,
    onMore: (String) -> Unit,
) {
    val c = Dsh.colors
    val dir = if (path.isEmpty()) root else File(root, path)
    val items by produceState(emptyList<File>(), dir, version) { value = withContext(Dispatchers.IO) { listDir(dir) } }
    val recent by produceState(emptyList<File>(), root, version, path) {
        value = if (path.isEmpty()) withContext(Dispatchers.IO) { walkFiles(root).sortedByDescending { it.lastModified() }.take(4) } else emptyList()
    }
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = ViewPadding) {
        if (path.isNotEmpty()) {
            item(key = "crumbs") { Crumbs(rootLabel, path, onCrumb) }
        } else if (recent.isNotEmpty()) {
            item(key = "recent-h") { Section(stringResource(R.string.fs_recent)) }
            item(key = "recent") {
                LazyRow(
                    Modifier.padding(bottom = 4.dp).fillMaxWidth().offset(x = 0.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recent, key = { it.absolutePath }) { f -> RecentCard(f, changed[relPath(root, f)] != null) { onOpen(f) } }
                }
            }
        }
        if (path.isEmpty()) item(key = "all-h") { Section(stringResource(R.string.fs_all), objects(items.size)) }
        if (items.isEmpty()) {
            item(key = "empty") { Empty(stringResource(R.string.fs_empty_dir), stringResource(R.string.fs_empty_dir_hint)) }
        } else {
            item(key = "list") {
                Group {
                    items.forEachIndexed { i, f ->
                        val rel = relPath(root, f)
                        FileRow(
                            name = f.name,
                            meta = if (f.isDirectory) dirMeta(root, f, changed) else fileMeta(f),
                            dir = f.isDirectory,
                            first = i == 0,
                            change = changed[rel],
                            onClick = { onOpen(f) },
                            onMore = { onMore(rel) },
                        )
                    }
                }
            }
        }
        item(key = "nav") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

/** `.crumbs`: путь от корня, последний — жирнее. */
@Composable
private fun Crumbs(rootName: String, path: String, onCrumb: (String) -> Unit) {
    val c = Dsh.colors
    val parts = path.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.padding(bottom = 10.dp).fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val all = listOf("" to rootName) + parts.mapIndexed { i, p -> parts.take(i + 1).joinToString("/") to p }
        all.forEachIndexed { i, (p, label) ->
            val last = i == all.lastIndex
            if (i > 0) Icon(DshGlyphs.xs("right"), null, tint = c.t3, modifier = Modifier.size(15.dp))
            Box(
                Modifier.height(40.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !last, role = Role.Button) { onCrumb(p) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, fontWeight = if (last) FontWeight.Medium else FontWeight.Normal), color = if (last) c.t1 else c.t2, maxLines = 1)
            }
        }
    }
}

/** `.rc`: карточка недавнего файла 168 dp. */
@Composable
private fun RecentCard(f: File, changed: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(168.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(18.dp))
            .background(c.s1)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        FileTile(f.name, false)
        Text(f.name, style = Mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(relativeTime(f.lastModified()), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp), color = if (changed) c.accent else c.t3, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Просмотр файла: плашка изменений, «Спросить / В чат / Ещё», код с подсветкой. */
@Composable
private fun DocView(
    f: File,
    path: String,
    change: GitChange?,
    onDiff: () -> Unit,
    onAsk: () -> Unit,
    onAttach: () -> Unit,
    onMore: () -> Unit,
    markdown: (@Composable (String) -> Unit)?,
    onOpenInBrowser: ((File) -> Unit)?,
) {
    val c = Dsh.colors
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val kind = previewKindOf(f.name)
    if (kind == PreviewKind.PDF) {
        PdfView(f) { DocHeader(change, onDiff, onAsk, onAttach, onMore) }
        return
    }
    val content by produceState<ViewText?>(null, f) {
        value = withContext(Dispatchers.IO) {
            when {
                ext in ImageExt -> null
                !looksText(f) -> ViewText(emptyList(), false)
                else -> runCatching { readViewText(f) }.getOrDefault(ViewText(emptyList(), false))
            }
        }
    }
    // Markdown и HTML открываются видом, код — по «Код»; выбор живёт, пока открыт этот файл.
    var showCode by remember(path) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        Spacer(Modifier.height(4.dp))
        DocHeader(change, onDiff, onAsk, onAttach, onMore)
        val data = content
        val previewable = data != null && data.lines.isNotEmpty() &&
            ((kind == PreviewKind.MARKDOWN && markdown != null) || kind == PreviewKind.HTML)
        if (previewable) PreviewSeg(
            stringResource(if (kind == PreviewKind.HTML) R.string.fs_view_page else R.string.fs_view_doc),
            stringResource(R.string.fs_view_code), showCode,
        ) { showCode = it }
        when {
            ext in ImageExt -> ImagePreview(f)
            data == null -> Unit
            data.lines.isEmpty() && !data.truncated && f.length() > 0 -> Empty(stringResource(R.string.fs_binary))
            previewable && !showCode && kind == PreviewKind.MARKDOWN -> Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
            ) { markdown!!(data.lines.joinToString("\n")) }
            previewable && !showCode && kind == PreviewKind.HTML -> {
                HtmlStaticPreview(data.lines.joinToString("\n"))
                if (onOpenInBrowser != null) Row(
                    Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.s2)
                        .clickable(role = Role.Button) { onOpenInBrowser(f) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(DshGlyphs.Globe, null, tint = c.t1, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.fs_open_browser), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.5.sp), color = c.t1)
                }
            }
            else -> {
                CodeBlock(data.lines.ifEmpty { listOf("") }, ext)
            }
        }
        if (data != null && data.truncated) Text(stringResource(R.string.fs_too_big, data.lines.size), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp), color = c.t3, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** `.fv-banner` (файл изменён) и `.fv-acts` — «Спросить», «В чат», «Ещё». */
@Composable
private fun DocHeader(change: GitChange?, onDiff: () -> Unit, onAsk: () -> Unit, onAttach: () -> Unit, onMore: () -> Unit) {
    val c = Dsh.colors
    if (change != null) {
        // `.fv-banner`
        Row(
            Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.warnSoft)
                .clickable(role = Role.Button, onClick = onDiff)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(DshGlyphs.Pencil, null, tint = c.warn, modifier = Modifier.size(18.dp))
            Text(
                buildAnnotatedString {
                    append(stringResource(if (change.kind == ChangeKind.ADDED) R.string.fs_banner_new else R.string.fs_banner_mod))
                    append(" · ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stat(change)) }
                },
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp),
                color = c.warn,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.fs_banner_open), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp), color = c.warn)
            Icon(DshGlyphs.xs("right"), null, tint = c.warn, modifier = Modifier.size(15.dp))
        }
    }
    // `.fv-acts`
    Row(Modifier.padding(bottom = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActBtn(DshGlyphs.xs("spark"), stringResource(R.string.fs_ask), primary = true, onClick = onAsk)
        ActBtn(DshGlyphs.xs("at"), stringResource(R.string.fs_to_chat), onClick = onAttach)
        ActBtn(DshGlyphs.xs("more"), stringResource(R.string.fs_more_btn), onClick = onMore)
    }
}

/** PDF: шапка, строка «N страниц» (и «Сбросить», когда увеличено), страницы по требованию со сведением пальцев. */
@Composable
private fun PdfView(f: File, header: @Composable () -> Unit) {
    val c = Dsh.colors
    // Loading → null, ошибка → Result.failure; документ закрывается, когда экран уходит.
    val doc by produceState<Result<PdfDoc>?>(null, f) {
        val opened = withContext(Dispatchers.IO) { PdfDoc.open(f) }
        value = opened?.let { Result.success(it) } ?: Result.failure(IllegalStateException("pdf"))
        awaitDispose { opened?.close() }
    }
    var scale by remember(f) { mutableStateOf(1f) }
    var shift by remember(f) { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            header()
            val d = doc?.getOrNull()
            if (d != null) Row(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp).fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.fs_pdf_pages, d.pages, d.pages), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t3, modifier = Modifier.weight(1f))
                if (scale > 1.01f) Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.s2).clickable(role = Role.Button) { scale = 1f; shift = 0f }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.fs_pdf_reset, (scale * 100).toInt()), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.sp), color = c.t1) }
                else Text(stringResource(R.string.fs_pdf_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t3)
            }
        }
        when (val r = doc) {
            null -> Unit
            else -> r.fold(
                onSuccess = { d ->
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().clipToBounds()
                            .pdfZoom({ scale }, { shift }) { s, o -> scale = s; shift = o }
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = shift },
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(d.pages, key = { it }) { PdfPage(d, it) }
                        item { Spacer(Modifier.navigationBarsPadding()) }
                    }
                },
                onFailure = { Box(Modifier.padding(16.dp)) { Empty(stringResource(R.string.fs_pdf_failed)) } },
            )
        }
    }
}

/** Кнопка `.fv-acts`: 44 dp, s1 с обводкой; главная — акцент. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ActBtn(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .weight(1f)
            .height(44.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(13.dp))
            .background(if (primary) c.accent else c.s1)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(13.dp)))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (primary) c.onAccent else c.t1
        Icon(icon, null, tint = ink, modifier = Modifier.size(15.dp))
        Text(label, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp), color = ink, maxLines = 1)
    }
}

/** «+1 −1». */
private fun stat(ch: GitChange): String = buildString {
    append("+").append(ch.added)
    if (ch.deleted > 0) append(" −").append(ch.deleted)
}

/** `.code`: номера строк и текст с подсветкой, общий горизонтальный сдвиг. */
@Composable
private fun CodeBlock(lines: List<String>, ext: String) {
    val c = Dsh.colors
    val style = Mono.copy(fontSize = 12.5.sp, lineHeight = 21.25.sp)
    val numbers = remember(lines) { (1..lines.size).joinToString("\n") }
    // Подсветка — в фоне: на 3000 строк регулярные выражения в composition заметно тормозят кадр.
    val text by produceState(AnnotatedString(lines.joinToString("\n")), lines, ext, c) {
        value = withContext(Dispatchers.Default) { highlight(lines, ext, c) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.termBg)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp),
    ) {
        Text(numbers, style = style.copy(textAlign = TextAlign.End), color = c.t3.copy(alpha = 0.8f), modifier = Modifier.width(38.dp).padding(end = 12.dp))
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Text(text, style = style, color = c.termText, softWrap = false, modifier = Modifier.padding(end = 14.dp))
        }
    }
}

/** Подсветка как `hl()` в прототипе: комментарии, строки, ключевые слова, секции TOML, заголовки Markdown. */
private fun highlight(lines: List<String>, ext: String, c: com.dsh.core.designsystem.DshColors): AnnotatedString = buildAnnotatedString {
    val token = Regex("(\"[^\"]*\"|'[^']*')|\\b(package|import|class|object|interface|override|fun|val|var|set|if|else|then|fi|for|while|return|echo|mkdir|tar|true|false|null|def|from|const|let|function|export)\\b|^(\\[[\\w.-]+\\])")
    lines.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        val comment = Regex("^\\s*(#|//)").containsMatchIn(line) && !(ext == "md" && line.startsWith("#"))
        when {
            comment -> withStyle(SpanStyle(color = c.t3, fontStyle = FontStyle.Italic)) { append(line) }
            ext == "md" && line.startsWith("#") -> withStyle(SpanStyle(color = c.accent)) { append(line) }
            else -> {
                var last = 0
                for (m in token.findAll(line)) {
                    append(line.substring(last, m.range.first))
                    val color = if (m.groups[1] != null) c.ok else c.accent
                    withStyle(SpanStyle(color = color)) { append(m.value) }
                    last = m.range.last + 1
                }
                append(line.substring(last))
            }
        }
    }
}

@Composable
private fun ImagePreview(f: File) {
    val c = Dsh.colors
    val bitmap by produceState<android.graphics.Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Сначала только размер (G-15): фото 108 Мп с фиксированным inSampleSize=2 — 108 МБ в памяти
                // и «Canvas: trying to draw too large bitmap» в RenderThread.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_SIDE)
                }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)))
    }
}

/** Дифф файла и «Отменить изменение». */
@Composable
private fun DiffView(path: String, change: GitChange?, git: FilesGit?, version: Int, onRevert: (GitChange) -> Unit) {
    val c = Dsh.colors
    val rows by produceState<List<DiffRow>?>(null, path, version) { value = runCatching { git?.diff(path) }.getOrNull().orEmpty() }
    val name = path.substringAfterLast('/')
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        // `.fv-head`
        Row(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileTile(name, false)
            Column(Modifier.weight(1f)) {
                Text(name, style = Mono.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        append(path)
                        if (change != null) {
                            append(" · ")
                            withStyle(SpanStyle(color = c.ok)) { append("+${change.added}") }
                            if (change.deleted > 0) { append(" "); withStyle(SpanStyle(color = c.danger)) { append("−${change.deleted}") } }
                        }
                    },
                    style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp),
                    color = c.t3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val list = rows
        if (list != null && list.isNotEmpty()) {
            val style = Mono.copy(fontSize = 12.5.sp, lineHeight = 21.25.sp)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.termBg).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    list.forEach { r ->
                        val (bg, ink, num, text) = when (r) {
                            is DiffRow.Hunk -> Quad(Color.Transparent, c.t3, "", r.text)
                            is DiffRow.Line -> when (r.sign) {
                                '+' -> Quad(c.okSoft, c.ok, "${r.number}", "+ ${r.text}")
                                '-' -> Quad(c.dangerSoft, c.danger, "${r.number}", "- ${r.text}")
                                else -> Quad(Color.Transparent, c.termText, "${r.number}", "  ${r.text}")
                            }
                        }
                        Row(Modifier.background(bg).padding(end = 14.dp)) {
                            Text(num, style = style.copy(textAlign = TextAlign.End), color = c.t3.copy(alpha = 0.8f), modifier = Modifier.width(38.dp).padding(end = 12.dp))
                            Text(text, style = style, color = ink, softWrap = false)
                        }
                    }
                }
            }
        }
        if (change != null) {
            // `.ch-acts`
            DshButton(
                stringResource(R.string.fs_revert),
                { onRevert(change) },
                Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp),
                kind = DshButtonKind.Ghost,
                icon = DshGlyphs.Undo,
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private data class Quad(val bg: Color, val ink: Color, val num: String, val text: String)

/** Вкладка «Изменения»: сводка с веткой, «Проверить с агентом», список файлов. */
@Composable
private fun ChangesView(
    info: GitInfo?,
    gitReady: Boolean,
    onReview: (String) -> Unit,
    commitMessage: (suspend (GitInfo) -> Result<String>)?,
    onToast: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val c = Dsh.colors
    val files = info?.changes.orEmpty()
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        when {
            info == null -> Empty(stringResource(if (gitReady) R.string.fs_no_changes else R.string.fs_no_git), icon = if (gitReady) DshGlyphs.Check else null)
            files.isEmpty() -> Empty(stringResource(R.string.fs_no_changes), icon = DshGlyphs.Check)
            else -> {
                val plus = files.sumOf { it.added.coerceAtLeast(0) }
                val minus = files.sumOf { it.deleted.coerceAtLeast(0) }
                Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (info.branch.isNotEmpty()) {
                            Row(
                                Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(c.s2).padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(DshGlyphs.xs("git"), null, tint = c.t1, modifier = Modifier.size(15.dp))
                                Text(info.branch, style = Mono.copy(fontSize = 12.sp), color = c.t1, maxLines = 1)
                            }
                        }
                        Text(stringResource(R.string.fs_uncommitted), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t2)
                    }
                    Text(
                        buildAnnotatedString {
                            append(pluralStringResource(R.plurals.fs_files_n, files.size, files.size))
                            append(" · ")
                            withStyle(SpanStyle(color = c.ok)) { append("+$plus") }
                            if (minus > 0) { append(" "); withStyle(SpanStyle(color = c.danger)) { append("−$minus") } }
                        },
                        style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
                        color = c.t1,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    Text(
                        stringResource(R.string.fs_changed_list, files.take(3).joinToString(", ") { it.path.substringAfterLast('/') } + if (files.size > 3) "…" else ""),
                        style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.sp),
                        color = c.t2,
                    )
                    val review = stringResource(R.string.fs_review_prompt)
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val copied = stringResource(R.string.fs_commit_copied)
                    val scope = rememberCoroutineScope()
                    var writing by remember { mutableStateOf(false) }
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DshButton(stringResource(R.string.fs_review), { onReview(review) }, Modifier.fillMaxWidth().height(48.dp), icon = DshGlyphs.Spark)
                        if (commitMessage != null) DshButton(
                            stringResource(if (writing) R.string.fs_commit_writing else R.string.fs_commit_msg),
                            {
                                writing = true
                                scope.launch {
                                    commitMessage(info)
                                        .onSuccess { msg ->
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg))
                                            onToast(copied.format(msg.lineSequence().first()))
                                        }
                                        .onFailure { onToast(it.message.orEmpty()) }
                                    writing = false
                                }
                            },
                            Modifier.fillMaxWidth().height(48.dp), kind = DshButtonKind.Ghost, enabled = !writing, icon = DshGlyphs.Msg,
                        )
                    }
                }
                Section(stringResource(R.string.fs_files), "${files.size}")
                Group {
                    files.forEachIndexed { i, ch ->
                        FileRow(
                            name = ch.path.substringAfterLast('/'),
                            meta = ch.path,
                            dir = false,
                            first = i == 0,
                            change = ch,
                            onClick = { onOpen(ch.path) },
                            onMore = null,
                            trailing = {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = c.ok)) { append("+${ch.added.coerceAtLeast(0)}") }
                                        if (ch.deleted > 0) { append(" "); withStyle(SpanStyle(color = c.danger)) { append("−${ch.deleted}") } }
                                    },
                                    style = Mono.copy(fontSize = 13.sp),
                                )
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Поиск по имени и пути во всём проекте. */
@Composable
private fun SearchView(root: File, query: String, version: Int, changed: Map<String, GitChange>, onOpen: (File) -> Unit, onMore: (String) -> Unit) {
    val c = Dsh.colors
    val q = query.trim()
    val all by produceState(emptyList<File>(), root, version) { value = withContext(Dispatchers.IO) { walkFiles(root) } }
    val hits = remember(all, q) { if (q.isEmpty()) emptyList() else all.filter { relPath(root, it).contains(q, ignoreCase = true) }.take(200) }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        when {
            q.isEmpty() -> Empty(stringResource(R.string.fs_search_start))
            hits.isEmpty() -> Empty(stringResource(R.string.fs_nothing, q), stringResource(R.string.fs_nothing_hint))
            else -> {
                Section(stringResource(R.string.fs_found), "${hits.size}")
                Group {
                    hits.forEachIndexed { i, f ->
                        val rel = relPath(root, f)
                        FileRow(f.name, rel, false, i == 0, changed[rel], onClick = { onOpen(f) }, onMore = { onMore(rel) })
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ---------------------------------------------------------------------------
// Листы
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.SheetBody(
    sheet: Sheet,
    root: File,
    changed: Map<String, GitChange>,
    file: (String) -> File,
    onDone: () -> Unit,
    onSheet: (Sheet) -> Unit,
    git: FilesGit?,
    onAsk: (File) -> Unit,
    onAttach: (File) -> Unit,
    onShare: (File) -> Unit,
    onImport: (File) -> Unit,
    onToast: (String) -> Unit,
    refresh: () -> Unit,
    currentDir: String,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    when (sheet) {
        is Sheet.Actions -> {
            val f = file(sheet.path)
            // `.fa-head`
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FileTile(f.name, f.isDirectory)
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = Mono.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.isDirectory) sheet.path else fileMeta(f), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp), color = c.t3, maxLines = 1)
                }
            }
            DshSheetList {
                DshSheetItem(DshGlyphs.Spark, stringResource(R.string.fs_fa_ask), { onDone(); onAsk(f) }, first = true)
                DshSheetItem(DshGlyphs.At, stringResource(R.string.fs_fa_attach), { onDone(); onAttach(f) })
                if (!f.isDirectory) DshSheetItem(DshGlyphs.Share, stringResource(R.string.fs_fa_share), { onDone(); onShare(f) })
                DshSheetItem(DshGlyphs.Pencil, stringResource(R.string.fs_fa_rename), { onSheet(Sheet.Rename(sheet.path)) })
            }
            DshSheetList(Modifier.padding(top = 10.dp)) {
                DshSheetItem(DshGlyphs.Trash, stringResource(R.string.fs_fa_delete), { onSheet(Sheet.Delete(sheet.path)) }, first = true, danger = true)
            }
        }
        Sheet.Add -> {
            DshSheetTitle(stringResource(R.string.fs_add_title))
            DshSourceTiles(
                listOf(
                    DshGlyphs.File to stringResource(R.string.fs_add_file),
                    DshGlyphs.Folder to stringResource(R.string.fs_add_folder),
                    DshGlyphs.Down to stringResource(R.string.fs_add_import),
                    DshGlyphs.Git to stringResource(R.string.fs_add_clone),
                ),
                onClick = { i ->
                    when (i) {
                        0 -> onSheet(Sheet.NewItem(folder = false))
                        1 -> onSheet(Sheet.NewItem(folder = true))
                        2 -> { onDone(); onImport(file(currentDir)) }
                        else -> onSheet(Sheet.Clone)
                    }
                },
                modifier = Modifier.padding(bottom = 4.dp),
            )
            DshSheetHint(stringResource(R.string.fs_add_hint))
        }
        is Sheet.NewItem -> NameForm(
            title = stringResource(if (sheet.folder) R.string.fs_new_folder else R.string.fs_new_file),
            initial = "",
            action = stringResource(R.string.fs_create),
            dir = file(currentDir),
        ) { name ->
            val target = File(file(currentDir), name)
            val ok = runCatching { if (sheet.folder) target.mkdirs() else target.createNewFile() }.getOrDefault(false)
            if (ok) { refresh(); onDone() }
        }
        is Sheet.Rename -> {
            val f = file(sheet.path)
            NameForm(stringResource(R.string.fs_rename_title), f.name, stringResource(R.string.fs_rename), f.parentFile ?: root, except = f.name) { name ->
                if (runCatching { f.renameTo(File(f.parentFile, name)) }.getOrDefault(false)) { refresh(); onDone() }
            }
        }
        is Sheet.Delete -> {
            val f = file(sheet.path)
            DshSheetTitle(stringResource(R.string.fs_delete_title, f.name))
            Text(
                stringResource(if (f.isDirectory) R.string.fs_delete_dir_lead else R.string.fs_delete_lead),
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, lineHeight = 21.75.sp),
                color = c.t2,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
            )
            val deleted = stringResource(R.string.fs_deleted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshButton(stringResource(R.string.fs_cancel), onDone, Modifier.weight(1f), kind = DshButtonKind.Ghost)
                DshButton(stringResource(R.string.fs_delete), {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { f.deleteRecursively() }.getOrDefault(false) }
                        if (ok) { onToast(deleted); refresh() }
                        onDone()
                    }
                }, Modifier.weight(1f), kind = DshButtonKind.Danger, icon = DshGlyphs.Trash)
            }
        }
        Sheet.Clone -> {
            var url by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val failed = stringResource(R.string.fs_clone_failed, "%s")
            val cloned = stringResource(R.string.fs_cloned)
            DshSheetTitle(stringResource(R.string.fs_clone_title))
            Field(url, { url = it; error = null }, stringResource(R.string.fs_url_ph), mono = true)
            if (error != null) Text(error!!, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            DshButton(
                stringResource(if (busy) R.string.fs_cloning else R.string.fs_clone),
                {
                    val dirName = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                    busy = true
                    scope.launch {
                        val target = if (currentDir.isEmpty()) dirName else "$currentDir/$dirName"
                        val r = git?.clone(url.trim(), target) ?: Result.failure(IllegalStateException("Linux"))
                        busy = false
                        r.onSuccess { onToast(cloned); refresh(); onDone() }.onFailure { error = failed.format(it.message.orEmpty()) }
                    }
                },
                Modifier.padding(top = 14.dp).fillMaxWidth(),
                enabled = !busy && git != null && url.trim().let { it.startsWith("https://") || it.startsWith("git@") },
                big = true,
            )
        }
    }
}

/** Лист с полем имени: создать файл/папку или переименовать. */
@Composable
private fun NameForm(title: String, initial: String, action: String, dir: File, except: String? = null, onSubmit: (String) -> Unit) {
    val c = Dsh.colors
    var name by remember { mutableStateOf(initial) }
    val clean = name.trim()
    val bad = clean.contains('/')
    val taken = clean.isNotEmpty() && clean != except && File(dir, clean).exists()
    DshSheetTitle(title)
    Field(name, { name = it }, stringResource(R.string.fs_name_ph), mono = true)
    if (bad || taken) {
        Text(
            stringResource(if (bad) R.string.fs_name_bad else R.string.fs_name_taken),
            style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp),
            color = c.danger,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
    DshButton(action, { onSubmit(clean) }, Modifier.padding(top = 14.dp).fillMaxWidth(), enabled = clean.isNotEmpty() && !bad && !taken && clean != except, big = true)
}

/** Поле ввода 54 dp, как `.ob-in`. */
@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, mono: Boolean = false) {
    val c = Dsh.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = TextStyle(fontFamily = if (mono) DshFonts.mono else DshFonts.body, fontSize = 15.sp, color = c.t1)
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).border(1.5.dp, c.accent, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.t3), maxLines = 1)
        BasicTextField(
            value,
            onValue,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}
