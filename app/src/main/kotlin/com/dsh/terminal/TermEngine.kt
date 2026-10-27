package com.dsh.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Цвет отрезка вывода — классы `.blk-o .d/.g/.r/.y/.b` прототипа. */
enum class Tone { Plain, Dim, Ok, Err, Warn, Pr }

/**
 * Вывод одной команды: строки с цветом каждого символа.
 * Понимает то, что пишут обычные утилиты: перевод строки, возврат каретки
 * (полосы прогресса apt и gradle), стирание строки, сдвиги курсора, цвета SGR.
 * Полноэкранные программы (vim, top) показываются как есть, без раскладки экрана.
 */
class TermLines(private val maxLines: Int = 3000) {
    companion object {
        /**
         * Длиннее строка переносится. Без предела одна строка без перевода (`cat` минифицированного
         * JS) или курсор `ESC [ 2000000000 G` из вывода раздували строку до гигабайт — OutOfMemoryError.
         */
        const val MAX_COLS = 1024
    }

    private val text = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private val tones = ArrayList<ArrayList<Tone>>().apply { add(ArrayList()) }
    private var row = 0
    private var col = 0
    var tone = Tone.Plain
    private var dim = false

    val lines: List<Pair<String, List<Tone>>>
        get() = text.indices.map { text[it].toString() to tones[it] }

    fun isBlank(): Boolean = text.all { it.isBlank() }

    val lineCount: Int get() = text.size

    /** Последние [n] строк — без пересборки всего вывода на каждый кусок (H-3). */
    fun tail(n: Int): List<Pair<String, List<Tone>>> =
        (maxOf(0, text.size - n) until text.size).map { text[it].toString() to tones[it] }

    /** Последняя непустая строка: проход с конца, а не копия всех строк. */
    fun lastNonBlank(): String? = text.lastOrNull { it.isNotBlank() }?.toString()

    fun plain(): String = text.joinToString("\n") { it.toString().trimEnd() }.trimEnd('\n', ' ')

    fun clear() {
        text.clear(); tones.clear(); text.add(StringBuilder()); tones.add(ArrayList()); row = 0; col = 0
    }

    fun put(ch: Char) {
        if (col >= MAX_COLS) { lf() }
        val line = text[row]
        val tl = tones[row]
        while (line.length < col) { line.append(' '); tl.add(Tone.Plain) }
        val t = if (dim && tone == Tone.Plain) Tone.Dim else tone
        if (col < line.length) { line.setCharAt(col, ch); tl[col] = t } else { line.append(ch); tl.add(t) }
        col++
    }

    fun cr() { col = 0 }

    fun lf() {
        row++
        if (row == text.size) { text.add(StringBuilder()); tones.add(ArrayList()) }
        col = 0
        while (text.size > maxLines) { text.removeAt(0); tones.removeAt(0); row-- }
    }

    fun backspace() { if (col > 0) col-- }

    fun tab() { col = minOf((col / 8 + 1) * 8, MAX_COLS) }

    /** `CSI n K`: 0 — до конца строки, 1 — от начала до курсора, 2 — всю строку. */
    fun eraseLine(mode: Int) {
        val line = text[row]
        val tl = tones[row]
        when (mode) {
            0 -> if (col < line.length) { line.setLength(col); while (tl.size > col) tl.removeAt(tl.lastIndex) }
            1 -> for (i in 0 until minOf(col + 1, line.length)) { line.setCharAt(i, ' '); tl[i] = Tone.Plain }
            else -> { line.setLength(0); tl.clear() }
        }
    }

    /** `CSI n J`: 2 и 3 очищают всё (команда `clear`), 0 — от курсора до конца. */
    fun eraseDisplay(mode: Int) {
        if (mode >= 2) { clear(); return }
        if (mode == 0) {
            eraseLine(0)
            while (text.size > row + 1) { text.removeAt(text.lastIndex); tones.removeAt(tones.lastIndex) }
        }
    }

    fun moveRow(d: Int) { row = (row + d).coerceIn(0, text.lastIndex) }
    fun moveCol(d: Int) { col = (col.toLong() + d).coerceIn(0, MAX_COLS.toLong()).toInt() }
    fun setCol(c: Int) { col = c.coerceIn(0, MAX_COLS) }

    fun sgr(params: List<Int>) {
        var i = 0
        val ps = params.ifEmpty { listOf(0) }
        while (i < ps.size) {
            when (val p = ps[i]) {
                0 -> { tone = Tone.Plain; dim = false }
                2 -> dim = true
                22 -> dim = false
                30, 90 -> tone = Tone.Dim
                31, 91 -> tone = Tone.Err
                32, 92 -> tone = Tone.Ok
                33, 93 -> tone = Tone.Warn
                34, 94, 35, 95, 36, 96 -> tone = Tone.Pr
                37, 97, 39 -> tone = Tone.Plain
                38 -> { i += if (ps.getOrNull(i + 1) == 5) 2 else 4; tone = Tone.Plain }
                48 -> i += if (ps.getOrNull(i + 1) == 5) 2 else 4
                else -> if (p in 40..49 || p in 100..107) Unit
            }
            i++
        }
    }
}

