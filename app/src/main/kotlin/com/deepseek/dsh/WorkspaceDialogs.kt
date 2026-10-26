package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.deepseek.dsh.core.designsystem.DshDialog
import com.deepseek.dsh.core.designsystem.DshDialogButton
import com.deepseek.dsh.core.designsystem.DshTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Окна проектов и сессий: новый проект, переименовать проект, удалить проект,
 * переименовать сессию. Состояние — в [AppRuntimeUiState], операции — [WorkspaceOps].
 */
@Composable
internal fun WorkspaceDialogs(runtimeUi: AppRuntimeUiState, ops: WorkspaceOps, scope: CoroutineScope) {
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
                            ops.create(name)?.let { created ->
                                runtimeUi.activeWorkspaceName = created
                                runtimeUi.workspaceRevision += 1
                            }
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
                            ops.rename(folder.name, folder.path, newName)?.let { renamed ->
                                if (folder.name == runtimeUi.activeWorkspaceName) runtimeUi.activeWorkspaceName = renamed
                                runtimeUi.workspaceRevision += 1
                            }
                        }
                    },
                )
            },
        )
    }

    runtimeUi.deleteWorkspaceFolder?.let { folder ->
        val close = { runtimeUi.deleteWorkspaceFolder = null }
        DshDialog(
            onDismiss = close,
            title = stringResource(R.string.workspace_delete_title),
            closeLabel = stringResource(R.string.action_close),
            description = stringResource(R.string.workspace_delete_desc, folder.name),
            footer = {
                DshDialogButton(stringResource(R.string.action_cancel), primary = false, enabled = true, onClick = close)
                DshDialogButton(
                    stringResource(R.string.action_delete), primary = true, enabled = true, danger = true,
                    onClick = {
                        close()
                        if (folder.name == runtimeUi.activeWorkspaceName) runtimeUi.activeWorkspaceName = DEFAULT_WORKSPACE_DIR_NAME
                        scope.launch {
                            ops.delete(folder.name, folder.path)
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
                        scope.launch { ops.renameSession(target.sessionId, target.title, title) }
                    },
                )
            },
        )
    }
}
