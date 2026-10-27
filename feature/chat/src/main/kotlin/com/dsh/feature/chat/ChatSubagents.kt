package com.dsh.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.model.SubagentInfo

/** Разговор субагента, открытый из листа «Субагенты» родительской сессии. */
data class SubagentView(
    val parentSessionId: String,
    val parentTitle: String,
    val childSessionId: String,
    val label: String,
    val mode: SubagentInfo.Mode,
)

/**
 * Лист `#sheet-subagents`: число и сколько работает, строки — состояние (работает / готово / неактивен),
 * название, режим и время; внешняя задача без разговора в DSH не открывается.
 */
@Composable
internal fun ColumnScope.SubagentsSheetBody(items: List<SubagentInfo>, running: Set<String>, onOpen: (SubagentInfo) -> Unit) {
    val c = Dsh.colors
    DshSheetTitleText(stringResource(R.string.subagents_title))
    val active = items.count { it.id in running }
    Text(
        pluralStringResource(R.plurals.subagents_count, items.size, items.size) +
            if (active > 0) " · " + pluralStringResource(R.plurals.subagents_running, active, active) else "",
        style = ckBody(13.5.sp, 18.sp), color = c.t3, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
    )
    items.forEach { s ->
        val isRunning = s.id in running
        val openable = s.mode != SubagentInfo.Mode.EXTERNAL
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
                .clickable(enabled = openable, role = Role.Button) { onOpen(s) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                when {
                    isRunning -> Spinner()
                    s.mode == SubagentInfo.Mode.EXTERNAL -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.t3))
                    else -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.ok))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    s.label.ifBlank { s.id.take(8) }, style = ckBody(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val time = s.createdAt.takeIf { it > 0 }?.let {
                    android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                }
                val state = stringResource(
                    when {
                        isRunning -> R.string.subagents_state_running
                        s.mode == SubagentInfo.Mode.EXTERNAL -> R.string.subagents_state_external
                        else -> R.string.subagents_state_done
                    },
                )
                Text(listOfNotNull(state, time).joinToString(" · "), style = ckBody(12.5.sp, 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val continuable = s.mode == SubagentInfo.Mode.CONTINUABLE
            Box(
                Modifier.height(24.dp).clip(CircleShape).background(if (continuable) c.accentSoft else c.s2).padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(
                        when (s.mode) {
                            SubagentInfo.Mode.CONTINUABLE -> R.string.subagents_mode_continuable
                            SubagentInfo.Mode.EXTERNAL -> R.string.subagents_mode_external
                            else -> R.string.subagents_mode_one_shot
                        },
                    ),
                    style = ckBody(11.5.sp, 14.sp, FontWeight.SemiBold), color = if (continuable) c.accent else c.t2, maxLines = 1,
                )
            }
            if (openable) Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DshSheetTitleText(text: String) {
    Text(
        text, style = ckDisplay(19.sp, 23.sp), color = Dsh.colors.t1,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
    )
}

/** Кольцо «работает» 12 dp, как `.sa-st i.run`. */
@Composable
private fun Spinner() {
    val c = Dsh.colors
    val angle by rememberInfiniteTransition(label = "sa").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Canvas(Modifier.size(12.dp)) {
        val w = 2.dp.toPx()
        drawArc(c.accentSoft, 0f, 360f, false, style = Stroke(w))
        drawArc(c.accent, angle, 90f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Плашка `.sa-band` под шапкой в разговоре субагента: название, родитель и «К сессии». */
@Composable
internal fun SubagentBand(view: SubagentView, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.padding(horizontal = 10.dp).fillMaxWidth().floatShadow(shape, c.isDark).clip(shape).background(c.glass.compositeOn(c.bg))
            .border(1.dp, c.glassLine, shape).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.subagents_band, view.label), style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.subagents_band_from, view.parentTitle), style = ckBody(12.5.sp, 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Btn(stringResource(R.string.subagents_back), BtnStyle.Ghost, height = 40.dp, radius = 12.dp, fontSize = 14f, onClick = onBack)
    }
}
