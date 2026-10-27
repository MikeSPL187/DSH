package com.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshSheetList
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.feature.chat.ckBody
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.em
import com.dsh.core.model.AgentTeamSummary
import com.dsh.core.model.TeamTaskItem

/**
 * Нативный интерфейс плагина «Команда агентов».
 *
 * Плагин добавляет в шапку сессии действие `conversation.session.header.actions`:
 * по нему открывается состав команды и общая доска задач; данные — проекция `agentTeam`
 * текущей сессии (отдельного RPC нет). Здесь те же данные и действия, оформленные
 * системным листом.
 */
internal object AgentTeamPluginUi {
    /** Profile bundle, при котором появляется действие команды. */
    const val BUNDLE = "@deepseek-ai/dsh-experimental-agent-team-profile"

    /** Контур значка команды (фигура group из Material, поле 24dp). */
    private const val USERS_PATH =
        "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 " +
            "2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5" +
            "c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5" +
            "c0-2.33-4.67-3.5-7-3.5z"

    /** Значок действия команды в шапке сессии. */
    val usersIcon: ImageVector by lazy {
        val nodes = androidx.compose.ui.graphics.vector.PathParser()
            .parsePathString(USERS_PATH)
            .toNodes()
        ImageVector.Builder(
            name = "DshUsers",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(nodes, fill = SolidColor(Color.Black)).build()
    }

    /**
     * Действие команды в шапке сессии.
     *
     * @param memberCount число участников (больше одного — значок со счётчиком).
     * @param running кто-то из участников работает.
     * @param onClick открыть или закрыть панель.
     */
    @Composable
    fun HeaderAction(memberCount: Int, running: Boolean, onClick: () -> Unit) {
        val c = Dsh.colors
        val title = stringResource(R.string.official_agent_team_title)
        Box(
            Modifier.height(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(32.dp).clip(CircleShape).background(c.s2).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(usersIcon, null, tint = c.t1, modifier = Modifier.size(16.dp))
                Text(if (memberCount > 1) stringResource(R.string.header_agents_count, memberCount) else title, style = ckBody(12.5.sp), color = c.t1, maxLines = 1)
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (running) c.accent else c.t3))
            }
        }
    }
}

/**
 * Команда агента листом: участники (нажатие — открыть сессию участника) и общие задачи
 * (нажатие — раскрыть описание). Цвета и строки — системные.
 *
 * @param summary проекция сессии; null — данных о команде нет.
 * @param fallbackTasks задачи из `team/listTasks`, если в проекции их нет.
 * @param loading первая загрузка.
 * @param error причина ошибки чтения.
 * @param currentSessionId открытая сессия — помечается «Текущая сессия».
 * @param onOpenMember открыть сессию участника.
 */
@Composable
internal fun AgentTeamMenuPanel(
    summary: AgentTeamSummary?,
    fallbackTasks: List<TeamTaskItem>,
    loading: Boolean,
    error: String?,
    currentSessionId: String?,
    onOpenMember: (String) -> Unit,
) {
    val c = Dsh.colors
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    DshSheetTitle(stringResource(R.string.team_title))
    if (error != null) Note(error, c.danger)
    when {
        summary == null && loading -> Note(stringResource(R.string.agent_team_loading), c.t3)
        summary == null -> Note(stringResource(R.string.agent_team_unavailable), c.t3)
        summary.failure != null -> Note(stringResource(R.string.agent_team_invalid, summary.failure ?: ""), c.danger)
        else -> {
            val members = summary.members
            if (members.isNotEmpty()) {
                Group(stringResource(R.string.agent_team_members), members.size)
                DshSheetList {
                    members.forEachIndexed { i, member ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        val (phase, tone) = when (member.phase) {
                            "provisioning" -> stringResource(R.string.agent_team_phase_provisioning) to c.accent
                            "failed" -> stringResource(R.string.agent_team_phase_failed) to c.danger
                            "active" -> stringResource(R.string.agent_team_phase_active) to c.accent
                            else -> stringResource(R.string.agent_team_phase_idle) to c.t3
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = member.phase != "provisioning", role = Role.Button) { onOpenMember(member.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                                Text(member.name.take(1).uppercase(), style = ckBody(14.sp, 18.sp, FontWeight.SemiBold), color = c.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(member.name, style = ckBody(15.sp, 20.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (member.id == currentSessionId) Tag(stringResource(R.string.agent_team_current_session))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                                    Text(phase, style = ckBody(12.5.sp, 17.sp), color = tone)
                                }
                                member.error?.let { Text(it, style = ckBody(12.sp, 16.sp), color = c.t3) }
                            }
                            Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            val tasks = if (summary.tasks.isNotEmpty()) {
                summary.tasks.map { task ->
                    TeamTaskItem(id = task.id, subject = task.subject, description = task.description, status = task.status, ownerName = task.ownerName) to task
                }
            } else {
                fallbackTasks.map { it to null }
            }
            if (tasks.isEmpty()) {
                Note(stringResource(R.string.agent_team_no_tasks), c.t3, top = 14.dp)
            } else {
                Group(stringResource(R.string.agent_team_tasks), tasks.size)
                DshSheetList {
                    tasks.take(12).forEachIndexed { i, (task, full) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        val open = expanded[task.id] == true
                        val (status, tone) = when (task.status) {
                            "in_progress" -> stringResource(R.string.agent_team_task_in_progress) to c.accent
                            "completed" -> stringResource(R.string.agent_team_task_completed) to c.ok
                            else -> stringResource(R.string.agent_team_task_pending) to c.t3
                        }
                        Column(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded[task.id] = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(task.subject, style = ckBody(15.sp, 20.sp), color = c.t1, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                            if (task.description.isNotBlank()) {
                                Text(
                                    task.description, style = ckBody(13.sp, 18.sp), color = c.t2,
                                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            val owner = task.ownerName ?: stringResource(R.string.agent_team_owner_unassigned)
                            val blockedLabel = stringResource(R.string.agent_team_blocked)
                            val readyLabel = stringResource(R.string.agent_team_ready)
                            val depsLabel = stringResource(R.string.agent_team_dependencies)
                            val scopesLabel = stringResource(R.string.agent_team_write_scopes)
                            val meta = buildString {
                                append(owner)
                                if (task.status == "pending") append(" · ").append(if (full?.ready == false) blockedLabel else readyLabel)
                                if (open) {
                                    full?.blockedBy?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(depsLabel).append(": ").append(it.joinToString(", ")) }
                                    full?.writeScopes?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(scopesLabel).append(": ").append(it.joinToString(", ")) }
                                }
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(status, style = ckBody(12.sp, 16.sp, FontWeight.Medium), color = tone, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
                                Text(meta, style = ckBody(12.sp, 16.sp), color = c.t3, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (open) full?.writeScopeWarnings?.forEach { warning -> Text(warning, style = ckBody(12.sp, 16.sp), color = c.warn, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** Подзаголовок группы листа с числом. */
@Composable
private fun Group(text: String, count: Int) {
    val c = Dsh.colors
    Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = c.t3)
        if (count > 1) Text(count.toString(), style = ckBody(12.sp, 15.sp), color = c.t3)
    }
}

@Composable
private fun Note(text: String, color: Color, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(text, style = ckBody(14.5.sp, 21.sp), color = color, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 8.dp))
}

/** Метка «Текущая сессия». */
@Composable
private fun Tag(text: String) {
    val c = Dsh.colors
    Text(text, style = ckBody(11.5.sp, 15.sp, FontWeight.Medium), color = c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp))
}
