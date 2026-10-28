package com.dsh.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole


/**
 * Лист действий с сообщением (`#sheet-msg`, долгое нажатие): копировать текст, начать новую сессию с этого места,
 * у своего сообщения — «Вернуться сюда» ([onRewind]); после возврата файлов — «Отменить возврат файлов» ([onUndo]).
 */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.MessageActionsBody(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onFork: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRewind: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    com.dsh.core.designsystem.DshSheetTitle(stringResource(R.string.message_actions_title))
    com.dsh.core.designsystem.DshSheetList {
        com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Copy, stringResource(R.string.message_action_copy), { onCopy(message.content); onDismiss() }, first = true)
        if (message.seq > 0L && (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)) {
            com.dsh.core.designsystem.DshSheetItem(
                com.dsh.core.designsystem.DshGlyphs.Flow, stringResource(R.string.message_action_fork), { onFork(message.seq.toInt()); onDismiss() },
                sub = stringResource(R.string.message_action_fork_sub),
            )
        }
        if (onRewind != null && message.role == MessageRole.USER) {
            com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Undo, stringResource(R.string.rw_here), onRewind, sub = stringResource(R.string.rw_here_sub))
        }
        if (onUndo != null) {
            com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Redo, stringResource(R.string.rw_undo), onUndo, sub = stringResource(R.string.rw_undo_sub))
        }
    }
}
