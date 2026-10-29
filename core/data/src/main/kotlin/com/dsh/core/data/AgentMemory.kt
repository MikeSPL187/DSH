package com.dsh.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Факт в памяти агента: [fromChat] — сохранён из чата, иначе добавлен вручную;
 * [project] — абсолютный путь папки проекта (память проекта), null — для всех проектов.
 */
@Serializable
data class MemoryFact(val id: Long, val text: String, val addedAt: Long, val fromChat: Boolean = false, val project: String? = null)

/**
 * @param auto «Сохранять без вопроса»: предложения агента сохраняются сами (в чате — «Отменить»), но только
 *   из ответов после [autoSince] — старые сессии при открытии память не пополняют.
 * @param handled id сообщений, чьи предложения уже решены (сохранены, отменены или «Не надо»): карточка не всплывает снова.
 */
@Serializable
data class MemoryState(
    val on: Boolean = true,
    val facts: List<MemoryFact> = emptyList(),
    val auto: Boolean = false,
    val autoSince: Long = 0,
    val handled: List<String> = emptyList(),
)

/** Почему факт не сохраняется. */
enum class MemoryIssue { EMPTY, TOO_LONG, SECRET, DUPLICATE }

/** Факт, который агент предложил запомнить; [project] — для текущего проекта (`scope="project"`). */
data class MemoryProposal(val text: String, val project: Boolean)

/**
 * Память агента между сессиями. Источник истины — [MemoryState] в личных файлах приложения. Агенту факты
 * выводятся блоками между [BEGIN] и [END]: общие — в `~/.agents/AGENTS.md` Linux, факты проекта — в
 * `AGENTS.local.md` его папки (вне git: файл вносится в `.git/info/exclude`). Оба файла `dsh-agent-instructions`
 * читает в начале сессии; чужой текст в них сохраняется.
 *
 * Агент сам в файлы не пишет: он предлагает факт строкой `<dsh-memory>…</dsh-memory>` ([proposals]), чат
 * показывает «Запомнить?» или, при [MemoryState.auto], сохраняет сам с «Отменить». Ключи, токены и пароли
 * в память не попадают ([issue]): файлы читает каждая сессия и любая модель.
 */
object AgentMemory {
    const val MAX_CHARS = 500
    const val MAX_FACTS = 200
    const val BEGIN = "<!-- dsh-memory:begin -->"
    const val END = "<!-- dsh-memory:end -->"
    const val PROJECT_FILE = "AGENTS.local.md"
    private const val TAG_OPEN = "<dsh-memory"
    private const val TAG_CLOSE = "</dsh-memory>"
    private const val MAX_HANDLED = 500

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    /** Текущая рабочая папка (проект): к ней относятся «Этот проект» и предложения `scope="project"`. */
    private val _project = MutableStateFlow<String?>(null)
    val project: StateFlow<String?> = _project.asStateFlow()

    private var stateFile: File? = null
    private var agentsMd: File? = null
    private val lock = Any()

    /** Прочитать сохранённое и вывести блоки для агента. [agentsFile] — `~/.agents/AGENTS.md` внутри rootfs. */
    fun init(file: File, agentsFile: File) = synchronized(lock) {
        stateFile = file
        agentsMd = agentsFile
        _state.value = runCatching { json.decodeFromString(MemoryState.serializer(), file.readText()) }.getOrDefault(MemoryState())
        sync()
    }

    /** Сменилась рабочая папка: её блок выводится заново (папку могли создать или очистить). */
    fun setProject(dir: File?) {
        _project.value = dir?.absolutePath
        dir?.let { synchronized(lock) { runCatching { writeProject(it, _state.value) } } }
    }

    /** Заново вывести все блоки (после переустановки Linux файлов может не быть). */
    fun sync(): Boolean = synchronized(lock) {
        val s = _state.value
        val f = agentsMd ?: return false
        val ok = runCatching { writeBlock(f, render(s)) }.isSuccess
        projectsOf(s).forEach { runCatching { writeProject(File(it), s) } }
        ok
    }

    fun setOn(on: Boolean) = update { it.copy(on = on) }

    fun setAuto(auto: Boolean, now: Long = System.currentTimeMillis()) = update { it.copy(auto = auto, autoSince = if (auto) now else 0) }

    /** Добавить факт ([project] — путь папки или null); null — сохранён, иначе причина отказа. */
    fun add(text: String, fromChat: Boolean, project: String? = null, now: Long = System.currentTimeMillis()): MemoryIssue? {
        val t = normalize(text)
        var why: MemoryIssue? = null
        update { s ->
            why = issue(t, s.facts, project = project) ?: if (s.facts.size >= MAX_FACTS) MemoryIssue.TOO_LONG else null
            if (why != null) s else s.copy(facts = s.facts + MemoryFact((s.facts.maxOfOrNull { it.id } ?: 0L) + 1, t, now, fromChat, project))
        }
        return why
    }