/** Состояние блока команды: `.blk.live / .ok / .err`; [Plain] — оболочка без меток, код неизвестен. */
enum class BlockState { Live, Ok, Err, Plain }

/** Объяснение ошибки моделью (`.explain`). */
@Stable
class Explain {
    var text by mutableStateOf("")
    var fix by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Один блок `.blk`: команда, её вывод, код выхода и время. */
@Stable
class TermBlock(val id: Long, val cmd: String, val startedAt: Long) {
    val out = TermLines()
    var state by mutableStateOf(BlockState.Live)
    var code by mutableStateOf<Int?>(null)
    var durationMs by mutableLongStateOf(0L)
    var folded by mutableStateOf(false)
    var explain by mutableStateOf<Explain?>(null)
    /** Растёт при каждом новом выводе — по нему перерисовывается текст. */
    var version by mutableIntStateOf(0)
        internal set
    /** Последний процент в выводе (`.prog`), если программа его пишет. */
    var progress by mutableStateOf<Int?>(null)
        internal set
}

/**
 * Модель одной вкладки терминала: превращает поток PTY в блоки команд.
 *
 * Метки конца команды ставит bash: один раз при открытии вкладки приложение
 * тихо задаёт `PROMPT_COMMAND`, который после каждой команды печатает
 * `ESC ] 133 ; D ; <код> ; <папка> BEL` (как shell integration в других терминалах).
 * Пока меток нет, всё, что печатает оболочка вне команды (приглашение, эхо ввода), не показывается.
 */
@Stable
class TermModel(val id: String, private val clock: () -> Long = System::currentTimeMillis) {
    val blocks = mutableStateListOf<TermBlock>()
    var cwd by mutableStateOf<String?>(null)
    /** Метки работают: есть код выхода и время. */
    var integrated by mutableStateOf(false)
        private set
    var setupSent = false
        private set
    private var discarding = false
    private var setupAt = 0L
    private var echoPending = false
    private var pending = ""
    private var nextId = 1L

    val live: TermBlock? get() = blocks.lastOrNull()?.takeIf { it.state == BlockState.Live }

    /** Строка настройки, которую приложение отправляет один раз (с пробелом в начале — мимо истории). */
    fun setupLine(): String {
        setupSent = true
        discarding = true
        setupAt = clock()
        return SETUP
    }

    /** Если метка после настройки так и не пришла (не bash) — работаем без неё. */
    fun checkSetupTimeout(now: Long = clock()) {
        if (discarding && !integrated && now - setupAt > SETUP_TIMEOUT_MS) discarding = false
    }

    /** Пользователь отправил команду: новый блок, эхо команды от оболочки пропускается. */
    fun submit(cmd: String): TermBlock {
        live?.let { finish(it, null) }
        val b = TermBlock(nextId++, cmd, clock())
        if (!integrated) b.state = BlockState.Plain
        blocks.add(b)
        // H-3: блоки вкладки не копятся без предела (каждый — до 3000 строк); самые старые уходят.
        if (blocks.size > MAX_BLOCKS) blocks.removeRange(0, blocks.size - MAX_BLOCKS)
        echoPending = true
        return b
    }

    /** `clear`: блоки исчезают, а сама команда и её вывод не показываются. */
    fun clearLocal(sentToShell: Boolean) {
        blocks.clear()
        if (sentToShell && integrated) discarding = true
    }

    /** Куда идёт вывод: живой блок (с метками) или последний блок (без меток). */
    private fun sink(): TermBlock? = when {
        discarding || echoPending -> null
        integrated -> live
        else -> blocks.lastOrNull()
    }

