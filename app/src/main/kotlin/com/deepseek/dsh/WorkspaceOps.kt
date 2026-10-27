package com.deepseek.dsh

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что о проектах должен знать Host: регистрация, переименование и снятие папки. true — Host согласился. */
interface WorkspaceHost {
    suspend fun create(path: String): Boolean
    suspend fun rename(workspaceId: String, title: String): Boolean
    suspend fun delete(workspaceId: String): Boolean
    suspend fun renameSession(sessionId: String, title: String): Boolean
}

/** Почему операция с проектом не выполнена — для понятного сообщения (G-28). */
enum class WorkspaceProblem { INVALID_NAME, NAME_TOO_LONG, NAME_TAKEN, OUTSIDE_ROOT, DEFAULT_PROTECTED, SESSION_RUNNING, DISK, HOST_REJECTED }

class WorkspaceOpException(val problem: WorkspaceProblem) : Exception(problem.name)

/**
 * Операции над папками проектов внутри корня [root] (DSH-Workspace): создать,
 * переименовать, удалить — на диске и у Host. Работа с диском — в фоне.
 * Удаляется и переименовывается только то, что лежит строго внутри корня:
 * путь может прийти от Host, симлинки не раскрываются. Все проверки — до вызова Host;
 * отказ Host или диска — [WorkspaceOpException] с причиной, а не молчание.
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

    /** Создать папку и зарегистрировать её у Host. Возвращает имя созданного проекта. */
    suspend fun create(rawName: String): Result<String> = runCatching {
        val name = cleanName(rawName)
        val dir = File(root, name)
        val ok = withContext(Dispatchers.IO) { inside(dir) && (dir.isDirectory || dir.mkdirs()) }
        if (!ok) fail(WorkspaceProblem.DISK)
        if (!runCatching { host.create(dir.absolutePath) }.getOrDefault(false)) fail(WorkspaceProblem.HOST_REJECTED)
        name
    }

    /** Переименовать папку проекта; Host получает новое название. Возвращает новое имя. */
    suspend fun rename(name: String, path: String, rawNewName: String): Result<String> = runCatching {
        val newName = cleanName(rawNewName)
        val target = folderOf(name, path)
        val renamed = File(target.parentFile, newName)
        if (renamed.absolutePath == target.absolutePath) fail(WorkspaceProblem.NAME_TAKEN)
        if (name == DEFAULT_WORKSPACE_DIR_NAME) fail(WorkspaceProblem.DEFAULT_PROTECTED)
        withContext(Dispatchers.IO) {
            if (!inside(target) || !inside(renamed)) fail(WorkspaceProblem.OUTSIDE_ROOT)
            if (renamed.exists()) fail(WorkspaceProblem.NAME_TAKEN)
            if (!target.isDirectory || !target.renameTo(renamed)) fail(WorkspaceProblem.DISK)
        }
        val id = hostIdOf(path.ifBlank { target.absolutePath })
        if (id != null && !runCatching { host.rename(id, newName) }.getOrDefault(false)) {
            // Host не принял новое имя — вернуть папку, иначе Host и диск разойдутся.
            withContext(Dispatchers.IO) { renamed.renameTo(target) }
            fail(WorkspaceProblem.HOST_REJECTED)
        }
        newName
    }

    /**
     * Удалить проект (G-12): «Default» и проект, где сейчас работает агент ([busy]), не удаляются; путь проверяется
     * до вызова Host; Host должен снять проект — иначе папка остаётся (у Host не будет ссылки на удалённое).
     */
    suspend fun delete(name: String, path: String, busy: Boolean = false): Result<Unit> = runCatching {
        if (name == DEFAULT_WORKSPACE_DIR_NAME) fail(WorkspaceProblem.DEFAULT_PROTECTED)
        if (busy) fail(WorkspaceProblem.SESSION_RUNNING)
        val target = folderOf(name, path)
        if (!withContext(Dispatchers.IO) { inside(target) }) fail(WorkspaceProblem.OUTSIDE_ROOT)
        val id = hostIdOf(path.ifBlank { target.absolutePath })
        if (id != null && !runCatching { host.delete(id) }.getOrDefault(false)) fail(WorkspaceProblem.HOST_REJECTED)
        val removed = withContext(Dispatchers.IO) {
            com.deepseek.dsh.runtime.host.SafeFiles.deleteTree(target)
            !target.exists()
        }
        if (!removed) fail(WorkspaceProblem.DISK)
    }

    /** Переименовать сессию; false — то же имя или пустое (ничего не делать), отказ Host — исключение. */
    suspend fun renameSession(sessionId: String, oldTitle: String, rawTitle: String): Result<Boolean> = runCatching {
        val title = rawTitle.trim()
        if (title.isEmpty() || title == oldTitle) return@runCatching false
        // Раньше `runCatching { … }.isSuccess` при Boolean-результате считал отказ Host успехом (G-27).
        if (!runCatching { host.renameSession(sessionId, title) }.getOrDefault(false)) fail(WorkspaceProblem.HOST_REJECTED)
        true
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
     * Длиннее 255 байт файловая система не примет.
     */
    private fun cleanName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            fail(WorkspaceProblem.INVALID_NAME)
        }
        if (name.toByteArray(Charsets.UTF_8).size > 255) fail(WorkspaceProblem.NAME_TOO_LONG)
        return name
    }

    private fun fail(problem: WorkspaceProblem): Nothing = throw WorkspaceOpException(problem)
}