    /** Изменить текст и где помнить. */
    fun edit(id: Long, text: String, project: String?): MemoryIssue? {
        val t = normalize(text)
        var why: MemoryIssue? = null
        update { s ->
            why = issue(t, s.facts, except = id, project = project)
            if (why != null) s else s.copy(facts = s.facts.map { if (it.id == id) it.copy(text = t, project = project) else it })
        }
        return why
    }

    fun remove(id: Long) = update { s -> s.copy(facts = s.facts.filterNot { it.id == id }) }

    /** Убрать факт по тексту (кнопка «Отменить» под ответом). */
    fun forget(text: String, project: String?) = normalize(text).let { t ->
        update { s -> s.copy(facts = s.facts.filterNot { it.project == project && it.text.equals(t, ignoreCase = true) }) }
    }

    fun clear() = update { it.copy(facts = emptyList()) }

    /** Предложения сообщения [messageId] решены: карточка не всплывает и не сохраняется сама снова. */
    fun markHandled(messageId: String) = update { s ->
        if (messageId in s.handled) s else s.copy(handled = (s.handled + messageId).takeLast(MAX_HANDLED))
    }

    fun contains(text: String, project: String? = null): Boolean =
        normalize(text).let { t -> _state.value.facts.any { it.project == project && it.text.equals(t, ignoreCase = true) } }

