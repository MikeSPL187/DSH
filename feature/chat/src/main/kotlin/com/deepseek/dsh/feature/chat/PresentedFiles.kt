package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.protocol.PresentedFileInfo

/** Сколько файлов видно до «Все N». */
private const val COLLAPSED_PRESENTED_COUNT = 4

/**
 * Результаты, которые агент отдал (deliverables): карточка как «Изменения» —
 * «Результаты», ниже файлы с описанием; нажатие открывает файл в «Файлах».
 */
@Composable
fun PresentedFilesRow(
    files: List<PresentedFileInfo>,
    onOpen: (PresentedFileInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val c = Dsh.colors
    var expanded by remember(files.size) { mutableStateOf(false) }
    val collapsible = files.size > COLLAPSED_PRESENTED_COUNT
    val visible = if (collapsible && !expanded) files.take(COLLAPSED_PRESENTED_COUNT) else files
    val shape = RoundedCornerShape(18.dp)
    val line = c.line
    Column(modifier.padding(top = 4.dp, bottom = 16.dp).fillMaxWidth().clip(shape).background(c.s1).border(1.dp, c.line, shape)) {
        Text(stringResource(R.string.ck_results), style = ckBody(14.5.sp, 18.sp, FontWeight.Medium), color = c.t1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        visible.forEach { file ->
            val name = file.path.substringAfterLast('/')
            val description = file.description
                ?.replace(Regex("\\s*(?:\\([^()]*\\)|（[^（）]*）)\\s*$"), "")?.trim()?.takeIf { it.isNotEmpty() }
                ?: name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()
                ?: stringResource(R.string.presented_file)
            Row(
                Modifier.fillMaxWidth().drawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
                    .clickable(role = Role.Button) { onOpen(file) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.File, null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = ckMono(13.sp, 17.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(description, style = ckBody(12.5.sp, 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
            }
        }
        if (collapsible) Box(
            Modifier.fillMaxWidth().drawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
                .clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (expanded) stringResource(R.string.presented_collapse) else stringResource(R.string.presented_all, files.size),
                style = ckBody(13.5.sp, 17.sp), color = c.accent,
            )
        }
    }
}
