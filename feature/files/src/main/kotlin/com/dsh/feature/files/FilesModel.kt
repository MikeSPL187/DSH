package com.dsh.feature.files

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

/**
 * Обход проекта (без служебных папок), не больше [limit] файлов и [maxDirs] папок. В ссылки на папки не заходит
 * (G-30): цикл `ln -s .. up` в папке без файлов раньше давал бесконечный обход — «Недавние» и поиск висли.
 */
internal fun walkFiles(root: File, limit: Int = 4000, maxDirs: Int = 20_000): List<File> {
    val out = ArrayList<File>()
    val stack = ArrayDeque<File>().apply { add(root) }
    var dirs = 0
    while (stack.isNotEmpty() && out.size < limit && dirs++ < maxDirs) {
        val dir = stack.removeLast()
        val kids = dir.listFiles() ?: continue
        for (f in kids) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
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

/** Текст файла для просмотра: строки и признак «показано не всё». */
internal data class ViewText(val lines: List<String>, val truncated: Boolean)

/**
 * Начало текстового файла для просмотра с пределами (G-01): не больше [maxLines] строк, строка длиннее
 * [maxLineChars] обрезается с «…», всего не больше [maxChars] символов и не дальше [maxRead] прочитанных.
 * Читается посимвольно: минифицированный `bundle.min.js` (мегабайты одной строкой) не попадает в память
 * целиком, а ширина текста остаётся в пределах, которые Compose может измерить.
 */
internal fun readViewText(
    file: File,
    maxLines: Int = MAX_VIEW_LINES,
    maxLineChars: Int = MAX_VIEW_LINE_CHARS,
    maxChars: Int = MAX_VIEW_CHARS,
    maxRead: Long = MAX_VIEW_READ,
): ViewText {
    val lines = ArrayList<String>()
    val line = StringBuilder()
    var cut = false
    var kept = 0
    var read = 0L
    fun finish() {
        lines += if (cut) line.append('…').toString() else line.toString()
        line.setLength(0)
        cut = false
    }
    file.bufferedReader().use { reader ->
        val buf = CharArray(8192)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buf[i]
                if (lines.size >= maxLines || kept >= maxChars || read >= maxRead) {
                    if (line.isNotEmpty() || cut) finish()
                    return ViewText(lines, truncated = true)
                }
                read++
                when {
                    ch == '\n' -> finish()
                    ch == '\r' -> Unit
                    line.length < maxLineChars -> { line.append(ch); kept++ }
                    else -> cut = true
                }
            }
        }
    }
    if (line.isNotEmpty() || cut) finish()
    return ViewText(lines, truncated = false)
}

internal const val MAX_VIEW_LINES = 3000
internal const val MAX_VIEW_LINE_CHARS = 2000
internal const val MAX_VIEW_CHARS = 256 * 1024
internal const val MAX_VIEW_READ = 8L * 1024 * 1024

/** Длинная сторона превью картинки: экран телефона, с запасом на xxxhdpi. */
internal const val PREVIEW_MAX_SIDE = 2048

/** Степень двойки, при которой длинная сторона картинки не больше [maxSide]; размер неизвестен — 1. */
internal fun previewSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    if (longest <= 0) return 1
    while (longest / sample > maxSide) sample *= 2
    return sample
}
