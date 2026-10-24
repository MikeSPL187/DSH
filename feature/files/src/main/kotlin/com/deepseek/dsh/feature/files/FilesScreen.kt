package com.deepseek.dsh.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshButton
import com.deepseek.dsh.core.designsystem.DshButtonKind
import com.deepseek.dsh.core.designsystem.DshDivider
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGroup
import com.deepseek.dsh.core.designsystem.DshIconButton
import com.deepseek.dsh.core.designsystem.DshSectionLabel
import com.deepseek.dsh.core.designsystem.DshSpace
import com.deepseek.dsh.core.designsystem.DshTopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файлы рабочей папки: шапка «Назад» с поиском и импортом, путь крошками,
 * папки и файлы двумя группами. Файл можно прикрепить к сообщению агенту.
 *
 * @param currentPath открытая папка.
 * @param files её содержимое.
 * @param onFileClick нажатие на строку (папку хост открывает).
 * @param rootPath корень рабочей папки: выше него не поднимаемся, от него строятся крошки.
 * @param onNavigateUp на уровень вверх; null — уже в корне.
 * @param onNavigateTo переход по крошке пути.
 * @param onClose закрыть экран, когда подниматься уже некуда; null — без шапки (встроенная панель).
 * @param onImportClick импорт файла с телефона в эту папку.
 * @param onSendFile прикрепить файл к следующему сообщению.
 */