    private fun update(change: (MemoryState) -> MemoryState) = synchronized(lock) {
        val before = _state.value
        val next = change(before)
        if (next == before) return@synchronized
        _state.value = next
        stateFile?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, ".${f.name}.tmp")
                tmp.writeText(json.encodeToString(MemoryState.serializer(), next))
                if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
            }
        }
        // Блоки, которые могли измениться: общий и проекты до и после (факт переехал или удалён последний).
        if (before.on != next.on || before.facts != next.facts) {
            agentsMd?.let { runCatching { writeBlock(it, render(next)) } }
            (projectsOf(before) + projectsOf(next)).forEach { runCatching { writeProject(File(it), next) } }
        }
    }

    private fun projectsOf(s: MemoryState): Set<String> = s.facts.mapNotNullTo(LinkedHashSet()) { it.project }

    // ── Чистые функции (тесты) ──

    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private val SECRET = listOf(
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{20,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""\bgithub_pat_"""),
        Regex("""\bglpat-[A-Za-z0-9_\-]{12,}"""),
        Regex("""\bxox[abposr]-"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\beyJ[A-Za-z0-9_\-]{16,}\."""),
        Regex("""\bya29\."""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY"""),
        Regex("""(?iu)(?<![\p{L}])(?:парол\p{L}*|password|passwd|pwd|token|токен\p{L}*|secret|секрет\p{L}*|api[_-]?key|ключ\p{L}* api)[^\n:=—]{0,24}[:=—]\s{0,3}\S{4,}"""),
    )

    /** Похоже на ключ, токен или пароль — в память такое не сохраняется. */
    fun looksSecret(text: String): Boolean = SECRET.any { it.containsMatchIn(text) }

    /** Причина отказа; повтор считается только среди фактов той же области ([project]). */
    fun issue(text: String, facts: List<MemoryFact>, except: Long? = null, project: String? = null): MemoryIssue? {
        val t = normalize(text)
        return when {
            t.isEmpty() -> MemoryIssue.EMPTY
            t.length > MAX_CHARS -> MemoryIssue.TOO_LONG
            looksSecret(t) -> MemoryIssue.SECRET
            facts.any { it.id != except && it.project == project && it.text.equals(t, ignoreCase = true) } -> MemoryIssue.DUPLICATE
            else -> null
        }
    }

    private val PROPOSAL = Regex("""<dsh-memory(?:\s+scope\s*=\s*["']?(project|global|all)["']?)?\s*>([\s\S]*?)</dsh-memory>""")

    /** Факты, которые агент предложил запомнить в [text], без пустых, секретов и повторов. */
    fun proposals(text: String): List<MemoryProposal> {
        if (!text.contains(TAG_OPEN)) return emptyList()
        return PROPOSAL.findAll(text)
            .map { MemoryProposal(normalize(it.groupValues[2]).take(MAX_CHARS), it.groupValues[1] == "project") }
            .filter { it.text.isNotEmpty() && !looksSecret(it.text) }
            .distinctBy { it.text.lowercase() to it.project }.toList()
    }

    /**
     * [text] без предложений памяти — для показа, копирования и чтения вслух. Незакрытый тег в конце
     * (ответ ещё идёт) срезается до конца, чтобы разметка не мелькала.
     */
    fun strip(text: String): String {
        if (!text.contains('<')) return text
        var t = text.replace(Regex("""[ \t]*<dsh-memory[^>]*>[\s\S]*?</dsh-memory>[ \t]*\n?"""), "")
        val open = t.lastIndexOf("<dsh-mem")
        if (open >= 0 && !t.substring(open).contains(TAG_CLOSE)) t = t.substring(0, open)
        // Хвост, который может оказаться началом тега: «<», «<dsh», …
        val tail = t.takeLast(TAG_OPEN.length)
        for (k in tail.length downTo 1) {
            if (TAG_OPEN.startsWith(tail.takeLast(k))) { t = t.dropLast(k); break }
        }
        return t.trimEnd()
    }

    /** Общий блок для `~/.agents/AGENTS.md`: факты для всех проектов и правило, как предлагать новые. Пусто — память выключена. */
    fun render(state: MemoryState): String {
        if (!state.on) return ""
        val global = state.facts.filter { it.project == null }
        val facts = if (global.isEmpty()) "_(no facts yet)_" else global.joinToString("\n") { "- " + it.text }
        return """
            |$BEGIN
            |# User memory
            |
            |Facts the user asked you to remember across sessions and projects (managed in the DSH app, Settings → Memory; edits to this block are overwritten). Treat them as the user's standing preferences and context. If the current request conflicts with a fact, follow the request. Facts for the current project, if any, are in the project's AGENTS.local.md.
            |
            |$facts
            |
            |## Proposing new facts
            |You cannot write memory files. When the user asks you to remember something for future sessions, or states a lasting preference worth keeping, end your reply with one line per fact:
            |<dsh-memory>short fact, in the user's language</dsh-memory>
            |For a fact that only applies to the current project (its commands, conventions, people, deadlines), use <dsh-memory scope="project">…</dsh-memory>.
            |The app shows it to the user, who decides. Propose only durable facts, not task details. Never include API keys, tokens, passwords or other secrets. Do not repeat facts already in memory.
            |$END
        """.trimMargin()
    }

    /** Блок проекта [project] для его `AGENTS.local.md`; пусто — фактов проекта нет или память выключена. */
    fun renderProject(state: MemoryState, project: String): String {
        val facts = state.facts.filter { it.project == project }
        if (!state.on || facts.isEmpty()) return ""
        return """
            |$BEGIN
            |# Project memory
            |
            |Facts the user asked you to remember for this project (managed in the DSH app; edits to this block are overwritten). Follow them in this project unless the current request says otherwise.
            |
            |${facts.joinToString("\n") { "- " + it.text }}
            |$END
        """.trimMargin()
    }

    /** [existing] с заменённым (или добавленным, или убранным) блоком памяти. null — файл не нужен вовсе. */
    fun merge(existing: String?, block: String): String? {
        val outside = withoutBlock(existing).trim()
        val parts = listOf(outside, block).filter { it.isNotBlank() }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n") + "\n"
    }

    /** Текст файла без блока памяти — для редактора инструкций (блок ведёт приложение). */
    fun withoutBlock(text: String?): String {
        val e = text ?: return ""
        val b = e.indexOf(BEGIN)
        val en = e.indexOf(END)
        return if (b >= 0 && en > b) (e.substring(0, b).trimEnd() + "\n" + e.substring(en + END.length).trimStart()).trim().let { if (it.isEmpty()) "" else it + "\n" } else e
    }

    /** Блок памяти из [text] (вместе с маркерами) или пусто — чтобы редактор инструкций сохранил его как был. */
    fun blockOf(text: String?): String {
        val e = text ?: return ""
        val b = e.indexOf(BEGIN)
        val en = e.indexOf(END)
        return if (b >= 0 && en > b) e.substring(b, en + END.length) else ""
    }

    private fun writeProject(dir: File, state: MemoryState) {
        if (!dir.isDirectory) return
        val block = renderProject(state, dir.absolutePath)
        val file = File(dir, PROJECT_FILE)
        if (block.isEmpty() && !file.isFile) return
        writeBlock(file, block)
        if (block.isNotEmpty()) excludeFromGit(dir)
    }

    /** `AGENTS.local.md` — личный файл: в репозитории проекта он вносится в `.git/info/exclude`. */
    private fun excludeFromGit(dir: File) {
        val git = File(dir, ".git")
        if (!git.isDirectory) return
        val exclude = File(git, "info/exclude")
        val lines = exclude.takeIf { it.isFile }?.readLines().orEmpty()
        if (lines.any { it.trim() == PROJECT_FILE || it.trim() == "/$PROJECT_FILE" }) return
        exclude.parentFile?.mkdirs()
        exclude.appendText((if (lines.isNotEmpty() && exclude.readText().endsWith("\n").not()) "\n" else "") + "/$PROJECT_FILE\n")
    }

    private fun writeBlock(file: File, block: String) {
        val existing = file.takeIf { it.isFile }?.readText()
        // Блока не было и не нужно — файл не трогаем (даже пробелы в чужом тексте).
        if (block.isEmpty() && existing?.contains(BEGIN) != true) return
        val next = merge(existing, block)
        if (next == existing) return
        if (next == null) { file.delete(); return }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.dsh-tmp")
        tmp.writeText(next)
        if (!tmp.renameTo(file)) { file.writeText(next); tmp.delete() }
    }
}
