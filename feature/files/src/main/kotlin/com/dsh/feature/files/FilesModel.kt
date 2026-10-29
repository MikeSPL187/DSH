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

    /** Автор коммитов (имя, почта); null — не задан, лист коммита спросит. */
    suspend fun identity(): Pair<String, String>? = null
    suspend fun setIdentity(name: String, email: String): Boolean = false

    /** Закоммитить все изменения; успех — короткий хэш, ошибка — последняя строка git. */
    suspend fun commit(message: String): Result<String> = Result.failure(UnsupportedOperationException())

    /** Неотправленные коммиты (число, тема последнего); null — не узнать. */
    suspend fun unpushed(): Pair<Int, String>? = null

    /** Куда уйдёт отправка («origin/main»); null — отправить некуда (не GitHub по https, нет ветки). */
    suspend fun pushTarget(): String? = null

    /** Отправить текущую ветку; успех — куда ушло, ошибка — [PushException]. */
    suspend fun push(): Result<String> = Result.failure(PushException(PushProblem.OTHER))

    /** Ветка, база и запрос на слияние из неё; null — проект не на GitHub или ветку не узнать. */
    suspend fun branchState(): BranchState? = null

    /** Перенести неотправленные коммиты в новую ветку [name] и перейти на неё; файлы не меняются. */
    suspend fun moveToBranch(name: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Сообщения коммитов ветки, которых нет в базе (новые первыми), — для названия и описания запроса. */
    suspend fun branchCommits(): List<String> = emptyList()

    /** Открыть запрос на слияние из текущей ветки в базу; ошибка — [PullException]. */
    suspend fun createPull(title: String, body: String, draft: Boolean): Result<PullInfo> = Result.failure(PullException(PullProblem.OTHER))

    /** История текущей ветки, новые первыми: [limit] коммитов после [skip]; null — git недоступен. */
    suspend fun log(skip: Int, limit: Int): List<CommitInfo>? = null

    /** Файлы коммита против первого родителя; null — не прочитать. */
    suspend fun commitFiles(hash: String): List<CommitFile>? = null

    /** Разница файла [file] в коммите [hash]. */
    suspend fun commitDiff(hash: String, file: CommitFile): List<DiffRow> = emptyList()
}

/**
 * Коммит истории: хэши, автор, время (мс), тема и тело. [pushed] — есть ли на удалённом репозитории;
 * null — удалённых нет (метку «не отправлен» не показываем).
 */
data class CommitInfo(val hash: String, val short: String, val author: String, val time: Long, val subject: String, val body: String, val pushed: Boolean?)

/** Файл коммита: путь, прежний путь при переименовании, вид, строки (-1 — двоичный). */
data class CommitFile(val path: String, val oldPath: String?, val kind: ChangeKind, val added: Int, val deleted: Int)

/** Сколько коммитов показать на «Изменениях» до «Вся история». */
const val HISTORY_SHOW = 5
/** Страница «Всей истории». */
const val HISTORY_PAGE = 50

/** Тело коммита для телефона: строки абзаца, перенесённые по 72 знака, склеиваются; пустые строки и пункты списка остаются. */
internal fun unwrapBody(text: String): String =
    text.replace(Regex("""([^\n])\n(?![\n\-*•]|\d+\.)"""), "$1 ")

/** Запрос на слияние на GitHub. */
data class PullInfo(val number: Int, val url: String, val title: String, val draft: Boolean)

/**
 * Ветка для запроса на слияние.
 *
 * @param base ветка по умолчанию на GitHub — куда сливать.
 * @param onRemote ветка уже есть на GitHub (`origin/<ветка>`).
 * @param pull открытый запрос из этой ветки; null — нет (или не вошли в GitHub и не узнать).
 */
data class BranchState(val branch: String, val base: String, val onRemote: Boolean, val pull: PullInfo?)

/** Почему запрос на слияние не создан — у каждой причины своё объяснение. */
enum class PullProblem { NEED_LOGIN, AUTH, NO_ACCESS, EXISTS, NO_COMMITS, DRAFT_UNSUPPORTED, NOT_GITHUB, OTHER }

/** Ветка с таким именем уже есть. */
class BranchExistsException : Exception("branch exists")

