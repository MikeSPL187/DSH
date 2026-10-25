package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.model.QueuedMessageInfo

/**
 * Очередь сообщений, отправленных во время работы агента (inbox Host): над полем ввода,
 * «стеклянной» карточкой. Каждое можно поправить, убрать или передать агенту сейчас.
 * Несколько — свёрнуты в строку с числом.
 */
@Composable
fun QueuedMessagesDock(
    messages: List<QueuedMessageInfo>,
    running: Boolean,
    onEdit: (QueuedMessageInfo, String) -> Unit,
    onRemove: (QueuedMessageInfo) -> Unit,
    onSteer: (QueuedMessageInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    val c = Dsh.colors
    var expanded by remember(messages.size) { mutableStateOf(messages.size == 1) }
    var editing by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().floatShadow(shape, c.isDark).clip(shape)
            .background(c.glass.compositeOn(c.bg)).border(1.dp, c.glassLine, shape).padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = messages.size > 1, role = Role.Button) { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(DshGlyphs.Clock, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.queue_title, messages.size), style = ckBody(13.5.sp, 17.sp, FontWeight.Medium), color = c.t1, modifier = Modifier.weight(1f))
            if (messages.size > 1) Icon(DshGlyphs.Chev, null, tint = c.t3, modifier = Modifier.size(16.dp))
        }
        if (expanded) messages.forEach { m ->
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, bottom = 8.dp)) {
                if (editing == m.id) {
                    var draft by remember(m.id) { mutableStateOf(m.text) }
                    BasicTextField(
                        draft, { draft = it }, textStyle = ckBody(15.sp, 20.sp).copy(color = c.t1), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.s2).padding(10.dp),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallAction(stringResource(R.string.queue_cancel)) { editing = null }
                        SmallAction(stringResource(R.string.queue_save), accent = true) {
                            editing = null
                            draft.trim().takeIf { it.isNotEmpty() }?.let { onEdit(m, it) }
                        }
                    }
                } else {
                    Text(m.text, style = ckBody(14.sp, 19.sp), color = c.t2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (running) SmallAction(stringResource(R.string.queue_steer), DshGlyphs.Bolt, accent = true) { onSteer(m) }
                        SmallAction(stringResource(R.string.queue_edit), DshGlyphs.Pencil) { editing = m.id }
                        SmallAction(stringResource(R.string.queue_remove), DshGlyphs.Trash) { onRemove(m) }
                    }
                }
            }
        }
    }
}
