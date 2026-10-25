package com.deepseek.dsh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


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

/** Заголовок вложенной страницы; совпадает с пунктом меню. */
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
    /** Фоновые процессы сессии (job/list); если есть — кнопка задач. */
    hostJobs: List<com.deepseek.dsh.core.protocol.HostJobInfo> = emptyList(),
    onKillJob: (com.deepseek.dsh.core.protocol.HostJobInfo) -> Unit = {},
    onOpenJobOutput: (com.deepseek.dsh.core.protocol.HostJobInfo) -> Unit = {},
    /** Команда текущей сессии (данные действия команды в шапке). */
    agentTeam: com.deepseek.dsh.core.model.AgentTeamSummary? = null,
    /** Команда читается впервые (панель показывает «Загрузка команды…»). */
    agentTeamLoading: Boolean = false,
    /** Включён ли плагин «Команда агентов»; только тогда есть действие команды. */
    teamEnabled: Boolean = false,
    currentSessionId: String? = null,
    onOpenMember: (String) -> Unit = {},
    /** Включён ли плагин «Инструменты разработчика»; только тогда есть диагностика сессии. */
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