    fun feed(data: String) {
        val s = pending + data
        pending = ""
        var i = 0
        var touched: TermBlock? = null
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\u001b') {
                val end = escapeEnd(s, i)
                if (end < 0) {
                    if (s.length - i <= MAX_PENDING) { pending = s.substring(i); break }
                    // Незакрытая последовательность длиннее любой настоящей (`cat` бинарного файла, `printf '\e]'`):
                    // раньше весь дальнейший вывод копился в ней (тишина, рост памяти, квадратичное копирование).
                    i++
                    continue
                }
                val b = sink()
                handleEscape(s.substring(i, end), b?.out)
                if (b != null) touched = b
                i = end
                continue
            }
            if (echoPending && !discarding) {
                if (ch == '\n') echoPending = false
                i++
                continue
            }
            val b = sink()
            if (b != null) {
                val out = b.out
                when {
                    ch == '\r' -> out.cr()
                    ch == '\n' -> out.lf()
                    ch == '\b' -> out.backspace()
                    ch == '\t' -> out.tab()
                    ch.code < 0x20 -> Unit
                    else -> out.put(ch)
                }
                touched = b
            }
            i++
        }
        touched?.let { b ->
            b.version++
            b.progress = if (b.state == BlockState.Live) lastPercent(b.out) else null
        }
    }

    /** Новая оболочка на месте закрытой: блоки остаются, метки настраиваются заново. */
    fun reset() {
        closed()
        setupSent = false; integrated = false; discarding = false; echoPending = false; pending = ""
    }

    /** Оболочка закрылась: живой блок завершается без кода. */
    fun closed() { live?.let { finish(it, null) } }

    private fun finish(b: TermBlock, code: Int?) {
        b.durationMs = clock() - b.startedAt
        b.code = code
        b.progress = null
        b.state = when {
            code == null -> BlockState.Plain
            code == 0 -> BlockState.Ok
            else -> BlockState.Err
        }
        if (b.state == BlockState.Ok && b.out.isBlank()) b.folded = false
    }

    private fun handleEscape(seq: String, out: TermLines?) {
        if (seq.startsWith("\u001b]")) {
            val body = seq.removePrefix("\u001b]").removeSuffix("\u0007").removeSuffix("\u001b\\")
            if (body.startsWith("133;D")) {
                val parts = body.split(';', limit = 5)
                val code = parts.getOrNull(2)?.toIntOrNull()
                val home = parts.getOrNull(4).orEmpty()
                parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { pwd ->
                    cwd = if (home.length > 1 && (pwd == home || pwd.startsWith("$home/"))) "~" + pwd.removePrefix(home) else pwd
                }
                integrated = true
                if (discarding) { discarding = false; echoPending = false; return }
                live?.let { finish(it, code) }
                echoPending = false
            }
            return
        }
        if (out == null || !seq.startsWith("\u001b[")) return
        val final = seq.last()
        val raw = seq.substring(2, seq.length - 1)
        if (raw.startsWith("?") || raw.startsWith(">")) return
        val ps = raw.split(';').map { it.toIntOrNull() ?: 0 }.let { if (raw.isEmpty()) emptyList() else it }
        val n = ps.firstOrNull()?.takeIf { it > 0 } ?: 1
        when (final) {
            'm' -> out.sgr(ps)
            'K' -> out.eraseLine(ps.firstOrNull() ?: 0)
            'J' -> out.eraseDisplay(ps.firstOrNull() ?: 0)
            'A' -> out.moveRow(-n)
            'B' -> out.moveRow(n)
            'C' -> out.moveCol(n)
            'D' -> out.moveCol(-n)
            'G' -> out.setCol(n - 1)
            else -> Unit
        }
    }

    companion object {
        const val SETUP_TIMEOUT_MS = 2_500L

        /** Самая длинная незаконченная escape-последовательность, которую ждём из следующего куска вывода. */
        internal const val MAX_PENDING = 8 * 1024
        /** Сколько блоков (команд) хранит вкладка. */
        internal const val MAX_BLOCKS = 200
        /** Пока команда идёт, рисуется хвост вывода: весь блок пересобирается только по её завершении. */
        const val LIVE_TAIL_LINES = 400
        const val SETUP = " [ -n \"\$BASH_VERSION\" ] && [ -z \"\$DSH_BLK\" ] && DSH_BLK=1 && " +
            "PROMPT_COMMAND='printf \"\\033]133;D;%s;%s;%s\\007\" \"\$?\" \"\$PWD\" \"\$HOME\"'" +
            "\"\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\"\r"

        /** Конец escape-последовательности, начатой в [i], или −1, если она не дошла целиком. */
        internal fun escapeEnd(s: String, i: Int): Int {
            if (i + 1 >= s.length) return -1
            return when (s[i + 1]) {
                '[' -> {
                    var j = i + 2
                    while (j < s.length && s[j].code in 0x20..0x3f) j++
                    if (j < s.length) j + 1 else -1
                }
                ']' -> {
                    var j = i + 2
                    while (j < s.length) {
                        if (s[j] == '\u0007') return j + 1
                        if (s[j] == '\u001b') return if (j + 1 < s.length) j + 2 else -1
                        j++
                    }
                    -1
                }
                '(', ')', '#' -> if (i + 2 < s.length) i + 3 else -1
                else -> i + 2
            }
        }

        private val PERCENT = Regex("""(\d{1,3})\s?%""")

        internal fun lastPercent(out: TermLines): Int? {
            val last = out.lastNonBlank() ?: return null
            return PERCENT.findAll(last).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
        }
    }
}

/**
 * Ввод для оболочки без управляющих символов: вставленный текст с `ESC`, `^C` и т. п. уходил в PTY как есть.
 * Переводы строк (`\r\n`, `\r`) сводятся к `\n` и остаются — их показывает [TermPaste.lines].
 */
object TermPaste {
    fun clean(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
        .filter { it == '\n' || it == '\t' || !(it.isISOControl() || it.code in 0x80..0x9f) }
        .trimEnd('\n')

    /**
     * Во вставке несколько строк: поле однострочное и видна только первая, а оболочка выполнит все
     * (подмена команды при копировании со страницы сайта). Такой ввод выполняется только после подтверждения.
     */
    fun needsConfirm(text: String): Boolean = '\n' in clean(text)

    fun lines(text: String): List<String> = clean(text).split('\n')
}
