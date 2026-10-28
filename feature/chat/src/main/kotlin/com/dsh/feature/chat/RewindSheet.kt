package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetItem
import com.dsh.core.designsystem.DshSheetList
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.model.ChatMessage
import kotlinx.coroutines.launch

/**
 * Лист «Вернуться к сообщению» (`#sheet-rewind`): цитата сообщения, что изменится в файлах (до 4 путей и «и ещё N»)
 * и варианты — «Вернуть файлы», «Вернуть файлы и разговор», «Только разговор». Нет точки или файлы не менялись —
 * только разговор, с пояснением.
 */
@Composable
fun ColumnScope.RewindSheetBody(message: ChatMessage, services: ChatServices, onDone: (toast: String?) -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var plan by remember(message.messageId) { mutableStateOf<RewindPlan?>(null) }
    var loaded by remember(message.messageId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(message.messageId) { plan = services.rewindPlan(message); loaded = true }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val failed = stringResource(R.string.rw_failed)

    DshSheetTitle(stringResource(R.string.rw_title))
    // `.rw-quote`: 4 / 14, полоса 3 dp слева, две строки.
    Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.line))
        Text(
            message.content.trim(), style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, lineHeight = 20.3.sp), color = c.t2,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp),
        )
    }
    val p = plan
    when {
        !loaded -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
            Text(stringResource(R.string.rw_loading), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp), color = c.t2)
        }
        p == null || !p.hasPoint -> Note(stringResource(R.string.rw_no_point))
        p.files.isEmpty() -> Note(stringResource(R.string.rw_unchanged))
        else -> {
            Text(
                pluralStringResource(R.plurals.rw_after, p.files.size, p.files.size).uppercase(),
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em), color = c.t3,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            )
            Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 4.dp)) {
                p.files.take(4).forEachIndexed { i, (path, change) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(path, style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.5.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val (label, ink) = when (change) {
                            RewindChange.MODIFIED -> R.string.rw_mod to c.t2
                            RewindChange.REMOVED -> R.string.rw_removed to c.danger
                            RewindChange.RESTORED -> R.string.rw_restored to c.ok
                        }
                        Text(stringResource(label), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.5.sp), color = ink)
                    }
                }
                if (p.files.size > 4) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Text(
                        stringResource(R.string.rw_more, p.files.size - 4), style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp), color = c.t3,
                        modifier = Modifier.heightIn(min = 36.dp).padding(top = 9.dp),
                    )
                }
            }
        }
    }
    if (!loaded) return
    val files = p != null && p.hasPoint && p.files.isNotEmpty()
    fun go(withTalk: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            services.rewindFiles(message)
                .onSuccess { n ->
                    if (withTalk) services.rewindConversation(message)
                    onDone(res.getQuantityString(if (withTalk) R.plurals.rw_done_both else R.plurals.rw_done, n, n))
                }
                .onFailure { onDone(failed) }
            busy = false
        }
    }
    DshSheetList {
        if (files) {
            DshSheetItem(DshGlyphs.Undo, stringResource(R.string.rw_files), { go(false) }, first = true, sub = stringResource(R.string.rw_files_sub))
            DshSheetItem(DshGlyphs.Flow, stringResource(R.string.rw_both), { go(true) }, sub = stringResource(R.string.rw_both_sub))
        }
        DshSheetItem(
            DshGlyphs.Flow, stringResource(if (files) R.string.rw_talk else R.string.rw_talk_only), { services.rewindConversation(message); onDone(null) },
            first = !files, sub = stringResource(if (files) R.string.rw_talk_sub else R.string.rw_both_sub),
        )
    }
}

@Composable
private fun Note(text: String) = Text(
    text, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, lineHeight = 19.6.sp), color = Dsh.colors.t2,
    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
)
