package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.model.WorkflowRunInfo


/**
 * Запуск workflow — карточкой в стиле хода работы: название и состояние,
 * ниже по фазам — участники (дочерние сессии) с точками состояния.
 */
@Composable
fun WorkflowRunCard(run: WorkflowRunInfo, modifier: Modifier = Modifier) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.s1).border(1.dp, c.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.Flow, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(run.name.ifBlank { run.runId }, style = ckBody(14.5.sp, 18.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.workflow_run_title), style = ckBody(12.5.sp, 16.sp), color = c.t3)
            }
            val (bg, fg) = when (run.status) {
                "completed" -> c.okSoft to c.ok
                "failed", "cancelled", "interrupted" -> c.dangerSoft to c.danger
                else -> c.accentSoft to c.accent
            }
            Text(statusLabel(run.status), style = ckBody(12.sp, 15.sp, FontWeight.Medium), color = fg, modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        run.phases.forEach { phase ->
            phase.phase?.let { Text(it.ifBlank { stringResource(R.string.workflow_phase_default) }.uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.06f, androidx.compose.ui.unit.TextUnitType.Em)), color = c.t3) }
            Steps(phase.members.map { m ->
                val st = when (m.status) {
                    "completed" -> StepState.Ok
                    "failed", "cancelled", "interrupted" -> StepState.Warn
                    "running" -> StepState.Running
                    else -> StepState.Plain
                }
                st to @Composable {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label.ifBlank { m.childId }, style = ckBody(14.sp, 18.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(statusLabel(m.status), style = ckBody(12.sp, 15.sp), color = c.t3)
                    }
                }
            })
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.workflow_status_running)
    "completed" -> stringResource(R.string.workflow_status_completed)
    "failed" -> stringResource(R.string.workflow_status_failed)
    "cancelled" -> stringResource(R.string.workflow_status_cancelled)
    "interrupted" -> stringResource(R.string.workflow_status_interrupted)
    else -> status
}


