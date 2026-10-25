package com.deepseek.dsh

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshDialog
import com.deepseek.dsh.core.designsystem.DshDialogButton
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshSheetList
import com.deepseek.dsh.core.designsystem.DshSheetTitle
import com.deepseek.dsh.core.protocol.HostJobInfo
import com.deepseek.dsh.feature.chat.ckBody

/**
 * Фоновые задачи сессии (`job/list`) листом: состояние, название, вид и ход,
 * «Остановить» у идущих; нажатие открывает вывод (`job/follow`).
 */
@Composable
fun JobsPanel(
    jobs: List<HostJobInfo>,
    onKill: (HostJobInfo) -> Unit,
    onOpenOutput: (HostJobInfo) -> Unit,
) {
    val c = Dsh.colors
    DshSheetTitle(stringResource(R.string.jobs_title))
    if (jobs.isEmpty()) {
        Text(stringResource(R.string.jobs_empty), style = ckBody(14.5.sp, 21.sp), color = c.t3, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        return
    }
    DshSheetList {
        jobs.forEachIndexed { i, job ->
            val running = job.status == "running" || job.status == "stopping"
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenOutput(job) }.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.s1), contentAlignment = Alignment.Center) {
                    if (running) {
                        com.deepseek.dsh.core.designsystem.DshSpinner(c.accent, 16.dp)
                    } else {
                        val dot = when (job.status) {
                            "completed" -> c.ok
                            "failed", "killed" -> c.danger
                            else -> c.t3
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(job.label.ifBlank { job.id }, style = ckBody(15.sp, 20.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = buildString {
                        append(job.kind)
                        job.progress?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        if (!running) job.detail?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    }
                    if (sub.isNotBlank()) Text(sub, style = ckBody(12.5.sp, 17.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (running) Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onKill(job) }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.jobs_kill), style = ckBody(13.5.sp, 18.sp, FontWeight.Medium), color = c.danger) }
            }
        }
    }
}

/** Вывод фоновой задачи (`job/follow`): строка состояния и хвост вывода моноширинным, прокручен к низу. */
@Composable
fun JobOutputDialog(
    job: HostJobInfo,
    output: String,
    statusLine: String?,
    onDismiss: () -> Unit,
) {
    val c = Dsh.colors
    DshDialog(
        onDismiss = onDismiss,
        title = job.label.ifBlank { job.id },
        closeLabel = stringResource(R.string.action_close),
        description = statusLine?.takeIf { it.isNotBlank() },
        footer = {
            DshDialogButton(text = stringResource(R.string.action_close), primary = false, enabled = true, onClick = onDismiss)
        },
        content = {
            if (output.isBlank()) {
                Text(stringResource(R.string.jobs_output_empty), style = ckBody(14.5.sp, 21.sp), color = c.t3, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                val scroll = rememberScrollState()
                LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).background(c.termBg)
                        .border(1.dp, c.line, RoundedCornerShape(16.dp)).verticalScroll(scroll).padding(12.dp),
                ) {
                    Text(output, style = androidx.compose.ui.text.TextStyle(fontFamily = DshFonts.mono, fontSize = 12.sp, lineHeight = 17.sp), color = c.termText)
                }
            }
        },
    )
}