class PullException(val problem: PullProblem, detail: String? = null) : Exception(detail ?: problem.name)

private val TRANSLIT = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
    'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
    'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
    'э' to "e", 'ю' to "yu", 'я' to "ya",
)

/** Имя новой ветки по теме коммита: `dsh/` и тема латиницей через «-», не длиннее 40 символов после префикса. */
fun branchSlug(subject: String): String {
    val latin = subject.lowercase().map { ch -> TRANSLIT[ch] ?: ch.toString() }.joinToString("")
    val slug = latin.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
    return "dsh/" + slug.ifEmpty { "changes" }
}

/** Что не так с именем ветки (правила `git check-ref-format`); null — годится. */
enum class BranchNameProblem { CHARS, FORM, IS_BASE }

fun branchNameProblem(name: String, base: String): BranchNameProblem? = when {
    name.isEmpty() -> null
    !Regex("^[A-Za-z0-9._/-]+$").matches(name) -> BranchNameProblem.CHARS
    name.startsWith("-") || name.startsWith("/") || name.startsWith(".") || name.endsWith("/") || name.endsWith(".") ||
        ".." in name || "//" in name || "/." in name || name.endsWith(".lock") || name.length > 100 -> BranchNameProblem.FORM
    name == base -> BranchNameProblem.IS_BASE
    else -> null
}

/** Почему отправка не удалась — у каждой причины своё объяснение пользователю. */
enum class PushProblem { NEED_LOGIN, NOT_GITHUB, UNSAFE_CONFIG, DETACHED, REJECTED, AUTH, OTHER }

class PushException(val problem: PushProblem, detail: String? = null) : Exception(detail ?: problem.name)

/** Вход в GitHub для отправки из «Изменений»: тот же лист, что в «Настройках». */
interface FilesGitHub {
    fun signedIn(): Boolean
    suspend fun begin(): Result<com.dsh.core.designsystem.kit.DeviceCodeView>
    suspend fun await(): Result<String>
    fun errorOf(t: Throwable): com.dsh.core.designsystem.kit.DeviceSignInError
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

/**
 * Удалить файл или папку целиком, не заходя в символические ссылки: ссылка удаляется как ссылка.
 * `File.deleteRecursively()` идёт по ссылкам на папки — удаление папки проекта с `node_modules` от pnpm
 * или ссылкой на общую память стёрло бы файлы вне рабочей папки.
 * @return true, если пути больше нет.
 */
internal fun deleteTree(file: File): Boolean {
    val root = file.toPath()
    val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
    if (!java.nio.file.Files.exists(root, noFollow)) return true
    return runCatching {
        if (java.nio.file.Files.isDirectory(root, noFollow)) {
            // walkFileTree по умолчанию не ходит по ссылкам: ссылка приходит в visitFile как обычный файл.
            java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                    java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(path) }

                override fun visitFileFailed(path: java.nio.file.Path, exc: java.io.IOException) =
                    java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(path) }

                override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
                    java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(dir) }
            })
        } else {
            java.nio.file.Files.deleteIfExists(root)
        }
        !java.nio.file.Files.exists(root, noFollow)
    }.getOrDefault(false)
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

/** Больше — не правится в приложении (поле ввода на телефоне с таким не справится). */
const val MAX_EDIT_BYTES = 1L shl 20

/**
 * Файл, открытый для правки: текст (переводы строк — `\n`), были ли они `\r\n` и отпечаток (размер и время
 * изменения), по которому видно, что файл изменили извне.
 */
data class EditDoc(val text: String, val crlf: Boolean, val stamp: Pair<Long, Long>)

/** Почему файл нельзя править. */
enum class EditProblem { TOO_BIG, NOT_TEXT, UNREADABLE }

class EditException(val problem: EditProblem) : Exception(problem.name)

internal fun stampOf(file: File): Pair<Long, Long> = file.length() to file.lastModified()

