package com.dsh.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.GLabel
import com.dsh.core.designsystem.kit.KBtn
import kotlinx.coroutines.launch

/** Почта похожа на настоящую: что-то@что-то, без пробелов (git сам её не проверяет). */
internal fun plausibleEmail(text: String): Boolean = Regex("""^[^@\s]+@[^@\s]+$""").matches(text.trim())

/**
 * Лист «Коммит в <ветка>» (`#sheet-commit`): сообщение, «Написать по изменениям» (ИИ), сводка — все изменения
 * (`git add -A`), автор — один раз имя и почта, потом строка «От имени … · Изменить». «Закоммитить» доступна,
 * когда есть сообщение и автор.
 *
 * @param writeMessage сообщение по изменениям от ИИ; null — недоступно (нет ключа).
 * @param onCommitted коммит сделан: короткий хэш и тема.
 */
@Composable
internal fun ColumnScope.CommitSheetBody(
    info: GitInfo,
    git: FilesGit,
    writeMessage: (suspend (GitInfo) -> Result<String>)?,
    onCancel: () -> Unit,
    onCommitted: (hash: String, subject: String) -> Unit,
    onToast: (String) -> Unit,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editWho by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(git) {
        known = git.identity()
        known?.let { (n, e) -> name = n; email = e }
    }
    val whoFailed = stringResource(R.string.fs_cm_who_failed)
    val askWho = known == null || editWho
    val ready = message.isNotBlank() && name.isNotBlank() && plausibleEmail(email) && !busy

    DshSheetTitle(if (info.branch.isNotEmpty()) stringResource(R.string.fs_cm_title, info.branch) else stringResource(R.string.fs_cm_title_plain))
    CmField(
        message, { message = it; error = null }, stringResource(R.string.fs_cm_ph), minHeight = 96.dp, singleLine = false,
        description = stringResource(R.string.fs_cm_msg_label), capitalize = true,
    )
    if (writeMessage != null) {
        Row(
            Modifier.padding(top = 8.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft)
                .clickable(enabled = !writing, role = Role.Button) {
                    writing = true
                    scope.launch {
                        writeMessage(info).onSuccess { message = it.trim(); error = null }.onFailure { onToast(it.message.orEmpty()) }
                        writing = false
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = androidx.compose.ui.graphics.lerp(c.t1, c.accent, 0.82f)
            Icon(DshGlyphs.Spark, null, tint = ink, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (writing) R.string.fs_commit_writing else R.string.fs_cm_ai),
                style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = ink,
            )
        }
    }
    val files = info.changes
    val plus = files.sumOf { it.added.coerceAtLeast(0) }
    val minus = files.sumOf { it.deleted.coerceAtLeast(0) }
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(DshGlyphs.File, null, tint = c.t2, modifier = Modifier.size(18.dp))
        Text(
            buildAnnotatedString {
                append(pluralStringResource(R.plurals.fs_files_n, files.size, files.size))
                append(" · ")
                withStyle(SpanStyle(color = c.ok)) { append("+$plus") }
                if (minus > 0) { append(" "); withStyle(SpanStyle(color = c.danger)) { append("−$minus") } }
                append(" · ")
                append(stringResource(R.string.fs_cm_all))
            },
            style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, lineHeight = 18.sp), color = c.t2,
        )
    }
    GLabel(stringResource(R.string.fs_cm_author))
    if (!askWho) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.fs_cm_as))
                    append(" ")
                    withStyle(SpanStyle(color = c.t1, fontWeight = FontWeight.Medium)) { append(name) }
                    append(" · $email")
                },
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 19.sp), color = c.t2, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.fs_cm_change), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                color = androidx.compose.ui.graphics.lerp(c.t1, c.accent, 0.82f),
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { editWho = true }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CmField(name, { name = it }, stringResource(R.string.fs_cm_name), description = stringResource(R.string.fs_cm_name), capitalize = true)
            CmField(email, { email = it }, stringResource(R.string.fs_cm_email), description = stringResource(R.string.fs_cm_email), keyboard = KeyboardType.Email)
        }
    }
    error?.let { Text(it, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.fs_cancel), onCancel, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
        KBtn(
            stringResource(if (busy) R.string.fs_cm_committing else R.string.fs_cm_go), {
                busy = true
                scope.launch {
                    val who = name.trim() to email.trim()
                    if (who != known && !git.setIdentity(who.first, who.second)) { busy = false; error = whoFailed; return@launch }
                    val subject = message.trim().lineSequence().first()
                    git.commit(message.trim()).onSuccess { onCommitted(it, subject) }.onFailure { error = it.message }
                    busy = false
                }
            },
            Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = ready,
        )
    }
}

