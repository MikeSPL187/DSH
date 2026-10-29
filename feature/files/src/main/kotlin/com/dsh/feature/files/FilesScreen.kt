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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.dsh.core.designsystem.kit.DeviceSignInSheetBody
import com.dsh.core.designsystem.DshSourceTiles
import com.dsh.core.designsystem.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Что открыто в стеке экрана «Файлы». */
private sealed interface Entry {
    data class Dir(val path: String) : Entry
    data class Doc(val path: String) : Entry
    data class Diff(val path: String) : Entry
    /** Правка файла. */
    data class Edit(val path: String) : Entry
    data object Changes : Entry
    data object Search : Entry
    /** Вся история ветки. */
    data object Log : Entry
    /** Коммит: шапка, «Спросить DSH», файлы. */
    data class Commit(val info: CommitInfo) : Entry
    /** Разница файла в коммите. */
    data class CommitDiff(val info: CommitInfo, val file: CommitFile) : Entry
}

/** Открытый лист. */
private sealed interface Sheet {
    data class Actions(val path: String) : Sheet
    data object Add : Sheet
    data class Rename(val path: String) : Sheet
    data class Delete(val path: String) : Sheet
    data class NewItem(val folder: Boolean) : Sheet
    data object Clone : Sheet
    data class Commit(val info: GitInfo) : Sheet
    /** Вход в GitHub; после входа — сразу действие, ради которого он понадобился. */
    data object GitHubLogin : Sheet
    /** Новая ветка для неотправленных коммитов. */
    data class Branch(val suggested: String, val base: String) : Sheet
    /** Запрос на слияние из текущей ветки. */
    data class Pull(val state: BranchState) : Sheet
    /** Выйти из правки без сохранения? */
    data object EditDiscard : Sheet
    /** Файл изменился извне, пока его правили. */
    data object EditConflict : Sheet
}

/** Картинки и PDF не правятся текстом; остальное решает [openForEdit] (не текст — честное объяснение). */
private val NOT_EDITABLE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "pdf", "zip", "gz", "tgz", "jar", "apk", "aar", "so", "class", "jks", "keystore")

