package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git рабочей папки для экрана «Файлы»: тот же `git` из Linux, которым пользуется агент.
 *
 * Команды выполняются через proot с привязкой корня рабочих папок к `/workspace`,
 * как у Host. Проект — подпапка корня; Host сам делает в ней `git init`.
 *
 * @param layout каталоги среды выполнения.
 * @param workspaceRoot корень рабочих папок на устройстве (привязывается к `/workspace`).
 */
class WorkspaceGit(private val layout: RuntimeLayout, private val workspaceRoot: File) {

    /** Одно изменённое место: путь от корня проекта, вид и сколько строк добавлено/удалено. */
    data class Change(val path: String, val kind: Kind, val added: Int, val deleted: Int, val binary: Boolean = false)

    enum class Kind { MODIFIED, ADDED, DELETED, RENAMED }

    /** Ветка и незакоммиченные изменения. */
    data class Status(val branch: String, val changes: List<Change>)

    /** Строка диффа: заголовок блока или строка с номером. */
    sealed interface DiffLine {
        data class Hunk(val text: String) : DiffLine
        /** [sign] — `' '`, `'+'` или `'-'`; [number] — номер строки (для `-` — в старой версии). */
        data class Line(val sign: Char, val number: Int, val text: String) : DiffLine
    }

    /** Доступен ли git: Linux установлен. */
    fun available(): Boolean = layout.prootBinary.isFile && File(layout.rootfsDir, "usr/bin/git").exists()

    /** Ветка и изменения проекта [project]; null — git недоступен или это не репозиторий. */
    suspend fun status(project: String): Status? = withContext(Dispatchers.IO) {
        if (!available()) return@withContext null
        val porcelain = git(project, "status", "--porcelain=v1", "-z", "-uall") ?: return@withContext null
        val branch = git(project, "symbolic-ref", "--short", "HEAD")?.trim()?.ifEmpty { null }
            ?: git(project, "rev-parse", "--short", "HEAD")?.trim().orEmpty()
        val hasHead = git(project, "rev-parse", "--verify", "-q", "HEAD") != null
        val numstat = if (hasHead) parseNumstat(git(project, "diff", "--no-ext-diff", "--no-textconv", "--numstat", "-z", "HEAD").orEmpty()) else emptyMap()
        val projectDir = File(workspaceRoot, project)
        val changes = parsePorcelain(porcelain).map { (path, kind) ->
            val stat = numstat[path]
            when {
                stat != null -> Change(path, kind, stat.first, stat.second, binary = stat.first < 0)
                kind == Kind.ADDED -> Change(path, kind, countLines(File(projectDir, path)), 0)
                else -> Change(path, kind, 0, 0)
            }
        }
        Status(branch, changes)
    }

