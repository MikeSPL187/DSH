package com.dsh.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Факт в памяти агента: [fromChat] — сохранён из карточки «Запомнить?» в чате, иначе добавлен вручную. */
@Serializable
data class MemoryFact(val id: Long, val text: String, val addedAt: Long, val fromChat: Boolean = false)

@Serializable
data class MemoryState(val on: Boolean = true, val facts: List<MemoryFact> = emptyList())

/** Почему факт не сохраняется. */
enum class MemoryIssue { EMPTY, TOO_LONG, SECRET, DUPLICATE }

/**
 * Память агента между сессиями. Источник истины — [MemoryState] в личных файлах приложения; для агента
 * факты выводятся блоком в `~/.agents/AGENTS.md` в Linux — этот файл `dsh-agent-instructions` читает
 * в начале каждой сессии (вместе с `~/.dsh/AGENTS.md`). Чужой текст в этом файле сохраняется, правится
 * только блок между [BEGIN] и [END].
 *
 * Агент сам в файл не пишет: он предлагает факт строкой `<dsh-memory>…</dsh-memory>` в ответе
 * ([proposals]), чат показывает карточку «Запомнить?», сохраняет пользователь. Ключи, токены и пароли
 * в память не попадают ([issue]): файл читает каждая сессия и любая модель.
 */
object AgentMemory {
    const val MAX_CHARS = 500
    const val MAX_FACTS = 200
    const val BEGIN = "<!-- dsh-memory:begin -->"
    const val END = "<!-- dsh-memory:end -->"
    private const val TAG_OPEN = "<dsh-memory>"
    private const val TAG_CLOSE = "</dsh-memory>"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    private var stateFile: File? = null
    private var agentsMd: File? = null
    private val lock = Any()

    /** Прочитать сохранённое и вывести блок для агента. [agentsFile] — `~/.agents/AGENTS.md` внутри rootfs. */
    fun init(file: File, agentsFile: File) = synchronized(lock) {
        stateFile = file
        agentsMd = agentsFile
        _state.value = runCatching { json.decodeFromString(MemoryState.serializer(), file.readText()) }.getOrDefault(MemoryState())
        sync()
    }

    /** Заново вывести блок в `AGENTS.md` (после переустановки Linux файла может не быть). */
    fun sync(): Boolean = synchronized(lock) {
        val f = agentsMd ?: return false
        runCatching { writeBlock(f, _state.value) }.isSuccess
    }

    fun setOn(on: Boolean) = update { it.copy(on = on) }

    /** Добавить факт; null — сохранён, иначе причина отказа. */
    fun add(text: String, fromChat: Boolean, now: Long = System.currentTimeMillis()): MemoryIssue? {
        val t = normalize(text)
        var why: MemoryIssue? = null
        update { s ->
            why = issue(t, s.facts) ?: if (s.facts.size >= MAX_FACTS) MemoryIssue.TOO_LONG else null
            if (why != null) s else s.copy(facts = s.facts + MemoryFact((s.facts.maxOfOrNull { it.id } ?: 0L) + 1, t, now, fromChat))
        }
        return why
    }

    fun edit(id: Long, text: String): MemoryIssue? {
        val t = normalize(text)
        var why: MemoryIssue? = null
        update { s ->
            why = issue(t, s.facts, except = id)
            if (why != null) s else s.copy(facts = s.facts.map { if (it.id == id) it.copy(text = t) else it })
        }
        return why
    }

    fun remove(id: Long) = update { s -> s.copy(facts = s.facts.filterNot { it.id == id }) }

    fun clear() = update { it.copy(facts = emptyList()) }

    /** Уже в памяти (для карточки «В памяти»). */
    fun contains(text: String): Boolean = normalize(text).lowercase().let { t -> _state.value.facts.any { it.text.lowercase() == t } }