/** Поле листа коммита (`.cm-msg` / `.cm-in`): s1, рамка 1,5 dp line, в фокусе — акцент и мягкое свечение 4 dp. */
@Composable
private fun CmField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    description: String,
    minHeight: Dp = 48.dp,
    singleLine: Boolean = true,
    capitalize: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
) {
    val c = Dsh.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (singleLine) 14.dp else 16.dp)
    val style = if (mono) TextStyle(fontFamily = DshFonts.mono, fontSize = 14.sp, lineHeight = 21.sp, color = c.t1)
    else TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 22.5.sp, color = c.t1)
    Box(
        Modifier.fillMaxWidth()
            .drawBehind {
                if (focused) {
                    val r = (if (singleLine) 14.dp else 16.dp).toPx() + 2.dp.toPx()
                    drawRoundRect(c.accentSoft, topLeft = androidx.compose.ui.geometry.Offset(-2.dp.toPx(), -2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(4.dp.toPx()))
                }
            }
            .heightIn(min = minHeight).clip(shape).background(c.s1).border(1.5.dp, if (focused) c.accent else c.line, shape)
            .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 12.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.t3))
        BasicTextField(
            value, onValue, singleLine = singleLine, textStyle = style, cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = capitalize, keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = description }
                .then(if (singleLine) Modifier.height(minHeight) else Modifier),
            // Одна строка — по центру поля высотой [minHeight] (вся высота принимает касание).
            decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) { inner() } },
        )
    }
}

/**
 * Карточка `.ch-push`: столько-то коммитов не отправлено, тема последнего и «Отправить в origin/<ветка>».
 * [onNewBranch] (на ветке по умолчанию) — ещё `.ch-alt` «В новую ветку и запрос на слияние».
 */
