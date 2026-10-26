package com.deepseek.dsh

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что о проектах должен знать Host: регистрация, переименование и снятие папки. */
interface WorkspaceHost {
    suspend fun create(path: String)
    suspend fun rename(workspaceId: String, title: String)
    suspend fun delete(workspaceId: String)
    suspend fun renameSession(sessionId: String, title: String)
}

/**
 * Операции над папками проектов внутри корня [root] (DSH-Workspace): создать,
 * переименовать, удалить — на диске и у Host. Работа с диском — в фоне.
 * Удаляется и переименовывается только то, что лежит строго внутри корня:
 * путь может прийти от Host, симлинки не раскрываются.
 *
 * @param hostIdOf id проекта у Host по пути папки (null — Host его не знает).
 */
class WorkspaceOps(
    private val root: File,
    private val host: WorkspaceHost,
    private val hostIdOf: (path: String) -> String?,
) {
    /** Папка проекта по пути или по имени в корне. */
    fun folderOf(name: String, path: String): File = if (path.isNotBlank()) File(path) else File(root, name)

    /** Создать папку и зарегистрировать её у Host. Возвращает имя созданного проекта или null. */
    suspend fun create(rawName: String): String? {
        val name = cleanName(rawName) ?: return null
        val dir = File(root, name)
        val ok = withContext(Dispatchers.IO) { inside(dir) && (dir.isDirectory || dir.mkdirs()) }
        if (!ok) return null
        runCatching { host.create(dir.absolutePath) }
        return name
    }

    /** Переименовать папку проекта; Host получает новое название. Возвращает новое имя или null. */
    suspend fun rename(name: String, path: String, rawNewName: String): String? {
        val newName = cleanName(rawNewName) ?: return null
        val target = folderOf(name, path)
        val renamed = File(target.parentFile, newName)
        if (renamed.absolutePath == target.absolutePath) return null
        val ok = withContext(Dispatchers.IO) {
            inside(target) && inside(renamed) && target.isDirectory && !renamed.exists() && target.renameTo(renamed)
        }
        if (!ok) return null
        hostIdOf(path.ifBlank { target.absolutePath })?.let { id -> runCatching { host.rename(id, newName) } }
        return newName
    }

    /** Снять проект у Host и удалить папку. true — папка удалена. */
    suspend fun delete(name: String, path: String): Boolean {
        val target = folderOf(name, path)
        hostIdOf(path.ifBlank { target.absolutePath })?.let { id -> runCatching { host.delete(id) } }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!inside(target)) return@runCatching false
                com.deepseek.dsh.runtime.host.SafeFiles.deleteTree(target)
                !target.exists()
            }.getOrDefault(false)
        }
    }

    suspend fun renameSession(sessionId: String, oldTitle: String, rawTitle: String): Boolean {
        val title = rawTitle.trim()
        if (title.isEmpty() || title == oldTitle) return false
        return runCatching { host.renameSession(sessionId, title) }.isSuccess
    }

    /** Строго внутри корня (не сам корень), по каноническим путям. */
    internal fun inside(file: File): Boolean = runCatching {
        val r = root.canonicalFile.toPath()
        val f = file.canonicalFile.toPath()
        f != r && f.startsWith(r)
    }.getOrDefault(false)

    /**
     * Имя папки проекта: без разделителей пути, `.`/`..` и управляющих символов. Двоеточие запрещено:
     * папка монтируется в Linux как `-b путь:/workspace`, и proot принял бы его за разделитель.
     */
    private fun cleanName(raw: String): String? =
        raw.trim().takeIf { name ->
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
        }
}
