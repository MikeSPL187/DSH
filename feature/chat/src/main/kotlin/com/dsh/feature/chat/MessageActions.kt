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
    /** Своё сообщение: новая ветка до него, текст — в поле ввода. */
    onEdit: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    /** Ответ агента: прочитать вслух или остановить ([speaking]). */
    onSpeak: (() -> Unit)? = null,
    speaking: Boolean = false,
) {
    com.dsh.core.designsystem.DshSheetTitle(stringResource(R.string.message_actions_title))
    com.dsh.core.designsystem.DshSheetList {
        com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Copy, stringResource(R.string.message_action_copy), { onCopy(message.content); onDismiss() }, first = true)
        if (onEdit != null && message.role == MessageRole.USER) {
            com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Pencil, stringResource(R.string.message_action_edit), onEdit, sub = stringResource(R.string.message_action_edit_sub))
        }
        if (onSelect != null) com.dsh.core.designsystem.DshSheetItem(com.dsh.core.designsystem.DshGlyphs.Text, stringResource(R.string.message_action_select), onSelect)
        if (onSpeak != null && message.role == MessageRole.ASSISTANT) {
            com.dsh.core.designsystem.DshSheetItem(
                if (speaking) com.dsh.core.designsystem.DshGlyphs.Stop else com.dsh.core.designsystem.DshGlyphs.Speaker,
                stringResource(if (speaking) R.string.tts_stop else R.string.message_action_speak), onSpeak,
            )
        }
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