@Composable
internal fun UnpushedCard(count: Int, subject: String, target: String?, pushing: Boolean, signedIn: Boolean, onPush: () -> Unit, onNewBranch: (() -> Unit)? = null) {
    val c = Dsh.colors
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(com.dsh.core.designsystem.kit.Si.c1), contentAlignment = Alignment.Center) {
                Icon(DshGlyphs.xs("git"), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.fs_unpushed, count, count), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp), color = c.t1)
                if (subject.isNotEmpty()) Text(subject, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.2.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (target != null) {
            KBtn(
                if (pushing) stringResource(R.string.fs_pushing) else stringResource(R.string.fs_push, target), onPush,
                Modifier.padding(top = 12.dp).fillMaxWidth(), kind = BtnKind.Primary, icon = if (pushing) null else DshGlyphs.Up,
                height = 48.dp, radius = 15.dp, enabled = !pushing, busy = pushing,
            )
            if (onNewBranch != null && !pushing) {
                val ink = androidx.compose.ui.graphics.lerp(c.t1, c.accent, 0.82f)
                Row(
                    Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onNewBranch),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(DshGlyphs.xs("git"), null, tint = ink, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.fs_new_branch_pr), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = ink, maxLines = 1)
                }
            }
            if (!signedIn) Text(
                stringResource(R.string.fs_push_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.sp), color = c.t3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * Карточка ветки на GitHub (`.ch-push` без неотправленного): нет запроса — «Ветка на GitHub», `ветка → база` и
 * «Создать запрос на слияние»; есть — «Запрос на слияние #N», название и состояние, «Открыть на GitHub».
 */
@Composable
internal fun PullCard(state: BranchState, onCreate: () -> Unit, onOpen: (String) -> Unit) {
    val c = Dsh.colors
    val pull = state.pull
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (pull != null) com.dsh.core.designsystem.kit.Si.c3 else com.dsh.core.designsystem.kit.Si.c1),
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.xs("git"), null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Column(Modifier.weight(1f)) {
                val head = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
                if (pull != null) {
                    Text(stringResource(R.string.fs_pr_n, pull.number), style = head, color = c.t1)
                    val st = stringResource(if (pull.draft) R.string.fs_pr_state_draft else R.string.fs_pr_state_open)
                    Text("${pull.title} · $st", style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.2.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
                } else {
                    Text(stringResource(R.string.fs_pr_card), style = head, color = c.t1)
                    Text("${state.branch} → ${state.base}", style = Mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (pull != null) KBtn(
            stringResource(R.string.fs_pr_open), { onOpen(pull.url) }, Modifier.padding(top = 12.dp).fillMaxWidth(),
            kind = BtnKind.Ghost, icon = DshGlyphs.Link, height = 48.dp, radius = 15.dp,
        ) else KBtn(
            stringResource(R.string.fs_pr_create), onCreate, Modifier.padding(top = 12.dp).fillMaxWidth(),
            kind = BtnKind.Primary, icon = DshGlyphs.Up, height = 48.dp, radius = 15.dp,
        )
    }
}

/**
 * Лист «Новая ветка» (`#sheet-branch`): имя (по теме коммита), проверка по правилам git, «Отправить» — перенос
 * неотправленных коммитов в ветку; дальше вызывающий отправляет её и открывает лист запроса.
 */
@Composable
internal fun ColumnScope.BranchSheetBody(suggested: String, base: String, git: FilesGit, onCancel: () -> Unit, onMoved: () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(suggested) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val n = name.trim()
    val problem = when (branchNameProblem(n, base)) {
        BranchNameProblem.CHARS -> stringResource(R.string.fs_br_chars)
        BranchNameProblem.FORM -> stringResource(R.string.fs_br_form)
        BranchNameProblem.IS_BASE -> stringResource(R.string.fs_br_is_base, base)
        null -> null
    }
    val exists = stringResource(R.string.fs_br_exists)
    val failed = stringResource(R.string.fs_br_failed, "%s")
    DshSheetTitle(stringResource(R.string.fs_br_title))
    Text(
        stringResource(R.string.fs_br_hint, base), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp).layoutUp(6),
    )
    CmField(name, { name = it.replace(" ", "-"); failure = null }, "dsh/…", description = stringResource(R.string.fs_br_label), mono = true)
    Text(
        problem ?: failure.orEmpty(), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.sp), color = c.danger,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).heightIn(min = 18.dp),
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.fs_cancel), onCancel, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
        KBtn(
            stringResource(R.string.fs_br_go), {
                busy = true
                scope.launch {
                    git.moveToBranch(n)
                        .onSuccess { onMoved() }
                        .onFailure { failure = if (it is BranchExistsException) exists else failed.format(it.message.orEmpty()) }
                    busy = false
                }
            },
            Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp,
            enabled = n.isNotEmpty() && problem == null && !busy, busy = busy,
        )
    }
}

/**
 * Лист «Запрос на слияние» (`#sheet-pr`): маршрут `ветка → база`, название (тема последнего коммита), описание,
 * «Написать по коммитам» (ИИ), «Черновик». Нужен вход — [onNeedLogin]; запрос уже есть — [onExists].
 */
