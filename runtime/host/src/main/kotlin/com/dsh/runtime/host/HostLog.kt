package com.dsh.runtime.host

import java.io.File

/**
 * Журнал одного запуска Host (`logs/host_<время>.log`, G-09). Каждая строка проходит [SecretScrubber] до записи на
 * диск (раньше чистился только буфер в памяти, а в файл шли ключи и токены из вывода Host), строка — не длиннее
 * [MAX_LINE] символов, файл — не больше [maxBytes]: дальше вывод не сохраняется, о чём пишется одна строка.
 */
class HostLog(private val file: File, private val maxBytes: Long = MAX_BYTES) {
    private var written = if (file.isFile) file.length() else 0L
    private var capped = false

    @Synchronized
    fun append(prefix: String, line: String) {
        if (capped) return
        runCatching {
            val bytes = "$prefix ${SecretScrubber.scrub(line.take(MAX_LINE))}\n".toByteArray()
            if (written + bytes.size > maxBytes) {
                file.appendBytes("[LOG] size limit reached, further output is not saved\n".toByteArray())
                capped = true
                return
            }
            file.appendBytes(bytes)
            written += bytes.size
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        const val MAX_LINE = 8 * 1024

        /** Сколько журналов запусков хранить, включая новый. */
        const val KEEP_LOGS = 5

        /** Сколько архивов диагностики хранить, включая новый. */
        const val KEEP_DIAGNOSTICS = 2

        /**
         * Удалить старые файлы `<prefix>*<suffix>` в [dir], оставив [keep] − 1 самых новых — место для того,
         * что сейчас будет создано. Раньше журналы копились на каждый запуск без предела (гигабайты за месяцы).
         */
        fun rotate(dir: File, prefix: String, suffix: String, keep: Int) {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(suffix) }.orEmpty()
            files.sortedByDescending { it.lastModified() }.drop((keep - 1).coerceAtLeast(0)).forEach { it.delete() }
        }
    }
}