    private fun update(change: (MemoryState) -> MemoryState) = synchronized(lock) {
        val next = change(_state.value)
        if (next == _state.value) return@synchronized
        _state.value = next
        stateFile?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, ".${f.name}.tmp")
                tmp.writeText(json.encodeToString(MemoryState.serializer(), next))
                if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
            }
        }
        sync()
    }

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

    fun issue(text: String, facts: List<MemoryFact>, except: Long? = null): MemoryIssue? {
        val t = normalize(text)
        return when {
            t.isEmpty() -> MemoryIssue.EMPTY
            t.length > MAX_CHARS -> MemoryIssue.TOO_LONG
            looksSecret(t) -> MemoryIssue.SECRET
            facts.any { it.id != except && it.text.equals(t, ignoreCase = true) } -> MemoryIssue.DUPLICATE
            else -> null
        }
    }

    /** Факты, которые агент предложил запомнить в [text] (`<dsh-memory>…</dsh-memory>`), без пустых и повторов. */
    fun proposals(text: String): List<String> {
        if (!text.contains(TAG_OPEN)) return emptyList()
        return Regex("""<dsh-memory>([\s\S]*?)</dsh-memory>""").findAll(text)
            .map { normalize(it.groupValues[1]).take(MAX_CHARS) }.filter { it.isNotEmpty() }.distinct().toList()
    }

    /**
     * [text] без предложений памяти — для показа, копирования и чтения вслух. Незакрытый тег в конце
     * (ответ ещё идёт) срезается до конца, чтобы разметка не мелькала.
     */
    fun strip(text: String): String {
        if (!text.contains('<')) return text
        var t = text.replace(Regex("""[ \t]*<dsh-memory>[\s\S]*?</dsh-memory>[ \t]*\n?"""), "")
        val open = t.lastIndexOf("<dsh-mem")
        if (open >= 0 && !t.substring(open).contains(TAG_CLOSE)) t = t.substring(0, open)
        // Хвост, который может оказаться началом тега: «<», «<dsh», …
        val tail = t.takeLast(TAG_OPEN.length)
        for (k in tail.length downTo 1) {
            if (TAG_OPEN.startsWith(tail.takeLast(k))) { t = t.dropLast(k); break }
        }
        return t.trimEnd()
    }

    /** Блок для `AGENTS.md`: факты и правило, как предлагать новые. Пусто — память выключена. */
    fun render(state: MemoryState): String {
        if (!state.on) return ""
        val facts = if (state.facts.isEmpty()) "_(no facts yet)_" else state.facts.joinToString("\n") { "- " + it.text }
        return """
            |$BEGIN
            |# User memory
            |
            |Facts the user asked you to remember across sessions (managed in the DSH app, Settings → Memory; edits to this block are overwritten). Treat them as the user's standing preferences and context. If the current request conflicts with a fact, follow the request.
            |
            |$facts
            |
            |## Proposing new facts
            |You cannot write this file. When the user asks you to remember something for future sessions, or states a lasting preference worth keeping, end your reply with one line per fact:
            |$TAG_OPEN short fact, in the user's language $TAG_CLOSE
            |The app shows it as a "Remember?" card and the user decides. Propose only durable facts, not task details. Never include API keys, tokens, passwords or other secrets. Do not repeat facts already listed above.
            |$END
        """.trimMargin()
    }

    /** [existing] с заменённым (или добавленным, или убранным) блоком памяти. null — файл не нужен вовсе. */
    fun merge(existing: String?, block: String): String? {
        val outside = existing?.let { e ->
            val b = e.indexOf(BEGIN)
            val en = e.indexOf(END)
            if (b >= 0 && en > b) (e.substring(0, b) + e.substring(en + END.length)).trim() else e.trim()
        }.orEmpty()
        val parts = listOf(outside, block).filter { it.isNotBlank() }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n") + "\n"
    }

    private fun writeBlock(file: File, state: MemoryState) {
        val existing = file.takeIf { it.isFile }?.readText()
        val next = merge(existing, render(state))
        if (next == existing) return
        if (next == null) { file.delete(); return }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.dsh-tmp")
        tmp.writeText(next)
        if (!tmp.renameTo(file)) { file.writeText(next); tmp.delete() }
    }
}