@Composable
internal fun ColumnScope.PullSheetBody(
    state: BranchState,
    git: FilesGit,
    writeText: (suspend (List<String>) -> Result<String>)?,
    onCancel: () -> Unit,
    onCreated: (PullInfo) -> Unit,
    onNeedLogin: (auth: Boolean) -> Unit,
    onExists: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var commits by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(git, state.branch) {
        commits = git.branchCommits()
        if (title.isEmpty()) title = commits.firstOrNull()?.lineSequence()?.first()?.trim()
            ?: state.branch.removePrefix("dsh/").replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
    val texts = mapOf(
        PullProblem.NO_ACCESS to stringResource(R.string.fs_pr_no_access),
        PullProblem.NO_COMMITS to stringResource(R.string.fs_pr_no_commits, state.base),
        PullProblem.DRAFT_UNSUPPORTED to stringResource(R.string.fs_pr_draft_unsupported),
        PullProblem.NOT_GITHUB to stringResource(R.string.fs_pr_not_github),
    )
    val failed = stringResource(R.string.fs_pr_failed, "%s")

    DshSheetTitle(stringResource(R.string.fs_pr_title))
    // `.pr-route`: ветка (обрезается многоточием) → база.
    Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp).layoutUp(6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BranchChip(state.branch, Modifier.weight(1f, fill = false))
        Icon(DshGlyphs.get("arrow"), null, tint = c.t3, modifier = Modifier.size(18.dp))
        BranchChip(state.base)
    }
    CmField(title, { title = it; error = null }, stringResource(R.string.fs_pr_name_ph), description = stringResource(R.string.fs_pr_name_ph), capitalize = true)
    Box(Modifier.padding(top = 8.dp)) {
        CmField(body, { body = it }, stringResource(R.string.fs_pr_body_ph), minHeight = 88.dp, singleLine = false, description = stringResource(R.string.fs_pr_body_label), capitalize = true)
    }
    if (writeText != null) AiButton(stringResource(if (writing) R.string.fs_pr_writing else R.string.fs_pr_ai), enabled = !writing) {
        writing = true
        scope.launch {
            writeText(commits).onSuccess { body = it.trim() }.onFailure { onToast(it.message.orEmpty()) }
            writing = false
        }
    }
    // `.pr-draft`: вся строка переключает.
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch) { draft = !draft; error = null }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.fs_pr_draft), style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1)
            Text(stringResource(R.string.fs_pr_draft_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.5.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
        }
        com.dsh.core.designsystem.DshSwitch(draft, { draft = it; error = null }, contentDescription = stringResource(R.string.fs_pr_draft))
    }
    error?.let { Text(it, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.fs_cancel), onCancel, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
        KBtn(
            stringResource(if (busy) R.string.fs_pr_creating else R.string.fs_pr_go), {
                busy = true
                scope.launch {
                    git.createPull(title.trim(), body.trim(), draft)
                        .onSuccess(onCreated)
                        .onFailure { e ->
                            when (val p = (e as? PullException)?.problem ?: PullProblem.OTHER) {
                                PullProblem.NEED_LOGIN, PullProblem.AUTH -> onNeedLogin(p == PullProblem.AUTH)
                                PullProblem.EXISTS -> onExists()
                                else -> error = texts[p] ?: failed.format(e.message.orEmpty())
                            }
                        }
                    busy = false
                }
            },
            Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp,
            enabled = title.isNotBlank() && !busy, busy = busy,
        )
    }
}

/** Имя ветки `.pr-route .br`: моноширинное 12 sp на s2, одна строка с многоточием. */
@Composable
private fun BranchChip(name: String, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Box(modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(c.s2).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
        Text(name, style = Mono.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.t1, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** `.cm-ai`: «Написать …» на мягком акценте. */
@Composable
private fun AiButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.padding(top = 8.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = androidx.compose.ui.graphics.lerp(c.t1, c.accent, 0.82f)
        Icon(DshGlyphs.Spark, null, tint = ink, modifier = Modifier.size(18.dp))
        Text(label, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = ink)
    }
}

/** Поднять на [dp] без зазора снизу (как отрицательный верхний отступ `margin: -6px …`). */
private fun Modifier.layoutUp(dp: Int): Modifier = this.then(
    Modifier.layout { m, cons ->
        val p = m.measure(cons)
        val up = dp.dp.roundToPx()
        layout(p.width, (p.height - up).coerceAtLeast(0)) { p.place(0, -up) }
    },
)

/** Тексты итогов отправки: успех и объяснение каждой причины отказа. */
internal class PushMessages(val done: String, private val texts: Map<PushProblem, String>, private val other: String) {
    fun of(problem: PushProblem, detail: String): String = texts[problem] ?: other.format(detail)

    companion object {
        @Composable
        fun load() = PushMessages(
            done = stringResource(R.string.fs_pushed, "%s"),
            texts = mapOf(
                PushProblem.NOT_GITHUB to stringResource(R.string.fs_push_not_github),
                PushProblem.UNSAFE_CONFIG to stringResource(R.string.fs_push_unsafe),
                PushProblem.DETACHED to stringResource(R.string.fs_push_detached),
                PushProblem.REJECTED to stringResource(R.string.fs_push_rejected),
                PushProblem.AUTH to stringResource(R.string.fs_push_auth),
                PushProblem.NEED_LOGIN to stringResource(R.string.fs_push_hint),
            ),
            other = stringResource(R.string.fs_push_failed, "%s"),
        )
    }
}
