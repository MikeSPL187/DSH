package com.dsh

import java.io.File

/** Прежнее имя папки проекта по умолчанию (из китайской версии клиента). */
internal const val LEGACY_DEFAULT_WORKSPACE_DIR_NAME = "默认工作区"

/** Папка сессий Host внутри filesDir. */
internal const val HOST_SESSIONS_DIR = "rootfs/root/.dsh/sessions"

/**
 * Переименовать папку проекта по умолчанию со старого имени на [DEFAULT_WORKSPACE_DIR_NAME]
 * вместе с папкой её сессий в Host, чтобы история осталась при папке.
 *
 * Ничего не теряется: если новая папка уже есть, старая остаётся как обычный проект;
 * если переименовать не удалось (нет доступа к файлам), попытка повторится при следующем запуске.
 * Повторный вызов безопасен.
 *
 * @param workspaceRoot корень рабочих папок (`DSH-Workspace`).
 * @param sessionsRoot папка сессий Host (`rootfs/root/.dsh/sessions`); может не существовать.
 * @return перенесена ли папка проекта.
 */
internal fun migrateDefaultWorkspace(workspaceRoot: File, sessionsRoot: File): Boolean {
    val legacy = File(workspaceRoot, LEGACY_DEFAULT_WORKSPACE_DIR_NAME)
    val target = File(workspaceRoot, DEFAULT_WORKSPACE_DIR_NAME)
    if (!legacy.isDirectory || target.exists() || !legacy.renameTo(target)) return false
    moveSessions(
        from = File(sessionsRoot, workspaceSessionDirName(LEGACY_DEFAULT_WORKSPACE_DIR_NAME)),
        to = File(sessionsRoot, workspaceSessionDirName(DEFAULT_WORKSPACE_DIR_NAME)),
    )
    return true
}

/** Перенести сессии папки; сессии, которые уже есть на новом месте, не перезаписываются. */
private fun moveSessions(from: File, to: File) {
    if (!from.isDirectory) return
    if (!to.exists()) {
        from.renameTo(to)
        return
    }
    from.listFiles()?.forEach { session ->
        val dest = File(to, session.name)
        if (!dest.exists()) session.renameTo(dest)
    }
    from.delete()
}
