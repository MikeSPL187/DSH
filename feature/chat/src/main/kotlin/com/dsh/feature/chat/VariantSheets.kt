package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetItem
import com.dsh.core.designsystem.DshSheetList
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.model.ChatMessage

/** «2 файла: libs.versions.toml, backup.sh». */
@Composable
private fun filesLine(files: List<String>): String =
    if (files.isEmpty()) stringResource(R.string.vr_no_files)
    else pluralStringResource(R.plurals.vr_files, files.size, files.size) + ": " + files.joinToString(", ") { it.substringAfterLast('/') }

/**
 * Лист «Другой вариант» (`#sheet-variant`): пояснение, карточка «Сохранится как вариант N» с изменёнными файлами
 * и «Запустить заново». Точки нет — честно: вариант начнётся поверх текущих файлов.
 */
@Composable
fun ColumnScope.VariantSheetBody(message: ChatMessage, services: ChatServices, onCancel: () -> Unit, onStart: (withPoint: Boolean) -> Unit) {
    val c = Dsh.colors
    var plan by remember(message.messageId) { mutableStateOf<VariantPlan?>(null) }
    var loaded by remember(message.messageId) { mutableStateOf(false) }
    LaunchedEffect(message.messageId) { plan = services.variantPlan(message); loaded = true }
    DshSheetTitle(stringResource(R.string.vr_title))
    Text(
        stringResource(R.string.vr_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2,
        modifier = Modifier.offset(y = (-6).dp).padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    )
    Column(Modifier.padding(bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 12.dp)) {
        val p = plan
        when {
            !loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.rw_loading), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp), color = c.t2)
            }
            p?.files == null -> Text(stringResource(R.string.vr_no_point), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2)
            else -> {
                Text(stringResource(R.string.vr_saved_as, p.number), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1)
                Text(filesLine(p.files), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.vr_cancel), onCancel, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp)
        KBtn(
            stringResource(if (plan?.files == null && loaded) R.string.vr_go_plain else R.string.vr_go), { onStart(plan?.files != null) }, Modifier.weight(1.35f),
            kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp, enabled = loaded,
        )
    }
}

/** `.vr-bar` под ответом: «Вариант 2 из 3» и «Сравнить». */
@Composable
fun VariantsBar(info: VariantsInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Row(
        modifier.offset(y = (-6).dp).padding(bottom = 8.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(c.s2)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DshGlyphs.Flow, null, tint = c.t3, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.vr_bar, info.current + 1, info.count), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = c.t1, modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.vr_compare), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = lerp(c.t1, c.accent, 0.82f))
        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
    }
}

/** Лист «Варианты» (`#sheet-variants`): что изменил каждый вариант, открытый — «сейчас»; касание оставляет вариант. */
@Composable
fun ColumnScope.VariantsSheetBody(services: ChatServices, onKeep: (Int) -> Unit) {
    val c = Dsh.colors
    var rows by remember { mutableStateOf<List<VariantRow>?>(null) }
    LaunchedEffect(Unit) { rows = services.variantRows() }
    DshSheetTitle(stringResource(R.string.vr_list_title))
    Text(
        stringResource(R.string.vr_list_hint), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2,
        modifier = Modifier.offset(y = (-6).dp).padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    )
    val list = rows
    if (list == null) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
            Text(stringResource(R.string.rw_loading), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp), color = c.t2)
        }
        return
    }
    DshSheetList {
        list.forEachIndexed { i, r ->
            DshSheetItem(
                DshGlyphs.Flow, stringResource(R.string.vr_n, r.number), { onKeep(i) }, first = i == 0, sub = filesLine(r.files),
                trailing = if (r.current) ({ Text(stringResource(R.string.vr_now), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.5.sp), color = c.ok) }) else null,
            )
        }
    }
}