/** Открыть для правки: только текст в строгом UTF-8 до [MAX_EDIT_BYTES] — иначе сохранение испортило бы файл. */
internal fun openForEdit(file: File): Result<EditDoc> = runCatching {
    if (!file.isFile) throw EditException(EditProblem.UNREADABLE)
    if (file.length() > MAX_EDIT_BYTES) throw EditException(EditProblem.TOO_BIG)
    val stamp = stampOf(file)
    val bytes = runCatching { file.readBytes() }.getOrElse { throw EditException(EditProblem.UNREADABLE) }
    val raw = strictText(bytes) ?: throw EditException(EditProblem.NOT_TEXT)
    val crlf = raw.contains("\r\n")
    EditDoc(if (crlf) raw.replace("\r\n", "\n") else raw, crlf, stamp)
}

/** Текст в строгом UTF-8 без нулевых байтов, иначе null (двоичный файл или другая кодировка). */
internal fun strictText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
}

/** Итог сохранения: записано (новый отпечаток) или файл изменили извне после открытия. */
sealed interface SaveResult {
    data class Saved(val stamp: Pair<Long, Long>) : SaveResult
    data object Conflict : SaveResult
}

/**
 * Записать [text] атомарно (временный файл рядом и переименование), с прежними переводами строк и правом на
 * запуск. [expected] — отпечаток при открытии: не совпал и не [force] — [SaveResult.Conflict], файл не тронут.
 */
