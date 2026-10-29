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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.data.AgentMemory
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.small

/**
 * `.mem-card` под ответом агента: агент предложил запомнить [fact] (`<dsh-memory>`), решает пользователь.
 * «Не надо» прячет карточку, «Запомнить» сохраняет в память; уже сохранённый факт — «В памяти» и «Открыть».
 */
@Composable
internal fun MemoryProposalCard(fact: String, onOpenMemory: () -> Unit) {
    val c = Dsh.colors
    val state by AgentMemory.state.collectAsState()
    val saved = state.facts.any { it.text.equals(AgentMemory.normalize(fact), ignoreCase = true) }
    var dismissed by rememberSaveable(fact) { mutableStateOf(false) }
    AnimatedVisibility(!dismissed || saved, exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.padding(bottom = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1)
                .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Si.c3), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Mem, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mem_card_label), style = small(12.5.sp, 16.sp), color = c.t3, modifier = Modifier.padding(bottom = 2.dp))
                    Text(fact, style = body(15.sp, 21.sp), color = c.t1)
                }
            }
            AnimatedContent(saved, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mem") { done ->
                if (done) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(DshGlyphs.Check, null, tint = c.ok, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.mem_card_done), style = body(14.sp, 18.sp), color = c.ok, modifier = Modifier.weight(1f))
                        Box(Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onOpenMemory).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.mem_card_open), style = body(14.sp, 18.sp), color = c.accent)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        KBtn(stringResource(R.string.mem_card_no), { dismissed = true }, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 16.dp)
                        KBtn(stringResource(R.string.mem_card_yes), { AgentMemory.add(fact, fromChat = true) }, kind = BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.5.sp, horizontal = 16.dp)
                    }
                }
            }
        }
    }
}