@Composable
fun FilesScreen(
    currentPath: String,
    files: List<FileItem>,
    onFileClick: (FileItem) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    onImportClick: (() -> Unit)? = null,
    onSendFile: ((FileItem) -> Unit)? = null,
    rootPath: String? = null,
    onNavigateTo: ((String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val c = Dsh.colors
    val atRoot = rootPath == null || File(currentPath) == File(rootPath)
    val canGoUp = onNavigateUp != null && !atRoot
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // В другой папке поиск начинается заново.
    LaunchedEffect(currentPath) { query = "" }

    BackHandler(enabled = searching) { searching = false; query = "" }
    BackHandler(enabled = !searching && canGoUp) { onNavigateUp?.invoke() }

    val shown = remember(files, query) {
        val q = query.trim()
        files
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<FileItem>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val folders = shown.filter { it.isDirectory }
    val plain = shown.filterNot { it.isDirectory }

    Column(modifier.fillMaxSize().background(c.bg)) {
        if (onClose != null) {
            DshTopBar(
                title = if (atRoot) stringResource(R.string.files_page_title) else File(currentPath).name,
                subtitle = if (atRoot) File(currentPath).name else null,
                onBack = { if (canGoUp) onNavigateUp?.invoke() else onClose() },
                backDescription = stringResource(R.string.files_back),
            ) {
                DshIconButton(Icons.Rounded.Search, stringResource(R.string.action_search), onClick = { searching = !searching; if (!searching) query = "" })
                if (onImportClick != null) {
                    DshIconButton(Icons.Rounded.Add, stringResource(R.string.files_import_action), onClick = onImportClick)
                }
            }
        }
        AnimatedVisibility(
            visible = searching,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SearchField(query, { query = it }, onClose = { searching = false; query = "" })
        }
        if (rootPath != null && !atRoot && !searching) {
            Crumbs(rootPath, currentPath, onNavigateTo)
        }

        when {
            files.isEmpty() -> EmptyFolder(onImportClick)
            shown.isEmpty() -> NothingFound(query.trim())
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            ) {
                if (folders.isNotEmpty()) {
                    item(key = "h-dirs") { DshSectionLabel(stringResource(R.string.files_section_folders), trailing = folders.size.toString()) }
                    item(key = "dirs") {
                        DshGroup {
                            folders.forEachIndexed { i, f ->
                                if (i > 0) DshDivider(inset = 58.dp)
                                FileRow(f, onClick = { onFileClick(f) }, onAttach = null)
                            }
                        }
                    }
                }
                if (plain.isNotEmpty()) {
                    item(key = "h-files") { DshSectionLabel(stringResource(R.string.files_section_files), trailing = plain.size.toString()) }
                    item(key = "files") {
                        DshGroup {
                            plain.forEachIndexed { i, f ->
                                if (i > 0) DshDivider(inset = 58.dp)
                                FileRow(f, onClick = { onFileClick(f) }, onAttach = onSendFile?.let { send -> { send(f) } })
                            }
                        }
                    }
                }
                item(key = "nav-pad") { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

/** Поле поиска по текущей папке; открывается под шапкой и сразу получает фокус. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = Dsh.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(DshSpace.controlRadius))
            .background(c.s2)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = c.t3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.files_search_hint), style = Dsh.type.body, color = c.t3, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = Dsh.type.body.copy(color = c.t1),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        DshIconButton(Icons.Rounded.Close, stringResource(R.string.files_search_close), tint = c.t3, onClick = onClose, modifier = Modifier.size(40.dp))
    }
}

/** Путь от корня рабочей папки: каждая часть — кнопка перехода. */
@Composable
private fun Crumbs(rootPath: String, currentPath: String, onNavigateTo: ((String) -> Unit)?) {
    val c = Dsh.colors
    val root = File(rootPath)
    val parts = remember(rootPath, currentPath) {
        val chain = generateSequence(File(currentPath)) { it.parentFile }.takeWhile { it != root }.toList().reversed()
        listOf(root) + chain
    }
    val scroll = rememberScrollState()
    LaunchedEffect(parts.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, dir ->
            val last = i == parts.lastIndex
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.t3, modifier = Modifier.size(16.dp))
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !last && onNavigateTo != null, role = Role.Button) { onNavigateTo?.invoke(dir.absolutePath) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (i == 0) stringResource(R.string.files_root) else dir.name,
                    style = Dsh.type.callout.copy(fontWeight = if (last) FontWeight.Medium else FontWeight.Normal),
                    color = if (last) c.t1 else c.t2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Строка файла в группе: плитка типа, имя, размер и дата, действие справа. */
@Composable
private fun FileRow(file: FileItem, onClick: () -> Unit, onAttach: (() -> Unit)?) {
    val c = Dsh.colors
    val context = LocalContext.current
    val meta = if (file.isDirectory) {
        stringResource(R.string.files_folder_items)
    } else {
        buildString {
            append(android.text.format.Formatter.formatShortFileSize(context, file.sizeBytes))
            if (file.lastModified > 0) {
                append(" · ")
                append(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(file.lastModified)))
            }
        }
    }
    Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeTile(file)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = Dsh.type.bodyMedium, color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = Dsh.type.footnote, color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                file.isDirectory -> Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = c.t3,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
                onAttach != null -> DshIconButton(
                    Icons.Rounded.AttachFile,
                    stringResource(R.string.files_attach),
                    tint = c.t2,
                    onClick = onAttach,
                    modifier = Modifier.size(40.dp),
                )
                else -> Spacer(Modifier.width(8.dp))
            }
    }
}

/** Плитка типа файла: расширение моноширинным шрифтом в цвете своего семейства. */
@Composable
private fun FileTypeTile(file: FileItem) {
    val c = Dsh.colors
    val ext = file.name.substringAfterLast('.', "").lowercase().take(4)
    val hue: Color? = when {
        file.isDirectory -> null
        ext in setOf("kt", "kts", "java") -> Color(0xFF7F52FF)
        ext in setOf("md", "txt", "rst", "doc", "docx") -> Color(0xFF2F7DE1)
        ext in setOf("sh", "py", "js", "ts", "rb", "go", "rs", "c", "cpp", "h") -> c.ok
        ext in setOf("toml", "xml", "json", "yml", "yaml", "gradle", "ini", "conf") -> c.warn
        ext == "pdf" -> c.danger
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "heic") -> Color(0xFF0E9AA7)
        else -> c.t3
    }
    val bg = if (hue == null) c.accentSoft else lerp(hue, c.s1, 0.85f)
    val ink = if (hue == null) c.accent else lerp(hue, c.t1, 0.3f)
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isDirectory) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
        } else {
            Text(
                ext.ifEmpty { "—" },
                style = Dsh.type.caption.copy(fontFamily = DshFonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp),
                color = ink,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyFolder(onImportClick: (() -> Unit)?) {
    val c = Dsh.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.files_empty_title), style = Dsh.type.title, color = c.t1, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.files_empty_desc),
            style = Dsh.type.body,
            color = c.t2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (onImportClick != null) {
            Spacer(Modifier.height(20.dp))
            DshButton(stringResource(R.string.files_import_action), onImportClick, kind = DshButtonKind.Ghost, icon = Icons.Rounded.Add)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun NothingFound(query: String) {
    val c = Dsh.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = c.t3, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.files_not_found, query), style = Dsh.type.body, color = c.t2, textAlign = TextAlign.Center)
    }
}