internal fun saveEdit(file: File, text: String, crlf: Boolean, expected: Pair<Long, Long>?, force: Boolean = false): Result<SaveResult> = runCatching {
    if (!force && expected != null && file.exists() && stampOf(file) != expected) return@runCatching SaveResult.Conflict
    val out = if (crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
    val exec = file.canExecute()
    val tmp = File(file.parentFile, ".${file.name}.dsh-edit-${System.nanoTime()}")
    try {
        java.io.FileOutputStream(tmp).use { it.write(out.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (exec) tmp.setExecutable(true)
        if (!tmp.renameTo(file)) error("cannot replace ${file.name}")
    } finally {
        tmp.delete()
    }
    SaveResult.Saved(stampOf(file))
}
/** Совпадения [query] в [text] без учёта регистра (начала, не пересекаются). */
internal fun findAll(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = text.indexOf(query, ignoreCase = true)
    while (i >= 0) { out += i; i = text.indexOf(query, i + query.length, ignoreCase = true) }
    return out
}

/** Заменить все совпадения [query] на [replacement]; итог и сколько заменено. */
internal fun replaceAll(text: String, query: String, replacement: String): Pair<String, Int> {
    val m = findAll(text, query)
    if (m.isEmpty()) return text to 0
    val sb = StringBuilder()
    var p = 0
    for (i in m) { sb.append(text, p, i).append(replacement); p = i + query.length }
    sb.append(text, p, text.length)
    return sb.toString() to m.size
}

/** Фрагменты диффа: каждый начинается заголовком `@@`. */
internal fun hunksOf(rows: List<DiffRow>): List<List<DiffRow>> {
    val out = ArrayList<MutableList<DiffRow>>()
    for (r in rows) if (r is DiffRow.Hunk) out += mutableListOf<DiffRow>(r) else out.lastOrNull()?.add(r)
    return out
}

/** Начало и число строк фрагмента в новой версии (`+c,d`); нет заголовка — null. */
internal fun hunkNewRange(header: String): Pair<Int, Int>? {
    val m = Regex("""\+(\d+)(?:,(\d+))?""").find(header) ?: return null
    return m.groupValues[1].toInt() to (m.groupValues[2].ifEmpty { "1" }).toInt()
}

/**
 * Текст [text] (переводы строк — `\n`) с отменённым фрагментом [hunk]: его новые строки (контекст и «+»)
 * заменяются старыми (контекст и «−»). null — файл уже не такой, как в диффе (фрагмент не совпал).
 */
internal fun revertHunk(text: String, hunk: List<DiffRow>): String? {
    val header = hunk.firstOrNull() as? DiffRow.Hunk ?: return null
    val (start, count) = hunkNewRange(header.text) ?: return null
    val lines = hunk.filterIsInstance<DiffRow.Line>()
    val newSide = lines.filter { it.sign != '-' }.map { it.text }
    val oldSide = lines.filter { it.sign != '+' }.map { it.text }
    if (newSide.size != count) return null
    val all = text.split('\n').toMutableList()
    // `+c,0` — вставка после строки c; иначе строки c…c+d−1 (с единицы).
    val from = if (count == 0) start else start - 1
    if (from < 0 || from + count > all.size || all.subList(from, from + count) != newSide) return null
    repeat(count) { all.removeAt(from) }
    all.addAll(from, oldSide)
    return all.joinToString("\n")
}

// ── Поиск по тексту во всём проекте ──

/**
 * Строка с найденным текстом: номер (с 1), где совпадение в тексте файла — как в правке (переводы строк `\n`) —
 * и отрезок строки для показа: без отступа, а если совпадение далеко — с «…» за [GREP_LEAD] знаков до него.
 * [start]..[end] — найденное внутри [snippet].
 */
data class TextHit(val line: Int, val offset: Int, val snippet: String, val start: Int, val end: Int)

/** Найденное в одном файле: путь от корня, первые строки (не больше [GREP_KEEP]) и сколько всего. */
data class FileHits(val path: String, val hits: List<TextHit>, val total: Int)

/** Итог поиска: файлы с совпадениями, всего строк и упёрся ли поиск в предел [GREP_MAX_LINES]. */
data class TextSearch(val files: List<FileHits>, val lines: Int, val capped: Boolean)

/** Сколько знаков строки оставить перед совпадением. */
const val GREP_LEAD = 18
/** Сколько строк файла показать до «Ещё N в этом файле» (+1 — чтобы знать, куда ведёт «Ещё»). */
const val GREP_SHOW = 5
const val GREP_KEEP = GREP_SHOW + 1
/** Больше строк не ищем — просим уточнить запрос. */
const val GREP_MAX_LINES = 1000
/** Длиннее строки (сжатый код) обрезаются после совпадения. */
private const val GREP_TAIL = 160

/** Строки [text] с [query] без учёта регистра: первые [keep] и сколько всего (не больше [limit]). */
internal fun grepText(text: String, query: String, keep: Int = GREP_KEEP, limit: Int = Int.MAX_VALUE): Pair<List<TextHit>, Int> {
    if (query.isEmpty()) return emptyList<TextHit>() to 0
    val out = ArrayList<TextHit>()
    var total = 0
    var pos = 0
    var no = 1
    var counted = 0
    while (total < limit && pos <= text.length) {
        val k = text.indexOf(query, pos, ignoreCase = true)
        if (k < 0) break
        for (x in counted until k) if (text[x] == '\n') no++
        counted = k
        val ls = text.lastIndexOf('\n', k - 1) + 1
        val le = text.indexOf('\n', k).let { if (it < 0) text.length else it }
        total++
        if (out.size < keep) {
            val line = text.substring(ls, le)
            val col = k - ls
            val lead = line.length - line.trimStart().length
            val from = maxOf(minOf(lead, col), col - GREP_LEAD)
            val cut = if (from > lead) "…" else ""
            val tailEnd = minOf(line.length, col + query.length + GREP_TAIL)
            val snippet = cut + line.substring(from, tailEnd) + if (tailEnd < line.length) "…" else ""
            val s = cut.length + col - from
            out += TextHit(no, k, snippet, s, s + query.length)
        }
        // Одна строка — одна находка: дальше ищем со следующей.
        pos = le + 1
    }
    return out to total
}

/**
 * Найти [query] в тексте файлов [files] проекта [root]: только то, что откроется в правке (строгий UTF-8 до
 * [MAX_EDIT_BYTES]), не больше [maxLines] строк. [active] = false — поиск устарел (запрос сменился), бросаем.
 */
internal fun searchText(root: File, files: List<File>, query: String, maxLines: Int = GREP_MAX_LINES, active: () -> Boolean = { true }): TextSearch {
    val out = ArrayList<FileHits>()
    var lines = 0
    for (f in files) {
        if (!active() || lines >= maxLines) break
        if (f.length() > MAX_EDIT_BYTES || f.length() < query.length) continue
        val raw = runCatching { f.readBytes() }.getOrNull()?.let(::strictText) ?: continue
        val text = if ('\r' in raw) raw.replace("\r\n", "\n") else raw
        val (hits, total) = grepText(text, query, limit = maxLines - lines)
        if (total == 0) continue
        lines += total
        out += FileHits(relPath(root, f), hits, total)
    }
    return TextSearch(out.sortedBy { it.path.lowercase() }, lines, lines >= maxLines)
}
