package com.deepseek.dsh

import com.deepseek.dsh.core.designsystem.DshIcons
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.DshTheme
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.core.model.SessionInfo
import com.deepseek.dsh.core.model.SurfaceItem
import com.deepseek.dsh.core.model.isThirdPartyPlugin
import com.deepseek.dsh.core.model.SurfaceKind
import com.deepseek.dsh.feature.chat.ModernChatScreen
import com.deepseek.dsh.feature.files.FilesScreen
import com.deepseek.dsh.feature.files.WorkspaceManager
import com.deepseek.dsh.plugin.surface.PluginOverlayContainer
import com.deepseek.dsh.plugin.surface.SurfaceCatalogStore
import com.deepseek.dsh.plugin.surface.SurfaceSheet
import com.deepseek.dsh.runtime.host.DefaultHostRuntimeManager
import com.deepseek.dsh.runtime.host.HostProcessRunner
import com.deepseek.dsh.runtime.host.HostRuntimeHolder
import com.deepseek.dsh.runtime.host.HostRuntimeManager
import com.deepseek.dsh.runtime.host.RuntimeLayout
import com.deepseek.dsh.runtime.host.RuntimePhase
import kotlinx.coroutines.launch
import java.io.File


/** Шапка вложенной страницы: та же, что у экранов дизайн-системы. */
@Composable
fun PageBackBar(
    title: String,
    onBack: () -> Unit,
) {
    com.deepseek.dsh.core.designsystem.DshTopBar(
        title = title,
        onBack = onBack,
        backDescription = stringResource(R.string.action_back),
        modifier = Modifier.background(com.deepseek.dsh.core.designsystem.Dsh.colors.bg),
    )
}

/** 子页面顶栏标题；与抽屉里的条目文案保持一致。 */
@Composable
internal fun pageTitleOf(page: ActivePage): String = when (page) {
    ActivePage.PLUGINS -> stringResource(R.string.page_plugins)
    ActivePage.AUTOMATION -> stringResource(R.string.page_automation)
    ActivePage.FILES -> stringResource(R.string.page_files)
    ActivePage.TERMINAL -> stringResource(R.string.tm_title)
    ActivePage.SETTINGS -> stringResource(R.string.page_settings)
    ActivePage.CHAT -> "deepseek"
}


