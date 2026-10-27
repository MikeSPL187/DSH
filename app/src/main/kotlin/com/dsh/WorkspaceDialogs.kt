package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dsh.core.designsystem.DshDialog
import com.dsh.core.designsystem.DshDialogButton
import com.dsh.core.designsystem.DshTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Окна проектов и сессий: новый проект, переименовать проект, удалить проект,
 * переименовать сессию. Состояние — в [AppRuntimeUiState], операции — [WorkspaceOps].
 */
@Composable
internal fun WorkspaceDialogs(
    ui: AppUiState,
    runtimeUi: AppRuntimeUiState,
    ops: WorkspaceOps,
    scope: CoroutineScope,
    runningSessionIds: Set<String>,
) {
    val context = LocalContext.current
    // Отказ — видимое сообщение с причиной, а не молча закрытое окно (G-28).
    fun report(error: Throwable) {
        val problem = (error as? WorkspaceOpException)?.problem
        android.widget.Toast.makeText(context, context.getString(workspaceProblemText(problem)), android.widget.Toast.LENGTH_LONG).show()
    }
    if (runtimeUi.showAddWorkspaceDialog) {
        val close = { runtimeUi.showAddWorkspaceDialog = false }
        DshDialog(
            onDismiss = close,
            title = stringResource(R.string.workspace_new_title),
            closeLabel = stringResource(R.string.action_close),
            content = { DshTextInput(runtimeUi.newWorkspaceName, { runtimeUi.newWorkspaceName = it }, stringResource(R.string.workspace_name_placeholder)) },
            footer = {
                DshDialogButton(stringResource(R.string.action_cancel), primary = false, enabled = true, onClick = close)
                DshDialogButton(
                    stringResource(R.string.action_create), primary = true, enabled = runtimeUi.newWorkspaceName.isNotBlank(),
                    onClick = {
                        val name = runtimeUi.newWorkspaceName
                        close()
                        scope.launch {
                            ops.create(name).onSuccess { created ->
                                runtimeUi.activeWorkspaceName = created
                            }.onFailure(::report)
                            runtimeUi.workspaceRevision += 1
                        }
                    },
                )
            },
        )
    }

    runtimeUi.renameWorkspaceFolder?.let { folder ->
        val close = { runtimeUi.renameWorkspaceFolder = null }
        DshDialog(
            onDismiss = close,
            title = stringResource(R.string.workspace_rename_title),
            closeLabel = stringResource(R.string.action_close),
            content = { DshTextInput(runtimeUi.renameWorkspaceName, { runtimeUi.renameWorkspaceName = it }, stringResource(R.string.workspace_name_placeholder)) },
            footer = {
                DshDialogButton(stringResource(R.string.action_cancel), primary = false, enabled = true, onClick = close)
                DshDialogButton(
                    stringResource(R.string.action_save), primary = true, enabled = runtimeUi.renameWorkspaceName.isNotBlank(),
                    onClick = {
                        val newName = runtimeUi.renameWorkspaceName
                        close()
                        scope.launch {
                            ops.rename(folder.name, folder.path, newName).onSuccess { renamed ->
                                if (folder.name == runtimeUi.activeWorkspaceName) runtimeUi.activeWorkspaceName = renamed
                            }.onFailure(::report)
                            runtimeUi.workspaceRevision += 1
                        }
                    },
                )
            },
        )
    }

    runtimeUi.deleteWorkspaceFolder?.let { folder ->
        val close = { runtimeUi.deleteWorkspaceFolder = null }
        // G-12: «Default» и проект, где работает агент, не удаляются; подтверждение — вводом имени проекта.
        val busy = folder.sessions.any { it.sessionId in runningSessionIds }
        val blocked = when {
            folder.name == DEFAULT_WORKSPACE_DIR_NAME -> R.string.workspace_delete_default
            busy -> R.string.workspace_delete_busy
            else -> null
        }
        var typed by remember(folder.name) { mutableStateOf("") }
        DshDialog(
            onDismiss = close,
            title = stringResource(R.string.workspace_delete_title),
            closeLabel = stringResource(R.string.action_close),
            description = if (blocked != null) stringResource(blocked, folder.name)
            else stringResource(R.string.workspace_delete_desc, folder.name) + " " + stringResource(R.string.workspace_delete_type),
            content = if (blocked == null) {
                { DshTextInput(typed, { typed = it }, folder.name) }
            } else null,
            footer = {
                DshDialogButton(stringResource(if (blocked == null) R.string.action_cancel else R.string.action_close), primary = false, enabled = true, onClick = close)
                if (blocked == null) DshDialogButton(
                    stringResource(R.string.action_delete), primary = true, enabled = typed.trim() == folder.name, danger = true,
                    onClick = {
                        close()
                        scope.launch {
                            ops.delete(folder.name, folder.path, busy).onSuccess {
                                if (folder.name == runtimeUi.activeWorkspaceName) runtimeUi.activeWorkspaceName = DEFAULT_WORKSPACE_DIR_NAME
                                // Открытая сессия удалённого проекта больше не выбрана.
                                if (folder.sessions.any { it.sessionId == ui.selectedSessionId }) ui.selectedSessionId = null
                            }.onFailure(::report)
                            runtimeUi.workspaceRevision += 1
                        }
                    },
                )
            },
        )
    }

    // Переименование сессии (`session/rename`).
    runtimeUi.renameSessionTarget?.let { target ->
        val close = { runtimeUi.renameSessionTarget = null }
        DshDialog(
            onDismiss = close,
            title = stringResource(R.string.row_rename),
            closeLabel = stringResource(R.string.action_close),
            content = { DshTextInput(runtimeUi.renameSessionTitle, { runtimeUi.renameSessionTitle = it }, stringResource(R.string.row_rename)) },
            footer = {
                DshDialogButton(stringResource(R.string.action_cancel), primary = false, enabled = true, onClick = close)
                DshDialogButton(
                    stringResource(R.string.action_save), primary = true, enabled = runtimeUi.renameSessionTitle.isNotBlank(),
                    onClick = {
                        val title = runtimeUi.renameSessionTitle
                        close()
                        scope.launch { ops.renameSession(target.sessionId, target.title, title).onFailure(::report) }
                    },
                )
            },
        )
    }
}

/** Текст причины отказа операции с проектом. */
internal fun workspaceProblemText(problem: WorkspaceProblem?): Int = when (problem) {
    WorkspaceProblem.INVALID_NAME -> R.string.workspace_err_name
    WorkspaceProblem.NAME_TOO_LONG -> R.string.workspace_err_long
    WorkspaceProblem.NAME_TAKEN -> R.string.workspace_err_taken
    WorkspaceProblem.OUTSIDE_ROOT -> R.string.workspace_err_outside
    WorkspaceProblem.DEFAULT_PROTECTED -> R.string.workspace_err_default
    WorkspaceProblem.SESSION_RUNNING -> R.string.workspace_delete_busy_short
    WorkspaceProblem.HOST_REJECTED -> R.string.workspace_err_host
    WorkspaceProblem.DISK, null -> R.string.workspace_err_disk
}
