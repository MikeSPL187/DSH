package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshSheetItem
import com.deepseek.dsh.core.designsystem.DshSheetList
import com.deepseek.dsh.core.designsystem.DshSheetTitle
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole

/**
 * Лист действий с сообщением (долгое нажатие): копировать, выделить фрагмент,
 * изменить своё сообщение и отправить заново, ответить заново, начать новую сессию с этого места.
 * «Выделить текст» открывает текст в том же листе с системным выделением.
 */
@Composable
fun ColumnScope.MessageActionsBody(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onFork: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** Своё сообщение — в поле ввода для правки; null — действия нет. */
    onEdit: ((String) -> Unit)? = null,
    /** Ответ агента — запросить заново; null — действия нет. */
    onRetry: (() -> Unit)? = null,
) {
    var selecting by remember(message.messageId) { mutableStateOf(false) }
    val text = remember(message.content) { message.content.substringAfter('\u0001') }
    if (selecting) {
        DshSheetTitle(stringResource(R.string.message_select_title))
        Text(
            stringResource(R.string.message_select_hint), style = ckBody(13.sp, 18.sp), color = Dsh.colors.t3,
            modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 10.dp),
        )
        SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(text, style = ckBody(16.sp, 25.6.sp), color = Dsh.colors.t1)
        }
        return
    }
    DshSheetTitle(stringResource(R.string.message_actions_title))
    DshSheetList {
        DshSheetItem(DshGlyphs.Copy, stringResource(R.string.message_action_copy), { onCopy(text); onDismiss() }, first = true)
        DshSheetItem(DshGlyphs.Text, stringResource(R.string.message_action_select), { selecting = true })
        if (onEdit != null && message.role == MessageRole.USER) {
            DshSheetItem(DshGlyphs.Pencil, stringResource(R.string.message_action_edit), { onEdit(text); onDismiss() })
        }
        if (onRetry != null && message.role == MessageRole.ASSISTANT) {
            DshSheetItem(DshGlyphs.Redo, stringResource(R.string.message_action_retry), { onRetry(); onDismiss() })
        }
        if (message.seq > 0L && (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)) {
            DshSheetItem(DshGlyphs.Flow, stringResource(R.string.message_action_fork), { onFork(message.seq.toInt()); onDismiss() })
        }
    }
}
