package com.dsh

import com.dsh.core.data.HostWorkspaceInfo
import com.dsh.core.model.SessionInfo
import com.dsh.feature.sessions.SessionDisplayItem
import com.dsh.feature.sessions.WorkspaceFolder
import java.io.File

/** Корень рабочих папок внутри Host. */
internal const val HOST_WORKSPACE_ROOT = "/workspace"

/**
 * Имя папки проекта по пути. Пути бывают двух видов: внутри Host (`/workspace/<папка>/…` —
 * так создаются сессии) и на телефоне (`<корень>/<папка>/…` — так приложение регистрирует папки
 * в Host). Сравнивать пути напрямую нельзя, имя папки — общее у обоих.
 * @return имя папки; путь вне рабочих папок или сам корень — null.
 */
internal fun projectFolderOf(path: String?, root: File): String? {
    if (path.isNullOrBlank()) return null
    val normalized = path.trimEnd('/')
    val relative = listOf(HOST_WORKSPACE_ROOT, root.absolutePath.trimEnd('/'))
        .firstNotNullOfOrNull { prefix -> normalized.removePrefix("$prefix/").takeIf { it != normalized } }
        ?: return null
    return relative.substringBefore('/').takeIf { it.isNotEmpty() }
}

/**
 * Папки проектов с их сессиями для меню.
 *
 * Порядок папок — из проекции `workspace/follow` Host (без неё — как на диске); название — имя папки
 * на диске: по нему выбирается текущий проект, а переименование в приложении меняет и диск, и Host.
 * Порядок сессий в папке — тоже от Host. Сессии без известной папки (старые, без пути) показываются
 * в папке по умолчанию (или в первой), чтобы не пропасть из меню. Первая папка раскрыта.
 *
 * @param dirs папки проектов на диске (подпапки [root]).
 * @param timeAgo «5 мин» по времени последнего изменения.
 */
internal fun buildWorkspaceFolders(
    dirs: List<File>,
    root: File,
    sessions: List<SessionInfo>,
    hostWorkspaces: List<HostWorkspaceInfo>,
    selectedSessionId: String?,
    runningIds: Set<String>,
    unreadIds: Set<String>,
    timeAgo: (Long) -> String,
): List<WorkspaceFolder> {
    val hostByFolder = hostWorkspaces.mapNotNull { ws -> projectFolderOf(ws.path, root)?.let { it to ws } }.toMap()
    val hostOrder = hostWorkspaces.mapNotNull { projectFolderOf(it.path, root) }
    val ordered = dirs.sortedBy { dir -> hostOrder.indexOf(dir.name).let { if (it < 0) Int.MAX_VALUE else it } }
    val names = ordered.map { it.name }.toSet()
    val fallback = DEFAULT_WORKSPACE_DIR_NAME.takeIf { it in names } ?: ordered.firstOrNull()?.name
    val byFolder = sessions.groupBy { session ->
        projectFolderOf(session.workspacePath, root)?.takeIf { it in names } ?: fallback
    }
    return ordered.mapIndexed { index, dir ->
        val host = hostByFolder[dir.name]
        val order = host?.sessionIds.orEmpty()
        WorkspaceFolder(
            id = "ws_${dir.name.hashCode()}",
            name = dir.name,
            path = dir.absolutePath,
            sessions = byFolder[dir.name].orEmpty()
                .sortedBy { s -> order.indexOf(s.sessionId).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { s ->
                    SessionDisplayItem(
                        sessionId = s.sessionId,
                        title = s.title,
                        timeAgo = timeAgo(s.updatedAt),
                        isActiveDot = s.sessionId == selectedSessionId,
                        isRunning = s.sessionId in runningIds,
                        hasUnread = s.sessionId in unreadIds,
                        updatedAt = s.updatedAt,
                        archived = s.archived,
                        pinned = s.pinned,
                    )
                },
            isExpanded = index == 0,
        )
    }
}

/** Проект Host для папки на диске (по имени папки). */
internal fun List<HostWorkspaceInfo>.forFolder(folderPath: String, root: File): HostWorkspaceInfo? {
    val name = projectFolderOf(folderPath, root) ?: return null
    return firstOrNull { projectFolderOf(it.path, root) == name }
}

/** «Только что», «5 мин», «3 ч», «2 дн.» — сколько прошло с [updatedAt]. */
internal fun timeAgoLabel(context: android.content.Context, updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - updatedAt).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> context.getString(R.string.time_just_now)
        elapsed < 3_600_000L -> context.getString(R.string.time_minutes, (elapsed / 60_000L).toInt())
        elapsed < 86_400_000L -> context.getString(R.string.time_hours, (elapsed / 3_600_000L).toInt())
        else -> context.getString(R.string.time_days, (elapsed / 86_400_000L).toInt())
    }
}
