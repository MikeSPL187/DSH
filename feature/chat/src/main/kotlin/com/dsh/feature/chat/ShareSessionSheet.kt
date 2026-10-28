package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.DshSwitch
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.model.ChatMessage

/**
 * Лист «Поделиться разговором» (`#sheet-share`): карточка с названием, числом реплик и шагов и началом текста,
 * «Шаги агента», напоминание, что секреты скрыты, «Скопировать» и «Поделиться». Текст — [SessionTranscript].
 *
 * @param header строка под заголовком: «DSH · дата · папка».
 */
@Composable
fun ColumnScope.ShareSessionSheetBody(
    title: String,
    header: String,
    /** null — переписка ещё загружается. */
    messages: List<ChatMessage>?,
    onCopy: (String) -> Unit,
    onShare: (text: String, fileName: String) -> Unit,
) {
    val c = Dsh.colors
    var steps by remember { mutableStateOf(true) }
    val labels = SessionTranscript.Labels(stringResource(R.string.sh_you), stringResource(R.string.sh_agent), stringResource(R.string.sh_failed))
    val list = messages.orEmpty()
    val text = remember(list, steps, title, header) { SessionTranscript.markdown(title, header, list, steps, labels) }
    val counts = remember(list) { SessionTranscript.counts(list) }
    val kb = (text.toByteArray().size + 1023) / 1024

    DshSheetTitle(stringResource(R.string.sh_title))
    // `.sh-pre`: название, «N сообщений · M шагов · K КБ», начало текста с растворением книзу.
    Column(Modifier.padding(bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1, maxLines = 2)
        val meta = buildList {
            add(androidx.compose.ui.res.pluralStringResource(R.plurals.sh_messages, counts.messages, counts.messages))
            if (steps && counts.steps > 0) add(androidx.compose.ui.res.pluralStringResource(R.plurals.sh_steps, counts.steps, counts.steps))
            add(stringResource(R.string.sh_kb, kb))
        }.joinToString(" · ")
        Text(meta, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
        if (messages == null || counts.messages == 0) Text(
            stringResource(if (messages == null) R.string.sh_loading else R.string.sh_empty),
            style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.2.sp), color = c.t2, modifier = Modifier.padding(top = 10.dp),
        ) else Text(
            text.lineSequence().take(8).joinToString("\n"), style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.sp, lineHeight = 18.sp), color = c.t2,
            maxLines = 6,
            // Растворение книзу — градиентом в цвет подложки: без смешивания слоёв, одинаково на всех версиях Android.
            modifier = Modifier.padding(top = 10.dp).heightIn(max = 108.dp)
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.s2))
                },
        )
    }
    // `.pr-draft`: вся строка переключает.
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch) { steps = !steps }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sh_steps_title), style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1)
            Text(stringResource(R.string.sh_steps_sub), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.5.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
        }
        DshSwitch(steps, { steps = it }, contentDescription = stringResource(R.string.sh_steps_title))
    }
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(DshGlyphs.Lock, null, tint = c.ok, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.sh_secrets), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 18.2.sp), color = c.t2)
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.sh_copy), { onCopy(text) }, Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = counts.messages > 0, horizontal = 10.dp)
        KBtn(
            stringResource(R.string.sh_go), { onShare(text, fileName(title)) }, Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp,
            enabled = counts.messages > 0,
        )
    }
}

/** Имя файла: буквы и цифры названия (остальное — «-»), `.md`. */
internal fun fileName(title: String): String {
    val base = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(48).trimEnd('-')
    return base.ifEmpty { "dsh-session" } + ".md"
}
