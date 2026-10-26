package com.deepseek.dsh.feature.files

import java.io.File

/** Вид изменения файла в git. */
enum class ChangeKind { MODIFIED, ADDED, DELETED, RENAMED }

/** Незакоммиченное изменение: путь от корня проекта и сколько строк добавлено/удалено. */
data class GitChange(val path: String, val kind: ChangeKind, val added: Int, val deleted: Int)

/** Ветка и незакоммиченные изменения проекта. */
data class GitInfo(val branch: String, val changes: List<GitChange>)

/** Строка диффа: заголовок блока или строка с номером и знаком (`' '`, `'+'`, `'-'`). */
sealed interface DiffRow {
    data class Hunk(val text: String) : DiffRow
    data class Line(val sign: Char, val number: Int, val text: String) : DiffRow
}

/**
 * Git проекта для экрана «Файлы». Реализация — в приложении (git внутри Linux).
 * Все пути — от корня проекта, через `/`.
 */
interface FilesGit {
    /** null — git ещё недоступен (Linux не установлен) или папка не репозиторий. */
    suspend fun status(): GitInfo?
    suspend fun diff(path: String): List<DiffRow>
    suspend fun revert(change: GitChange): Boolean
    suspend fun clone(url: String, intoDir: String): Result<Unit>
}

/** Путь [file] от корня [root] через `/`; пустая строка — сам корень. */
internal fun relPath(root: File, file: File): String =
    file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\').replace('\\', '/')

/** Папки, которые не показываем в недавних и в поиске: служебные и сборочные. */
internal val SkipDirs = setOf(".git", "build", "node_modules", ".gradle", ".idea", ".cache", "__pycache__", ".venv")

/** Обход проекта (без служебных папок), не больше [limit] файлов. */
internal fun walkFiles(root: File, limit: Int = 4000): List<File> {
    val out = ArrayList<File>()
    val stack = ArrayDeque<File>().apply { add(root) }
    while (stack.isNotEmpty() && out.size < limit) {
        val dir = stack.removeLast()
        val kids = dir.listFiles() ?: continue
        for (f in kids) {
            if (f.isDirectory) {
                if (f.name !in SkipDirs) stack.add(f)
            } else {
                out.add(f)
                if (out.size >= limit) break
            }
        }
    }
    return out
}

/** Содержимое папки: сначала папки, потом файлы, скрытые — в конце; по имени без учёта регистра. */
internal fun listDir(dir: File): List<File> =
    (dir.listFiles()?.toList() ?: emptyList())
        .filter { it.name != ".git" }
        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.startsWith(".") }, { it.name.lowercase() }))

/** Похоже ли содержимое на текст: нет нулевых байтов в начале файла. */
internal fun looksText(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        val n = input.read(buf)
        (0 until maxOf(n, 0)).none { buf[it] == 0.toByte() }
    }
}.getOrDefault(false)

internal val ImageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
