package com.deepseek.dsh.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshButton
import com.deepseek.dsh.core.designsystem.DshButtonKind
import kotlinx.coroutines.delay

/** Насколько рискованно действие, о котором спрашивает агент. */
enum class ApprovalTier { WRITE, DESTRUCTIVE }

/**
 * Уровень риска по имени инструмента и его аргументам: удаление и затирание —
 * необратимы, остальное — изменение файлов или запуск команды.
 */
fun approvalTier(toolName: String, args: String?): ApprovalTier {
    val text = (toolName + " " + args.orEmpty()).lowercase()
    val destructive = listOf("rm -", "rm\t", "rmdir", "delete", "remove", "unlink", "truncate", "git reset --hard", "git clean", "drop table", "mkfs", "dd if=")
    return if (destructive.any { it in text }) ApprovalTier.DESTRUCTIVE else ApprovalTier.WRITE
}

/** Команда или путь из JSON-аргументов вызова — то, что агент собирается сделать. */
fun approvalTarget(args: String?): String? {
    if (args.isNullOrBlank()) return null
    val keys = listOf("command", "cmd", "path", "file_path", "filePath", "target", "url")
    for (key in keys) {
        val match = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(args) ?: continue
        return match.groupValues[1].replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\").trim().ifBlank { null }
    }
    return args.trim().takeIf { it.length in 1..200 && !it.startsWith("{") }
}

/**
 * Лист одобрения действия агента. Для необратимых действий «Разрешить»
 * срабатывает со второго касания: первое только взводит кнопку на 3 секунды.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    toolName: String,
    reason: String?,
    args: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    val c = Dsh.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onReject,
        sheetState = sheetState,
        containerColor = c.s1,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.s3))
        },
    ) {
        ApprovalSheetContent(toolName, reason, args, onAllow, onReject)
    }
}

/** Содержимое листа одобрения (отдельно — чтобы показывать и проверять без окна листа). */
@Composable
fun ApprovalSheetContent(
    toolName: String,
    reason: String?,
    args: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    val c = Dsh.colors
    val t = Dsh.type
    val tier = remember(toolName, args) { approvalTier(toolName, args) }
    val target = remember(args) { approvalTarget(args) }
    val danger = tier == ApprovalTier.DESTRUCTIVE
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    run {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (danger) c.dangerSoft else c.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (danger) Icons.Rounded.Delete else Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (danger) c.danger else c.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (danger) c.dangerSoft else c.accentSoft).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (danger) Icon(Icons.Rounded.Warning, null, tint = c.danger, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                        Text(
                            stringResource(if (danger) R.string.chat_approval_tier_destructive else R.string.chat_approval_tier_write).uppercase(),
                            style = t.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = if (danger) c.danger else c.accent,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.chat_approval_sheet_title),
                        style = t.headline,
                        color = c.t1,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (reason.isNullOrBlank()) stringResource(R.string.chat_approval_no_reason) else reason,
                style = t.body,
                color = c.t2,
            )
            Spacer(Modifier.height(12.dp))
            // Что именно будет выполнено: инструмент и команда или путь.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.codeBg)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(toolName, style = t.footnote, color = c.t3)
                if (target != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(target, style = t.code, color = if (danger) c.danger else c.t1, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            if (danger) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.chat_approval_irreversible), style = t.footnote, color = c.danger)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshButton(stringResource(R.string.chat_approval_reject), onReject, Modifier.weight(1f), kind = DshButtonKind.Ghost)
                val allowText = when {
                    danger && armed -> stringResource(R.string.chat_approval_tap_again)
                    else -> stringResource(R.string.chat_approval_allow_once)
                }
                DshButton(
                    allowText,
                    onClick = {
                        if (danger && !armed) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            armed = true
                        } else {
                            onAllow()
                        }
                    },
                    modifier = Modifier.weight(1.3f),
                    kind = if (danger) DshButtonKind.Danger else DshButtonKind.Primary,
                )
            }
            if (danger) {
                Text(
                    stringResource(if (armed) R.string.chat_approval_armed_hint else R.string.chat_approval_two_taps),
                    style = t.footnote,
                    color = c.t3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
