package com.dsh.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.data.AgentMemory
import com.dsh.core.data.MemoryProposal
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.small

/**
 * `.mem-card` под ответом агента [messageId]: агент предложил запомнить [p] (`<dsh-memory>`).
 * Не решено — «Не надо» / «Запомнить»; в памяти — «В памяти» (при «Сохранять без вопроса» — «Запомнено»),
 * «Открыть» и «Отменить». Решение помнится по сообщению: «Не надо» и «Отменить» прячут карточку насовсем.
 * Факт `scope="project"` относится к текущему проекту [project] (путь папки), без проекта — ко всем.
 */
@Composable
internal fun MemoryProposalCard(messageId: String, p: MemoryProposal, project: String?, onOpenMemory: () -> Unit) {
    val c = Dsh.colors
    val state by AgentMemory.state.collectAsState()
    val where = if (p.project) project else null
    val saved = state.facts.any { it.project == where && it.text.equals(p.text, ignoreCase = true) }
    val handled = messageId in state.handled
    val projectName = where?.let { java.io.File(it).name }
    AnimatedVisibility(saved || !handled, exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.padding(bottom = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1)
                .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Si.c3), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Mem, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.weight(1f)) {
                    val label = when {
                        saved && projectName != null -> stringResource(R.string.mem_card_label_project_saved, projectName)
                        saved -> stringResource(R.string.mem_card_label_saved)
                        projectName != null -> stringResource(R.string.mem_card_label_project, projectName)
                        else -> stringResource(R.string.mem_card_label)
                    }
                    Text(label, style = small(12.5.sp, 16.sp), color = c.t3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
                    Text(p.text, style = body(15.sp, 21.sp), color = c.t1)
                }
            }
            AnimatedContent(saved, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mem") { done ->
                if (done) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(DshGlyphs.Check, null, tint = c.ok, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(if (state.auto) R.string.mem_card_auto_done else R.string.mem_card_done), style = body(14.sp, 18.sp), color = c.ok,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp),
                        )
                        LinkBtn(stringResource(R.string.mem_card_open), onOpenMemory)
                        LinkBtn(stringResource(R.string.mem_card_undo)) { AgentMemory.forget(p.text, where); AgentMemory.markHandled(messageId) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        KBtn(stringResource(R.string.mem_card_no), { AgentMemory.markHandled(messageId) }, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 16.dp)
                        KBtn(
                            stringResource(R.string.mem_card_yes), { AgentMemory.add(p.text, fromChat = true, project = where); AgentMemory.markHandled(messageId) },
                            kind = BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 16.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Текстовая кнопка акцентом высотой 40 (`.mem-card .done button`). */
@Composable
private fun LinkBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = body(14.sp, 18.sp), color = Dsh.colors.accent, maxLines = 1)
    }
}
