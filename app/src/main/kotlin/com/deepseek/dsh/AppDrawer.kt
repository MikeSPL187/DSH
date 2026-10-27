package com.deepseek.dsh

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.data.HostWorkspaceInfo
import com.deepseek.dsh.core.model.SessionInfo
import com.deepseek.dsh.feature.sessions.DshDrawerState
import com.deepseek.dsh.feature.sessions.ProjectSheetBody
import com.deepseek.dsh.feature.sessions.SessionSheetBody
import com.deepseek.dsh.feature.sessions.WorkspaceFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Меню: открыто ли оно, открытый лист («Проект», действия с сессией), поиск и архив. */
@Stable
class AppDrawerState {
    val drawer = DshDrawerState()
    var sheet by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    var query by mutableStateOf("")
    var showArchived by mutableStateOf(false)
}

/**
 * Меню (`#drawer`) с данными приложения: сессии текущего проекта, поиск по Host,
 * листы «Проект» и действий с сессией, переходы на страницы.
 */
@Composable
internal fun AppDrawer(
    state: AppDrawerState,
    ui: AppUiState,
    runtimeUi: AppRuntimeUiState,
    repository: DshRepository,
    scope: CoroutineScope,
    folders: List<WorkspaceFolder>,
    hostWorkspaces: List<HostWorkspaceInfo>,
    workspaceRoot: java.io.File,
    sessions: List<SessionInfo>,
    waitingIds: Set<String>,
    previews: Map<String, String>,
    hostLive: Boolean,
    defaultWorkspaceLabel: String,
    pluginsOn: Int,
    pluginsAwaitingTrust: Int,
    userName: String?,
    updateVersion: String?,
) {
    val context = LocalContext.current

    val currentFolder = folders.firstOrNull { it.name == runtimeUi.activeWorkspaceName } ?: folders.firstOrNull()
    val drawerSessions = currentFolder?.sessions.orEmpty().map { s ->
        com.deepseek.dsh.feature.sessions.DrawerSession(
            id = s.sessionId,
            title = s.title.ifBlank { context.getString(R.string.drawer_untitled_session) },
            time = if (s.isRunning) context.getString(R.string.drawer_now) else com.deepseek.dsh.feature.sessions.drawerTime(s.updatedAt),
            preview = previews[s.sessionId],
            updatedAt = s.updatedAt,
            running = s.isRunning,
            waiting = s.sessionId in waitingIds,
            pinned = s.pinned,
            archived = s.archived,
        )
    }
    fun go(page: ActivePage) {
        state.drawer.close()
        ui.activePage = page
    }
    val projectName = currentFolder?.name?.let { if (it == DEFAULT_WORKSPACE_DIR_NAME) defaultWorkspaceLabel else it }
        ?: runtimeUi.activeWorkspaceName.ifBlank { defaultWorkspaceLabel }
    com.deepseek.dsh.feature.sessions.DshDrawerContent(
        hostLive = hostLive,
        project = projectName,
        projectLetter = projectName.take(1).uppercase(),
        onProject = {
            state.sheet = {
                ProjectSheetBody(
                    projects = folders,
                    selectedName = currentFolder?.name.orEmpty(),
                    displayName = { name -> if (name == DEFAULT_WORKSPACE_DIR_NAME) defaultWorkspaceLabel else name },
                    onSelect = { folder -> runtimeUi.activeWorkspaceName = folder.name; state.sheet = null },
                    onAdd = {
                        state.sheet = null
                        runtimeUi.newWorkspaceName = ""
                        runtimeUi.showAddWorkspaceDialog = true
                    },
                    onRename = { folder ->
                        state.sheet = null
                        runtimeUi.renameWorkspaceFolder = folder
                        runtimeUi.renameWorkspaceName = folder.name
                    },
                    onMove = { folder, delta ->
                        val index = hostWorkspaces.indexOf(hostWorkspaces.forFolder(folder.path, workspaceRoot))
                        val targetIndex = index + delta
                        if (index >= 0 && delta != 0 && targetIndex in hostWorkspaces.indices) {
                            // Вниз — перед элементом после цели, вверх — перед целью; в конец — без beforeWorkspaceId.
                            val beforeId = hostWorkspaces.getOrNull(if (delta > 0) targetIndex + 1 else targetIndex)?.id
                            scope.launch { repository.moveWorkspace(ui.activeHostId, hostWorkspaces[index].id, beforeId) }
                        }
                        state.sheet = null
                    },
                    onDelete = { folder ->
                        state.sheet = null
                        runtimeUi.deleteWorkspaceFolder = folder
                    },
                )
            }
        },
        sessions = drawerSessions,
        selectedId = ui.selectedSessionId,
        onSelect = { sessionId ->
            ui.selectedSessionId = sessionId
            go(ActivePage.CHAT)
        },
        onSessionMenu = { item ->
            val display = currentFolder?.sessions?.firstOrNull { it.sessionId == item.id }
            state.sheet = {
                SessionSheetBody(
                    session = item,
                    onRename = {
                        state.sheet = null
                        if (display != null) {
                            runtimeUi.renameSessionTarget = display
                            runtimeUi.renameSessionTitle = display.title
                        }
                    },
                    onPin = {
                        state.sheet = null
                        scope.launch { repository.setSessionPinned(ui.activeHostId, item.id, !item.pinned) }
                    },
                    onFork = {
                        state.sheet = null
                        scope.launch {
                            val forked = repository.forkSession(ui.activeHostId, item.id)
                            if (forked != null) {
                                ui.selectedSessionId = forked
                                go(ActivePage.CHAT)
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.session_fork_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onArchive = {
                        state.sheet = null
                        scope.launch { repository.setSessionArchived(ui.activeHostId, item.id, !item.archived) }
                    },
                )
            }
        },
        onNew = {
            // Новая сессия создаётся на Host только с первым сообщением.
            ui.selectedSessionId = null
            go(ActivePage.CHAT)
        },
        query = state.query,
        onQuery = { query ->
            state.query = query
            if (query.isBlank()) {
                runtimeUi.remoteSearchResults = emptyList()
            } else {
                scope.launch {
                    val hits = repository.searchSessions(ui.activeHostId, query)
                    if (state.query != query) return@launch
                    val titles = sessions.associate { it.sessionId to it.title }
                    runtimeUi.remoteSearchResults = hits.map { hit ->
                        val title = titles[hit.sessionId]
                        hit.sessionId to if (title.isNullOrBlank()) hit.snippet else "$title · ${hit.snippet}"
                    }
                }
            }
        },
        searchHits = runtimeUi.remoteSearchResults,
        pluginsOn = pluginsOn,
        pluginsAwaitingTrust = pluginsAwaitingTrust,
        automationsOn = com.deepseek.dsh.feature.settings.logic.AutomationStore.items.count { it.on },
        onPlugins = { go(ActivePage.PLUGINS) },
        onAutomation = { go(ActivePage.AUTOMATION) },
        userName = userName ?: stringResource(R.string.drawer_default_user),
        footerSub = stringResource(
            if (com.deepseek.dsh.feature.settings.logic.ApiKeyVault.has(DEEPSEEK_VAULT_REF)) R.string.drawer_foot_key else R.string.drawer_foot_nokey,
        ),
        onSettings = { go(ActivePage.SETTINGS) },
        updateVersion = updateVersion,
        onUpdate = {
            ui.settingsInitialSection = com.deepseek.dsh.feature.settings.ui.StPage.About
            go(ActivePage.SETTINGS)
        },
        archivedCount = currentFolder?.sessions.orEmpty().count { it.archived },
        showArchived = state.showArchived,
        onToggleArchived = { state.showArchived = !state.showArchived },
    ) {
        // sidebar.*: место нативных плагинов внизу меню.
        com.deepseek.dsh.plugin.nativehost.NativeSlot(
            slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.SIDEBAR_PANEL,
            spec = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
    }
}