    /** Дифф файла [path] против последнего коммита; новый файл — все строки добавлены. */
    suspend fun diff(project: String, path: String): List<DiffLine> = withContext(Dispatchers.IO) {
        val hasHead = git(project, "rev-parse", "--verify", "-q", "HEAD") != null
        val tracked = hasHead && git(project, "ls-files", "--error-unmatch", "--", path) != null
        if (!tracked) {
            val file = File(File(workspaceRoot, project), path)
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).take(MAX_LINES)
            return@withContext listOf(DiffLine.Hunk("@@ -0,0 +1,${lines.size} @@")) +
                lines.mapIndexed { i, t -> DiffLine.Line('+', i + 1, t) }
        }
        parseUnified(git(project, "diff", "--no-ext-diff", "--no-textconv", "--no-color", "-U3", "HEAD", "--", path).orEmpty())
    }

    /** Отменить изменение файла: вернуть версию из коммита, новый файл — удалить. */
    suspend fun revert(project: String, change: Change): Boolean = withContext(Dispatchers.IO) {
        if (change.kind == Kind.ADDED) {
            git(project, "rm", "-q", "--cached", "--ignore-unmatch", "--", change.path)
            File(File(workspaceRoot, project), change.path).delete()
        } else {
            git(project, "checkout", "HEAD", "--", change.path) != null
        }
    }

    /** Автор коммитов (`user.name`, `user.email` проекта или общий); null — не задан. */
    suspend fun identity(project: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val name = git(project, "config", "user.name")?.trim().orEmpty()
        val email = git(project, "config", "user.email")?.trim().orEmpty()
        if (name.isEmpty() || email.isEmpty()) null else name to email
    }

    /** Запомнить автора для всех проектов (`git config --global` в Linux DSH). */
    suspend fun setIdentity(project: String, name: String, email: String): Boolean = withContext(Dispatchers.IO) {
        git(project, "config", "--global", "user.name", name) != null && git(project, "config", "--global", "user.email", email) != null
    }

    /**
     * Закоммитить все изменения проекта (`git add -A`, затем `commit -m`); хуки и подпись выключены [HARDENING].
     * Сообщение уходит отдельным аргументом, без оболочки. Успех — короткий хэш нового коммита.
     */
    suspend fun commit(project: String, message: String): Result<String> = withContext(Dispatchers.IO) {
        if (!available() || !repoConfigSafe(File(workspaceRoot, project))) return@withContext Result.failure(IllegalStateException("git"))
        val base = listOf(GIT) + HARDENING + listOf("-c", "core.quotepath=off", "-C", "/workspace/$project")
        val add = run(base + listOf("add", "-A"))
        if (add.first != 0) return@withContext Result.failure(IllegalStateException(lastLine(add.second, "git add")))
        val done = run(base + listOf("commit", "--no-verify", "-m", message), timeoutSec = 60)
        if (done.first != 0) return@withContext Result.failure(IllegalStateException(lastLine(done.second, "git commit")))
        Result.success(git(project, "rev-parse", "--short", "HEAD")?.trim().orEmpty())
    }

    /** Коммиты, которых нет ни на одном удалённом репозитории, и тема последнего; null — git недоступен. */
    suspend fun unpushed(project: String): Unpushed? = withContext(Dispatchers.IO) {
        if (!available() || git(project, "rev-parse", "--verify", "-q", "HEAD") == null) return@withContext null
        val count = git(project, "rev-list", "--count", "HEAD", "--not", "--remotes")?.trim()?.toIntOrNull() ?: return@withContext null
        Unpushed(count, git(project, "log", "-1", "--format=%s")?.trim().orEmpty())
    }

    /** Неотправленные коммиты: сколько и тема последнего коммита. */
    data class Unpushed(val count: Int, val lastSubject: String)

    private fun lastLine(out: String, fallback: String) = out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: fallback

    /** `git clone` в новую папку [dir] внутри проекта. */
    suspend fun clone(project: String, url: String, dir: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!available()) return@withContext Result.failure(IllegalStateException("git"))
        val out = run(listOf(GIT) + HARDENING + listOf("-C", "/workspace/$project", "clone", "--depth", "50", "--", url, dir), timeoutSec = 600)
        if (out.first != 0) return@withContext Result.failure(IllegalStateException(out.second.lines().lastOrNull { it.isNotBlank() } ?: "git clone"))
        // Чужой код в проекте: новые чаты — «Только чтение», пока пользователь не выберет другие права (G-50).
        ClonedProjects.add(layout, project)
        Result.success(Unit)
    }

    private fun git(project: String, vararg args: String): String? {
        if (!repoConfigSafe(File(workspaceRoot, project))) return null
        val (code, out) = run(listOf(GIT) + HARDENING + listOf("-c", "core.quotepath=off", "-C", "/workspace/$project") + args)
        return if (code == 0) out else null
    }

    private fun run(argv: List<String>, timeoutSec: Long = 30): Pair<Int, String> = runCatching {
        val exec = ProotCommandBuilder.buildExec(layout, argv, workspaceRoot.absolutePath)
        val process = ProcessBuilder(exec.command).apply {
            environment().putAll(exec.environment)
            redirectErrorStream(true)
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching -1 to out
        }
        process.exitValue() to out
    }.getOrDefault(-1 to "")

    private fun countLines(file: File): Int = runCatching {
        if (!file.isFile || file.length() > 2_000_000) 0 else file.bufferedReader().useLines { it.count() }
    }.getOrDefault(0)

    companion object {
        private const val GIT = "/usr/bin/git"

        /**
         * Настройки поверх конфига репозитория. `safe.directory=*` нужен: в proot владелец папок —
         * uid приложения, и git иначе отказывается работать. Но тогда git доверяет `.git/config` и хукам
         * проекта, а их мог положить кто угодно (архив проекта, общая папка «Документы»). Всё, чем git
         * сам запускает программы при status/diff/checkout/clone, здесь выключено.
         */
        internal val HARDENING = listOf(
            "-c", "safe.directory=*",
            "-c", "core.fsmonitor=false",
            "-c", "core.hooksPath=/dev/null",
            "-c", "core.pager=cat",
            "-c", "core.editor=true",
            "-c", "core.sshCommand=ssh",
            "-c", "commit.gpgSign=false",
            "-c", "protocol.ext.allow=never",
        )

        /** Секции конфига, которые запускают программы и не выключаются флагом: фильтры и включение других файлов. */
        private val UNSAFE_SECTION = Regex("""(?im)^\s*\[\s*(filter|include|includeif)\b""")

        /**
         * Можно ли запускать git в [dir]: в его репозитории (ближайшая `.git` вверх по папкам) нет
         * фильтров и `include`. `.git`-файл (worktree, submodule) указывает на конфиг в другом месте — отказ.
         */
        internal fun repoConfigSafe(dir: File): Boolean {
            var current: File? = dir.absoluteFile
            while (current != null) {
                val dotGit = File(current, ".git")
                if (dotGit.isFile) return false
                if (dotGit.isDirectory) {
                    val configs = listOf(File(dotGit, "config"), File(dotGit, "config.worktree"))
                    return configs.none { it.isFile && UNSAFE_SECTION.containsMatchIn(runCatching { it.readText() }.getOrDefault("[include]")) }
                }
                current = current.parentFile
            }
            return true
        }
        private const val MAX_LINES = 2_000

        /** Разбор `status --porcelain=v1 -z`: путь и вид изменения. */
        fun parsePorcelain(raw: String): List<Pair<String, Kind>> {
            val parts = raw.split('\u0000')
            val out = mutableListOf<Pair<String, Kind>>()
            var i = 0
            while (i < parts.size) {
                val entry = parts[i]
                if (entry.length < 4) { i++; continue }
                val x = entry[0]
                val y = entry[1]
                val path = entry.substring(3)
                val kind = when {
                    x == '?' || x == 'A' -> Kind.ADDED
                    x == 'D' || y == 'D' -> Kind.DELETED
                    x == 'R' -> Kind.RENAMED
                    else -> Kind.MODIFIED
                }
                out += path to kind
                // У переименования следом идёт старый путь.
                i += if (x == 'R' || x == 'C') 2 else 1
            }
            return out
        }

        /** Разбор `diff --numstat -z`: путь → (добавлено, удалено); двоичные — (-1, -1). */
        fun parseNumstat(raw: String): Map<String, Pair<Int, Int>> {
            val parts = raw.split('\u0000')
            val out = mutableMapOf<String, Pair<Int, Int>>()
            var i = 0
            while (i < parts.size) {
                val cols = parts[i].split('\t')
                if (cols.size < 3) { i++; continue }
                val added = cols[0].toIntOrNull() ?: -1
                val deleted = cols[1].toIntOrNull() ?: -1
                if (cols[2].isEmpty() && i + 2 < parts.size) {
                    // Переименование: «a\td\t\0старый\0новый\0».
                    out[parts[i + 2]] = added to deleted
                    i += 3
                } else {
                    out[cols[2]] = added to deleted
                    i++
                }
            }
            return out
        }

        /** Разбор единого диффа в строки с номерами. */
        fun parseUnified(raw: String): List<DiffLine> {
            val out = mutableListOf<DiffLine>()
            var old = 0
            var new = 0
            var inHunk = false
            val header = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
            for (line in raw.lines()) {
                val m = header.matchEntire(line)
                when {
                    m != null -> {
                        old = m.groupValues[1].toInt()
                        new = m.groupValues[2].toInt()
                        inHunk = true
                        out += DiffLine.Hunk(line)
                    }
                    !inHunk -> Unit
                    line.startsWith("+") -> out += DiffLine.Line('+', new++, line.substring(1))
                    line.startsWith("-") -> out += DiffLine.Line('-', old++, line.substring(1))
                    line.startsWith(" ") -> { out += DiffLine.Line(' ', new, line.substring(1)); old++; new++ }
                    line.startsWith("\\") -> Unit
                }
            }
            return out.take(MAX_LINES)
        }
    }
}