private fun editable(path: String) = path.substringAfterLast('.', "").lowercase() !in NOT_EDITABLE

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
 * @param onAskText открыть чат с подставленным вопросом (например, о коммите).
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
    /** Вход в GitHub для «Отправить»; null — отправки нет. */
    gitHub: FilesGitHub? = null,
    /** Описание запроса на слияние по сообщениям коммитов (ИИ); null — недоступно. */
    pullText: (suspend (List<String>) -> Result<String>)? = null,
    onAskText: (String) -> Unit = {},
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
    val editor = remember { FileEditor() }
    fun pop(force: Boolean = false) {
        // Несохранённая правка — сначала спросить.
        if (!force && stack.lastOrNull() is Entry.Edit && editor.dirty) { sheet = Sheet.EditDiscard; return }
        if (stack.size <= 1) onClose() else { forward = false; stack = stack.dropLast(1) }
    }
    fun edit(path: String) { editor.reset(path); push(Entry.Edit(path)) }
    fun setTab(i: Int) {
        tab = i
        forward = true
        stack = listOf(if (i == 0) Entry.Dir("") else Entry.Changes)
    }
    fun file(path: String) = if (path.isEmpty()) root else File(root, path)
    fun refresh() { tick++ }
    val savedText = stringResource(R.string.fe_saved)
    val saveFailed = stringResource(R.string.fe_save_failed, "%s")
    val reloadedText = stringResource(R.string.fe_reloaded)
    /** Сохранить правку; [force] — поверх изменений извне. [then] — после успешной записи (например, выйти). */
    fun saveEditor(force: Boolean = false, then: () -> Unit = {}) {
        val path = editor.path ?: return
        val doc = editor.doc ?: return
        if (editor.saving) return
        editor.saving = true
        val text = editor.value.text
        scope.launch {
            val r = withContext(Dispatchers.IO) { saveEdit(file(path), text, doc.crlf, doc.stamp, force) }
            editor.saving = false
            r.onSuccess { res ->
                when (res) {
                    is SaveResult.Saved -> { editor.doc = doc.copy(text = text, stamp = res.stamp); onToast(savedText); refresh(); then() }
                    SaveResult.Conflict -> sheet = Sheet.EditConflict
                }
            }.onFailure { onToast(saveFailed.format(it.message.orEmpty())) }
        }
    }
    var pushing by remember { mutableStateOf(false) }
    val pushMsgs = PushMessages.load()
    // Ветка, база и запрос на слияние — только когда есть вход в GitHub для отправки.
    var loginTick by remember { mutableIntStateOf(0) }
    val branchState by produceState<BranchState?>(null, git, version, loginTick, tab) {
        value = if (gitHub != null && tab == 1) runCatching { git?.branchState() }.getOrNull() else null
    }
    // Что сделать после входа в GitHub: отправку или запрос на слияние, ради которых он понадобился.
    var afterLogin by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun needLogin(then: () -> Unit) { afterLogin = then; sheet = Sheet.GitHubLogin }
    fun openPull() {
        val g = git ?: return
        scope.launch { g.branchState()?.takeIf { it.branch != it.base }?.let { sheet = Sheet.Pull(it) } }
    }
    fun doPush(thenPull: Boolean = false) {
        val g = git ?: return
        if (pushing) return
        pushing = true
        scope.launch {
            g.push()
                .onSuccess { onToast(pushMsgs.done.format(it)); refresh(); if (thenPull) openPull() }
                .onFailure { e ->
                    val problem = (e as? PushException)?.problem ?: PushProblem.OTHER
                    if (problem == PushProblem.NEED_LOGIN && gitHub != null) needLogin { doPush(thenPull) }
                    else onToast(pushMsgs.of(problem, e.message.orEmpty()))
                }
            pushing = false
        }
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val pullExists = stringResource(R.string.fs_pr_exists)
    val pullAuth = stringResource(R.string.fs_pr_auth)

    val revertedText = stringResource(R.string.fs_reverted)
    val hunkReverted = stringResource(R.string.fs_hunk_done)
    val hunkStale = stringResource(R.string.fs_hunk_stale)
    val top = stack.last()
    val searching = top == Entry.Search
    BackHandler(enabled = sheet == null && stack.size > 1) { pop() }

    DshSheetScaffold(
        sheet = sheet?.let { s -> { SheetBody(s, root, changedPaths, ::file, onDone = { sheet = null }, onSheet = { sheet = it }, git, onAsk, onAttach, onShare, onImport, onToast, ::refresh, currentDir = (stack.lastOrNull { it is Entry.Dir } as? Entry.Dir)?.path ?: "", commitMessage = commitMessage, gitHub = gitHub,
            afterLogin = { loginTick++; val then = afterLogin ?: { doPush() }; afterLogin = null; then() },
            pullText = pullText,
            onMoved = { refresh(); doPush(thenPull = true) },
            onPullCreated = { p -> sheet = null; refresh(); onToast(res.getString(R.string.fs_pr_done, p.number)) },
            onPullLogin = { auth -> if (auth) onToast(pullAuth); val s = (sheet as? Sheet.Pull)?.state; needLogin { s?.let { sheet = Sheet.Pull(it) } } },
            onPullExists = { sheet = null; refresh(); onToast(pullExists) },
            onEditDrop = { sheet = null; pop(force = true) },
            onEditKeep = { sheet = null; saveEditor { pop(force = true) } },
            onEditReload = { sheet = null; editor.path?.let { p -> editor.reset(p); scope.launch { withContext(Dispatchers.IO) { editor.load(file(p)) }; onToast(reloadedText) } } },
            onEditOverwrite = { sheet = null; saveEditor(force = true) },
            onEdit = { p -> sheet = null; edit(p) },
        ) } },
        onDismiss = { sheet = null },
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
            // `.sbar`: «Назад», заголовок с подписью, поиск и «+».
            val (title, sub) = titles(top, root, rootLabel, gitInfo, editor.dirty)
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
                if (top is Entry.Edit) {
                    // Правка: в шапке только «Сохранить» (активна, когда есть несохранённое); файл не правится — без неё.
                    if (editor.problem == null) {
                        com.dsh.core.designsystem.kit.KBtn(
                            stringResource(R.string.fe_save), { saveEditor() }, Modifier.padding(end = 4.dp),
                            kind = com.dsh.core.designsystem.kit.BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 14.dp,
                            enabled = editor.dirty && !editor.saving, busy = editor.saving,
                        )
                    }
                } else {
                    val canFind = top is Entry.Dir || top == Entry.Changes
                    IconBtn(DshGlyphs.Search, stringResource(R.string.fs_find), visible = canFind) { query = ""; push(Entry.Search) }
                    if (top is Entry.Doc && editable(top.path)) IconBtn(DshGlyphs.Pencil, stringResource(R.string.fe_edit)) { edit(top.path) }
                    else IconBtn(DshGlyphs.Plus, stringResource(R.string.fs_add), visible = top is Entry.Dir) { sheet = Sheet.Add }
                }
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
                    is Entry.Edit -> EditView(file(e.path), editor)
                    is Entry.Diff -> DiffView(e.path, changedPaths[e.path], git, version, file = file(e.path),
                        onHunk = { ok -> onToast(if (ok) hunkReverted else hunkStale); refresh() },
                        onEmpty = { pop() },
                        onRevert = { ch ->
                        scope.launch {
                            val ok = runCatching { git?.revert(ch) ?: false }.getOrDefault(false)
                            if (ok) { onToast(revertedText); refresh(); pop() }
                        }
                    })
                    Entry.Changes -> ChangesView(
                        gitInfo, git, version, onReview = onReview, onCommit = { sheet = Sheet.Commit(it) }, onOpen = { push(Entry.Diff(it)) },
                        pushing = pushing, signedIn = gitHub?.signedIn() == true, canPush = gitHub != null, onPush = { doPush() },
                        branchState = branchState,
                        onNewBranch = { subject -> branchState?.let { sheet = Sheet.Branch(branchSlug(subject), it.base) } },
                        onCreatePull = { st -> if (gitHub?.signedIn() == true) sheet = Sheet.Pull(st) else needLogin { openPull() } },
                        onOpenPull = { url -> runCatching { uri.openUri(url) } },
                        onCommitOpen = { push(Entry.Commit(it)) },
                        onLog = { push(Entry.Log) },
                    )
                    Entry.Log -> LogView(git, version, onOpen = { push(Entry.Commit(it)) })
                    is Entry.Commit -> {
                        val prompt = stringResource(R.string.fs_commit_ask_prompt, e.info.short, e.info.subject)
                        CommitView(e.info, git, gitInfo?.branch.orEmpty(), onFile = { push(Entry.CommitDiff(e.info, it)) }, onAsk = { onAskText(prompt) })
                    }
                    is Entry.CommitDiff -> DiffView(
                        e.file.path, GitChange(e.file.path, e.file.kind, e.file.added, e.file.deleted), git, version,
                        onRevert = {}, history = { git?.commitDiff(e.info.hash, e.file).orEmpty() },
                    )
                    Entry.Search -> SearchView(
                        root, query, version, changedPaths,
                        onOpen = { f -> push(if (f.isDirectory) Entry.Dir(relPath(root, f)) else Entry.Doc(relPath(root, f))) },
                        onMore = { sheet = Sheet.Actions(it) },
                        onHit = { path, offset -> editor.reset(path); editor.findAt(query.trim(), offset); push(Entry.Edit(path)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun titles(top: Entry, root: File, rootName: String, git: GitInfo?, dirty: Boolean = false): Pair<String, String> {
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
        is Entry.Edit -> File(top.path).name to stringResource(if (dirty) R.string.fe_sub_dirty else R.string.fe_sub)
        Entry.Changes -> stringResource(R.string.fs_changes_title) to (git?.branch?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.fs_changes_sub, rootName, it) } ?: rootName)
        Entry.Search -> stringResource(R.string.fs_search_title) to stringResource(R.string.fs_search_sub)
        Entry.Log -> stringResource(R.string.fs_history) to (git?.branch?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.fs_history_branch, it) } ?: rootName)
        is Entry.Commit -> stringResource(R.string.fs_commit_title) to top.info.short
        is Entry.CommitDiff -> top.file.path.substringAfterLast('/') to stringResource(R.string.fs_commit_in, top.info.short)
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

internal val Mono = TextStyle(fontFamily = DshFonts.mono)

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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            // Долгое нажатие — те же действия, что у «⋯» (переименовать, удалить, поделиться…).
            Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .combinedClickable(role = Role.Button, onLongClick = onMore, onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
            // У папки тоже есть «⋯»: без неё подпапку нельзя было переименовать или удалить (DSH-11).
            // Нажатие на саму строку папки по-прежнему открывает её.
            when {
                onMore == null -> Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.padding(end = 8.dp).size(18.dp))
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

/** Подсветка — общая с чатом ([com.dsh.core.designsystem.highlightCode]). */
internal fun highlight(lines: List<String>, ext: String, c: com.dsh.core.designsystem.DshColors): AnnotatedString =
    com.dsh.core.designsystem.highlightCode(lines, ext, c)

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
private fun DiffView(
    path: String,
    change: GitChange?,
    git: FilesGit?,
    version: Int,
    onRevert: (GitChange) -> Unit,
    file: File? = null,
    /** Фрагмент отменён (true) или файл уже не такой, как в диффе (false). */
    onHunk: (Boolean) -> Unit = {},
    /** Изменений в файле больше нет (отменён последний фрагмент). */
    onEmpty: () -> Unit = {},
    /** Разница из истории (файл в коммите): только просмотр, без «Отменить». */
    history: (suspend () -> List<DiffRow>)? = null,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val rows by produceState<List<DiffRow>?>(null, path, version) { value = runCatching { if (history != null) history() else git?.diff(path) }.getOrNull().orEmpty() }
    var reverted by remember(path) { mutableStateOf(false) }
    LaunchedEffect(rows) { if (reverted && rows?.isEmpty() == true) onEmpty() }
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
                        if (change != null && change.added >= 0) {
                            append(" · ")
                            if (change.added > 0 || change.deleted == 0) withStyle(SpanStyle(color = c.ok)) { append("+${change.added}") }
                            if (change.added > 0 && change.deleted > 0) append(" ")
                            if (change.deleted > 0) withStyle(SpanStyle(color = c.danger)) { append("−${change.deleted}") }
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
            // По фрагментам (`.hk`): подпись строк и «Отменить» — только у изменённого файла (новый и удалённый — целиком).
            hunksOf(list).forEachIndexed { hi, hunk ->
            val header = (hunk.first() as DiffRow.Hunk).text
            Row(
                Modifier.padding(top = if (hi > 0) 14.dp else 0.dp, bottom = 6.dp).fillMaxWidth().heightIn(min = 44.dp).padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val range = hunkNewRange(header)
                Text(
                    when {
                        range == null -> header
                        range.second == 0 -> stringResource(R.string.fs_hunk_del, range.first)
                        range.second == 1 -> stringResource(R.string.fs_hunk_line, range.first)
                        else -> stringResource(R.string.fs_hunk_lines, range.first, range.first + range.second - 1)
                    },
                    style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t2, modifier = Modifier.weight(1f),
                )
                if (change?.kind == ChangeKind.MODIFIED && file != null) {
                    com.dsh.core.designsystem.kit.KBtn(
                        stringResource(R.string.fs_hunk_revert), {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val doc = openForEdit(file).getOrNull() ?: return@withContext false
                                    val next = revertHunk(doc.text, hunk) ?: return@withContext false
                                    saveEdit(file, next, doc.crlf, doc.stamp).getOrNull() is SaveResult.Saved
                                }
                                if (ok) reverted = true
                                onHunk(ok)
                            }
                        },
                        kind = com.dsh.core.designsystem.kit.BtnKind.Ghost, icon = DshGlyphs.Undo, height = 40.dp, radius = 12.dp, fontSize = 13.5.sp, horizontal = 12.dp,
                    )
                }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.termBg).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                // Фон строк «+»/«−» — на всю ширину блока (или самой длинной строки), как `.ln` в прототипе.
                Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).width(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(vertical = 8.dp)) {
                    hunk.drop(1).forEach { r ->
                        val (bg, ink, num, text) = when (r) {
                            is DiffRow.Hunk -> Quad(Color.Transparent, c.t3, "", r.text)
                            is DiffRow.Line -> when (r.sign) {
                                '+' -> Quad(c.okSoft, c.ok, "${r.number}", "+ ${r.text}")
                                '-' -> Quad(c.dangerSoft, c.danger, "${r.number}", "- ${r.text}")
                                else -> Quad(Color.Transparent, c.termText, "${r.number}", "  ${r.text}")
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(end = 14.dp)) {
                            Text(num, style = style.copy(textAlign = TextAlign.End), color = c.t3.copy(alpha = 0.8f), modifier = Modifier.width(38.dp).padding(end = 12.dp))
                            Text(text, style = style, color = ink, softWrap = false)
                        }
                    }
                }
            }
            }
        }
        if (history != null && list?.isEmpty() == true) {
            Empty(stringResource(R.string.fs_commit_nodiff))
        }
        if (change != null && history == null) {
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
    git: FilesGit?,
    version: Int,
    onReview: (String) -> Unit,
    onCommit: (GitInfo) -> Unit,
    onOpen: (String) -> Unit,
    pushing: Boolean = false,
    signedIn: Boolean = false,
    canPush: Boolean = false,
    onPush: () -> Unit = {},
    branchState: BranchState? = null,
    onNewBranch: (subject: String) -> Unit = {},
    onCreatePull: (BranchState) -> Unit = {},
    onOpenPull: (String) -> Unit = {},
    onCommitOpen: (CommitInfo) -> Unit = {},
    onLog: () -> Unit = {},
) {
    val c = Dsh.colors
    val gitReady = git != null
    // Последние коммиты (на один больше показываемых — чтобы знать, нужна ли «Вся история»).
    val history by produceState<List<CommitInfo>?>(null, git, version, info) { value = runCatching { git?.log(0, HISTORY_SHOW + 1) }.getOrNull() }
    val files = info?.changes.orEmpty()
    // Неотправленные коммиты — после каждого обновления статуса (коммит, правки агента).
    var unpushed by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(git, version, info) { unpushed = git?.unpushed(); target = if (canPush) git?.pushTarget() else null }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        when {
            info == null -> Empty(stringResource(if (gitReady) R.string.fs_no_changes else R.string.fs_no_git), icon = if (gitReady) DshGlyphs.Check else null)
            files.isEmpty() -> {
                Empty(stringResource(R.string.fs_no_changes), icon = DshGlyphs.Check)
                val ahead = unpushed?.takeIf { it.first > 0 }
                val onBase = branchState != null && branchState.branch == branchState.base
                if (ahead != null) UnpushedCard(ahead.first, ahead.second, target, pushing, signedIn, onPush, onNewBranch = if (onBase && target != null) ({ onNewBranch(ahead.second) }) else null)
                else if (branchState != null && !onBase && branchState.onRemote) PullCard(branchState, { onCreatePull(branchState) }, onOpenPull)
            }
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
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DshButton(stringResource(R.string.fs_review), { onReview(review) }, Modifier.fillMaxWidth().height(48.dp), icon = DshGlyphs.Spark)
                        if (gitReady) DshButton(
                            stringResource(R.string.fs_cm_go), { onCommit(info) },
                            Modifier.fillMaxWidth().height(48.dp), kind = DshButtonKind.Ghost, icon = DshGlyphs.xs("git"),
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
        // `.fs-sec` «История»: последние коммиты ветки и «Вся история».
        val commits = history.orEmpty()
        if (info != null && commits.isNotEmpty()) {
            Section(stringResource(R.string.fs_history), info.branch.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.fs_history_branch, it) })
            Group {
                commits.take(HISTORY_SHOW).forEachIndexed { i, ci -> CommitRow(ci, i == 0) { onCommitOpen(ci) } }
                if (commits.size > HISTORY_SHOW) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onLog).padding(start = 16.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.fs_history_all), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp), color = c.accent, modifier = Modifier.weight(1f))
                        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** `.hc`: коммит в истории — тема (до двух строк), «хэш · автор · когда», «не отправлен» или стрелка. */
@Composable
private fun CommitRow(ci: CommitInfo, first: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fresh = ci.pushed == false
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (fresh) c.accentSoft else c.s2), contentAlignment = Alignment.Center) {
                Icon(DshGlyphs.xs("git"), null, tint = if (fresh) c.accent else c.t2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                // Метка «не отправлен» — в строке темы: строке «хэш · автор · когда» остаётся вся ширина.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ci.subject, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (ci.pushed == false) Box(Modifier.padding(top = 1.dp)) { UnpushedTag() }
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = DshFonts.mono, fontSize = 12.sp)) { append(ci.short) }
                        append(" · ${ci.author} · ${relativeTime(ci.time)}")
                    },
                    style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
        }
    }
}