/** Плашка «Доступно обновление Linux» над чатом: версия «было → станет», «Обновить» и «Скрыть». */
@Composable
internal fun RootfsUpdateBanner(
    installed: String,
    available: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.accentSoft)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onUpdate)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.Down, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.runtime_update_available), style = com.deepseek.dsh.feature.chat.ckBody(14.sp, 18.sp, androidx.compose.ui.text.font.FontWeight.Medium), color = c.t1)
            Text("$installed → $available", style = com.deepseek.dsh.feature.chat.ckBody(12.5.sp, 16.sp), color = c.t2)
        }
        Text(stringResource(R.string.action_update), style = com.deepseek.dsh.feature.chat.ckBody(14.sp, 18.sp, androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.accent)
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.X, stringResource(R.string.dismiss_reminder), tint = c.t2, modifier = Modifier.size(18.dp)) }
    }
}
@Composable
fun ModernTopAppBar(
    title: String,
    /** Папка проекта под названием (`.title span`). */
    workspace: String? = null,
    /** Host на связи — зелёная точка у папки. */
    live: Boolean = true,
    /** Лента прокручена — «стекло» и линия под шапкой. */
    scrolled: Boolean = false,
    onNew: () -> Unit = {},
    teamTasks: List<com.deepseek.dsh.core.model.TeamTaskItem> = emptyList(),
    /** 当前会话的后台任务（官方 job/list）；非空时显示任务按钮。 */
    hostJobs: List<com.deepseek.dsh.core.protocol.HostJobInfo> = emptyList(),
    onKillJob: (com.deepseek.dsh.core.protocol.HostJobInfo) -> Unit = {},
    onOpenJobOutput: (com.deepseek.dsh.core.protocol.HostJobInfo) -> Unit = {},
    /** 当前会话的团队成员投影（官方 header 的团队动作数据）。 */
    agentTeam: com.deepseek.dsh.core.model.AgentTeamSummary? = null,
    /** 团队投影第一次读取中（浮层显示「正在加载团队…」）。 */
    agentTeamLoading: Boolean = false,
    /** 「智能体团队」插件是否启用；启用才显示团队动作。 */
    teamEnabled: Boolean = false,
    currentSessionId: String? = null,
    onOpenMember: (String) -> Unit = {},
    /** 「开发者工具」插件是否启用；启用才显示会话数据诊断入口。 */
    inspectorEnabled: Boolean = false,
    onOpenInspector: () -> Unit = {},
    /** Панели нативных плагинов; null — ни один плагин их не добавил. */
    onToggleRightPane: (() -> Unit)? = null,
    onMenuClick: () -> Unit
) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    var teamPanelOpen by remember { mutableStateOf(false) }
    var jobsPanelOpen by remember { mutableStateOf(false) }
    val teamMemberCount = agentTeam?.members?.size ?: 0
    val teamRunning = agentTeam?.members?.any { it.phase == "active" || it.phase == "provisioning" } == true
    Box(modifier = Modifier.fillMaxWidth()) {
        com.deepseek.dsh.feature.chat.ChatTopBar(
            title = title,
            workspace = workspace,
            live = live,
            scrolled = scrolled,
            onMenu = onMenuClick,
            onNew = onNew,
        ) {
            // Дополнительные действия сессии — те же круглые кнопки шапки.
            if (onToggleRightPane != null) {
                com.deepseek.dsh.feature.chat.ChatIconBtn(
                    com.deepseek.dsh.core.designsystem.DshGlyphs.Puzzle, stringResource(R.string.plugin_panes_title),
                    onClick = onToggleRightPane,
                )
            }
            if (inspectorEnabled) {
                com.deepseek.dsh.feature.chat.ChatIconBtn(com.deepseek.dsh.core.designsystem.DshGlyphs.Bug, stringResource(R.string.session_diagnostics), onClick = onOpenInspector)
            }
            if (teamEnabled) {
                AgentTeamPluginUi.HeaderAction(
                    memberCount = teamMemberCount,
                    running = teamRunning,
                    onClick = { teamPanelOpen = !teamPanelOpen },
                )
            }
            // Фоновые задачи сессии (job/list): число и точка «идёт».
            if (hostJobs.isNotEmpty()) {
                val liveCount = hostJobs.count { it.status == "running" || it.status == "stopping" }
                val label = if (liveCount > 0) stringResource(R.string.header_jobs_running, liveCount) else stringResource(R.string.header_jobs_idle, hostJobs.size)
                Box(
                    Modifier.height(44.dp).clip(CircleShape).clickable { jobsPanelOpen = !jobsPanelOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.height(32.dp).clip(CircleShape).background(c.s2).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (liveCount > 0) Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                        Text(label, style = com.deepseek.dsh.feature.chat.ckBody(12.5.sp), color = c.t1, maxLines = 1)
                    }
                }
            }
        }
        // Команда агента — системный лист.
        if (teamPanelOpen) {
            com.deepseek.dsh.core.designsystem.DshHeadlessModal(onDismiss = { teamPanelOpen = false }) {
                        AgentTeamMenuPanel(
                            summary = agentTeam,
                            fallbackTasks = teamTasks,
                            loading = agentTeamLoading,
                            error = null,
                            currentSessionId = currentSessionId,
                            onOpenMember = { memberId ->
                                teamPanelOpen = false
                                onOpenMember(memberId)
                            },
                        )
                    }
        }
        // Фоновые задачи — системный лист.
        if (jobsPanelOpen) {
            com.deepseek.dsh.core.designsystem.DshHeadlessModal(onDismiss = { jobsPanelOpen = false }) {
                        JobsPanel(
                            jobs = hostJobs,
                            onKill = { job -> onKillJob(job) },
                            onOpenOutput = { job ->
                                jobsPanelOpen = false
                                onOpenJobOutput(job)
                            },
                        )
                    }
        }
    }
}
