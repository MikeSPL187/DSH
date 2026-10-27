package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.protocol.ChangedFileInfo
import com.dsh.core.protocol.ChangesDiffInfo
import com.dsh.core.protocol.ChangesSummaryInfo

/** Сколько файлов видно до «Все N». */
private const val CHANGED_COLLAPSED_ROWS = 4

/**
 * Итог запроса `.changes`: «Изменения» и +n −m, ниже файлы моноширинным с их счётом.
 * Нажатие на файл открывает его diff.
 */
@Composable
fun ChangedFilesCard(
    summary: ChangesSummaryInfo,
    onOpenDiff: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.files.isEmpty()) return
    val c = Dsh.colors
    var expanded by remember(summary.seq) { mutableStateOf(false) }
    val foldable = summary.files.size > CHANGED_COLLAPSED_ROWS
    val rows = if (foldable && !expanded) summary.files.take(CHANGED_COLLAPSED_ROWS) else summary.files
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.padding(top = 4.dp, bottom = 16.dp).fillMaxWidth().clip(shape).background(c.s1).border(1.dp, c.line, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ck_changes), style = ckBody(14.5.sp, 18.sp, FontWeight.Medium), color = c.t1, modifier = Modifier.weight(1f))
            Stat(summary.added, summary.deleted)
        }
        rows.forEachIndexed { i, file ->
            val index = summary.files.indexOf(file).takeIf { it >= 0 } ?: i
            Row(
                Modifier.fillMaxWidth().topLine().clickable(role = Role.Button) { onOpenDiff(index) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(DshGlyphs.File, null, tint = c.t3, modifier = Modifier.size(18.dp))
                Text(file.display, style = ckMono(13.sp, 17.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FileStat(file)
            }
        }
        if (foldable) {
            Box(
                Modifier.fillMaxWidth().topLine().clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (expanded) stringResource(R.string.changes_collapse) else stringResource(R.string.changes_all, summary.files.size),
                    style = ckBody(13.5.sp, 17.sp), color = c.accent,
                )
            }
        }
    }
}

@Composable
private fun Modifier.topLine(): Modifier {
    val line = Dsh.colors.line
    return drawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
}

/** `.stat`: «+n −m» моноширинным, плюс зелёным, минус красным. */
@Composable
private fun Stat(added: Int, deleted: Int) {
    val c = Dsh.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.ok)) { append("+$added") }
            append(" ")
            withStyle(SpanStyle(color = c.danger)) { append("−$deleted") }
        },
        style = ckMono(12.5.sp, 16.sp),
    )
}

@Composable
private fun FileStat(file: ChangedFileInfo) {
    val c = Dsh.colors
    when {
        file.binary -> Text(stringResource(R.string.changes_binary), style = ckBody(12.sp), color = c.t3)
        file.oversized -> Text(stringResource(R.string.changes_oversized), style = ckBody(12.sp), color = c.t3)
        else -> Stat(file.added, file.deleted)
    }
}

/**
 * Лист с изменениями одного файла: шапка `.diff .f` и строки — удалённые на danger-soft,
 * добавленные на ok-soft; длинные строки прокручиваются вбок.
 *
 * @param diff null — загружается или не удалось.
 */
@Composable
fun ColumnScope.ChangeDiffBody(title: String, diff: ChangesDiffInfo?, loading: Boolean) {
    val c = Dsh.colors
    DshSheetTitle(title.substringAfterLast('/'))
    val note = when {
        loading -> stringResource(R.string.ck_loading)
        diff == null -> stringResource(R.string.changes_diff_failed)
        diff.kind == "binary" -> stringResource(R.string.changes_binary)
        diff.kind == "oversized" -> stringResource(R.string.changes_oversized)
        else -> null
    }
    if (note != null || diff == null) {
        Text(note.orEmpty(), style = ckBody(14.sp, 19.sp), color = c.t3, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        return
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape)) {
        Row(Modifier.fillMaxWidth().background(c.s2).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, style = ckMono(12.sp, 20.4.sp), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).horizontalScroll(rememberScrollState())) {
            diff.hunks.forEach { h ->
                Text(
                    "@@ −${h.oldStart},${h.oldLines} +${h.newStart},${h.newLines} @@",
                    style = ckMono(12.sp, 20.4.sp), color = c.t3, softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
                h.lines.forEach { line ->
                    val sign = line.firstOrNull()
                    val (bg, fg) = when (sign) {
                        '+' -> c.okSoft to c.ok
                        '-' -> c.dangerSoft to c.danger
                        else -> androidx.compose.ui.graphics.Color.Transparent to c.t1
                    }
                    Text(
                        if (sign == '-') "−" + line.drop(1) else line,
                        style = ckMono(12.sp, 20.4.sp), color = fg, softWrap = false, maxLines = 1,
                        modifier = Modifier.widthIn(min = 600.dp).background(bg).padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