/** `.hc-tag`: «не отправлен». */
@Composable
private fun UnpushedTag() {
    val c = Dsh.colors
    Text(
        stringResource(R.string.fs_unpushed_tag), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp), color = c.warn,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.warnSoft).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Вся история ветки страницами по [HISTORY_PAGE]; «Показать ещё», пока страница полная. */
@Composable
private fun LogView(git: FilesGit?, version: Int, onOpen: (CommitInfo) -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val items = remember(version) { androidx.compose.runtime.mutableStateListOf<CommitInfo>() }
    var more by remember(version) { mutableStateOf(false) }
    var loaded by remember(version) { mutableStateOf(false) }
    var loading by remember(version) { mutableStateOf(false) }
    suspend fun page() {
        loading = true
        val next = runCatching { git?.log(items.size, HISTORY_PAGE) }.getOrNull().orEmpty()
        items += next
        more = next.size == HISTORY_PAGE
        loaded = true
        loading = false
    }
    LaunchedEffect(git, version) { page() }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        when {
            !loaded -> Unit
            items.isEmpty() -> Empty(stringResource(R.string.fs_history_none))
            else -> {
                Group { items.forEachIndexed { i, ci -> CommitRow(ci, i == 0) { onOpen(ci) } } }
                if (more) DshButton(
                    stringResource(R.string.fs_history_more), { if (!loading) scope.launch { page() } },
                    Modifier.padding(top = 12.dp).fillMaxWidth().height(48.dp), kind = DshButtonKind.Ghost,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Коммит (`.cv-head`): хэш и где он, тема, тело, автор и время, всего строк; «Спросить DSH»; файлы → разница. */
@Composable
private fun CommitView(ci: CommitInfo, git: FilesGit?, branch: String, onFile: (CommitFile) -> Unit, onAsk: () -> Unit) {
    val c = Dsh.colors
    // null — читаем; пусто при ошибке git — «не удалось».
    val files by produceState<List<CommitFile>?>(null, ci.hash) { value = runCatching { git?.commitFiles(ci.hash) }.getOrNull() ?: emptyList() }
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(c.s2).padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(DshGlyphs.xs("git"), null, tint = c.t1, modifier = Modifier.size(15.dp))
                    Text(ci.short, style = Mono.copy(fontSize = 12.sp), color = c.t1, maxLines = 1)
                }
                when {
                    ci.pushed == false -> UnpushedTag()
                    ci.pushed == true && branch.isNotEmpty() -> Text(stringResource(R.string.fs_commit_on_remote, branch), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                ci.subject, style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp), color = c.t1,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (ci.body.isNotBlank()) Text(
                unwrapBody(ci.body), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 21.sp), color = c.t2,
                modifier = Modifier.padding(top = 8.dp),
            )
            val list = files.orEmpty()
            val plus = list.sumOf { it.added.coerceAtLeast(0) }
            val minus = list.sumOf { it.deleted.coerceAtLeast(0) }
            Text(
                buildAnnotatedString {
                    append("${ci.author} · ${relativeTime(ci.time)}")
                    if (list.isNotEmpty()) {
                        append(" · " + pluralStringResource(R.plurals.fs_files_n, list.size, list.size) + " · ")
                        withStyle(SpanStyle(fontFamily = DshFonts.mono)) {
                            if (plus > 0 || minus == 0) withStyle(SpanStyle(color = c.ok)) { append("+$plus") }
                            // Неразрывный пробел: «+20 −17» не разъезжается по строкам.
                            if (plus > 0 && minus > 0) append("\u00A0")
                            if (minus > 0) withStyle(SpanStyle(color = c.danger)) { append("−$minus") }
                        }
                    }
                },
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.sp), color = c.t3,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        DshButton(stringResource(R.string.fs_commit_ask), onAsk, Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp), kind = DshButtonKind.Ghost, icon = DshGlyphs.Spark)
        val list = files
        when {
            list == null -> Unit
            list.isEmpty() -> Empty(stringResource(R.string.fs_commit_failed))
            else -> {
                Section(stringResource(R.string.fs_files), "${list.size}")
                Group {
                    list.forEachIndexed { i, f ->
                        FileRow(
                            name = f.path.substringAfterLast('/'), meta = f.oldPath?.let { "$it → ${f.path}" } ?: f.path, dir = false, first = i == 0,
                            change = null, onClick = { onFile(f) }, onMore = null,
                            trailing = {
                                when {
                                    f.kind == ChangeKind.ADDED -> Badge(stringResource(R.string.fs_badge_new), c.ok, c.okSoft)
                                    f.kind == ChangeKind.DELETED -> Badge(stringResource(R.string.fs_badge_del), c.danger, c.dangerSoft)
                                    f.added < 0 -> Badge(stringResource(R.string.fs_badge_bin), c.t2, c.s2)
                                    else -> Text(
                                        buildAnnotatedString {
                                            if (f.added > 0 || f.deleted == 0) withStyle(SpanStyle(color = c.ok)) { append("+${f.added}") }
                                            if (f.added > 0 && f.deleted > 0) append(" ")
                                            if (f.deleted > 0) withStyle(SpanStyle(color = c.danger)) { append("−${f.deleted}") }
                                        },
                                        style = Mono.copy(fontSize = 13.sp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** `.chg`: метка вида файла. */
@Composable
private fun Badge(text: String, ink: Color, bg: Color) {
    Text(
        text, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp), color = ink,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Поиск во всём проекте: «Файлы» — по имени и пути, «В тексте» — строки с запросом (от двух знаков, с паузой
 * [GREP_DELAY_MS] после ввода). Строка открывает правку файла с открытым «Найти» на этом совпадении.
 */
@Composable
private fun SearchView(
    root: File,
    query: String,
    version: Int,
    changed: Map<String, GitChange>,
    onOpen: (File) -> Unit,
    onMore: (String) -> Unit,
    onHit: (path: String, offset: Int) -> Unit,
) {
    val c = Dsh.colors
    val q = query.trim()
    val all by produceState(emptyList<File>(), root, version) { value = withContext(Dispatchers.IO) { walkFiles(root) } }
    val hits = remember(all, q) { if (q.isEmpty()) emptyList() else all.filter { relPath(root, it).contains(q, ignoreCase = true) }.take(200) }
    // null — ещё ищем (или запрос короче двух знаков).
    val text by produceState<TextSearch?>(null, all, q) {
        value = null
        if (q.length < 2 || all.isEmpty()) return@produceState
        kotlinx.coroutines.delay(GREP_DELAY_MS)
        value = withContext(Dispatchers.IO) { searchText(root, all, q, active = { isActive }) }
    }
    val searching = q.length >= 2 && text == null
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(ViewPadding)) {
        val found = text?.files.orEmpty()
        when {
            q.isEmpty() -> Empty(stringResource(R.string.fs_search_start))
            hits.isEmpty() && found.isEmpty() && !searching -> Empty(stringResource(R.string.fs_nothing, q), stringResource(R.string.fs_nothing_hint))
            else -> {
                if (hits.isNotEmpty()) {
                    Section(stringResource(R.string.fs_found), "${hits.size}")
                    Group {
                        hits.forEachIndexed { i, f ->
                            val rel = relPath(root, f)
                            FileRow(f.name, rel, false, i == 0, changed[rel], onClick = { onOpen(f) }, onMore = { onMore(rel) })
                        }
                    }
                }
                val t = text
                if (searching) {
                    Text(
                        stringResource(R.string.fs_text_searching), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t3,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp), textAlign = TextAlign.Center,
                    )
                } else if (t != null && t.files.isNotEmpty()) {
                    Section(
                        stringResource(R.string.fs_in_text),
                        stringResource(R.string.fs_in_text_count, pluralStringResource(R.plurals.fs_lines_n, t.lines, t.lines), pluralStringResource(R.plurals.fs_files_n, t.files.size, t.files.size)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        t.files.forEach { fh -> TextHitsGroup(fh, onHit) }
                    }
                    if (t.capped) Text(
                        stringResource(R.string.fs_text_capped, GREP_MAX_LINES), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.sp), color = c.t3,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Пауза после ввода перед поиском по тексту: не читать проект на каждую букву. */
private const val GREP_DELAY_MS = 250L

/** `.fg`: файл (иконка, имя, путь, сколько строк) и строки с совпадением; найденное подсвечено, как в правке. */
@Composable
private fun TextHitsGroup(fh: FileHits, onHit: (String, Int) -> Unit) {
    val c = Dsh.colors
    val name = fh.path.substringAfterLast('/')
    Group {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onHit(fh.path, fh.hits.first().offset) }
                .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileTile(name, false)
            Column(Modifier.weight(1f)) {
                Text(name, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.5.sp, lineHeight = 19.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fh.path, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, lineHeight = 15.5.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${fh.total}", style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp), color = c.t3)
        }
        val code = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.5.sp, lineHeight = 18.sp)
        fh.hits.take(GREP_SHOW).forEach { h ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val label = stringResource(R.string.fs_text_line, h.line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { onHit(fh.path, h.offset) }
                    .semantics { contentDescription = label }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${h.line}", style = code.copy(textAlign = TextAlign.End), color = c.t3, maxLines = 1, modifier = Modifier.width(52.dp).padding(end = 12.dp))
                val shown = remember(h, c) {
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(h.snippet)
                        addStyle(androidx.compose.ui.text.SpanStyle(background = c.accentSoft, color = c.t1), h.start, h.end)
                    }
                }
                Text(shown, style = code, color = c.t2, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (fh.total > GREP_SHOW && fh.hits.size > GREP_SHOW) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Text(
                stringResource(R.string.fs_text_more, fh.total - GREP_SHOW), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.accent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { onHit(fh.path, fh.hits[GREP_SHOW].offset) }
                    .padding(start = 52.dp, end = 14.dp).wrapContentHeight(Alignment.CenterVertically),
            )
        }
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
    commitMessage: (suspend (GitInfo) -> Result<String>)?,
    gitHub: FilesGitHub?,
    afterLogin: () -> Unit,
    pullText: (suspend (List<String>) -> Result<String>)?,
    onMoved: () -> Unit,
    onPullCreated: (PullInfo) -> Unit,
    onPullLogin: (auth: Boolean) -> Unit,
    onPullExists: () -> Unit,
    onEditDrop: () -> Unit,
    onEditKeep: () -> Unit,
    onEditReload: () -> Unit,
    onEditOverwrite: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val createFailed = stringResource(R.string.fs_create_failed, "%s")
    val renameFailed = stringResource(R.string.fs_rename_failed, "%s")
    when (sheet) {
        Sheet.EditDiscard -> EditDiscardSheet(onEditDrop, onEditKeep)
        Sheet.EditConflict -> EditConflictSheet(onEditReload, onEditOverwrite)
        is Sheet.Branch -> if (git != null) BranchSheetBody(sheet.suggested, sheet.base, git, onCancel = onDone, onMoved = { onDone(); onMoved() })
        is Sheet.Pull -> if (git != null) PullSheetBody(
            sheet.state, git, pullText, onCancel = onDone, onCreated = onPullCreated, onNeedLogin = onPullLogin, onExists = onPullExists, onToast = onToast,
        )
        Sheet.GitHubLogin -> if (gitHub != null) {
            val signed = stringResource(R.string.fs_gh_signed, "%s")
            DeviceSignInSheetBody(
                begin = gitHub::begin, await = gitHub::await, errorOf = gitHub::errorOf,
                onSignedIn = { login -> onToast(signed.format(login)); onDone(); afterLogin() },
                onToast = onToast,
            )
        }
        is Sheet.Commit -> {
            val done = stringResource(R.string.fs_cm_done, sheet.info.branch.ifEmpty { "git" }, "%s")
            if (git != null) CommitSheetBody(
                sheet.info, git, commitMessage, onCancel = onDone,
                onCommitted = { _, subject -> onToast(done.format(subject)); refresh(); onDone() },
                onToast = onToast,
            )
        }
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
                if (!f.isDirectory && editable(sheet.path)) DshSheetItem(DshGlyphs.Code, stringResource(R.string.fe_edit), { onEdit(sheet.path) })
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
            when {
                !ok -> onToast(createFailed.format(name))
                sheet.folder -> { refresh(); onDone() }
                // Новый файл — сразу в правку: его создают, чтобы заполнить.
                else -> { refresh(); onEdit(if (currentDir.isEmpty()) name else "$currentDir/$name") }
            }
        }
        is Sheet.Rename -> {
            val f = file(sheet.path)
            NameForm(stringResource(R.string.fs_rename_title), f.name, stringResource(R.string.fs_rename), f.parentFile ?: root, except = f.name) { name ->
                if (runCatching { f.renameTo(File(f.parentFile, name)) }.getOrDefault(false)) { refresh(); onDone() }
                else onToast(renameFailed.format(f.name))
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
            val deleteFailed = stringResource(R.string.fs_delete_failed, f.name)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshButton(stringResource(R.string.fs_cancel), onDone, Modifier.weight(1f), kind = DshButtonKind.Ghost)
                DshButton(stringResource(R.string.fs_delete), {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { deleteTree(f) }
                        // Часть дерева могла удалиться и при отказе — список перечитывается в любом случае.
                        refresh()
                        onToast(if (ok) deleted else deleteFailed)
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
