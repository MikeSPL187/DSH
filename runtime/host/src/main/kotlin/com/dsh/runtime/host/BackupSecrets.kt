package com.dsh.runtime.host

import java.io.File
import java.io.OutputStream

/**
 * Очистка содержимого резервной копии «без API-ключей» (DSH-13).
 *
 * Файлы с секретами по имени ([RuntimeBackupAndDiagnostics]) в копию не идут вовсе, но ключ бывает и внутри
 * обычного файла: `"apiKey": "sk-…"` в config.json, `DEEPSEEK_API_KEY=…` в заметке, токен в истории сессии.
 * Здесь вычищаются только узнаваемые секреты: значения полей с именем секрета и ключи известного вида.
 * Общий [SecretScrubber] для копии слишком широк — он стёр бы `"sessionId"` и сломал историю сессий.
 * Файл без секретов копируется байт в байт; двоичные файлы не трогаются.
 */
internal object BackupSecrets {
    /** Имя поля-секрета целиком: apiKey, openai_api_key, accessToken, token, clientSecret, password, privateKey… */
    private const val SECRET_FIELD =
        "(?:[A-Za-z0-9_\\-]*(?:api[_\\-]?key|secret|password|passwd|private[_\\-]?key)" +
            "|(?:access|refresh|auth|bearer|id|session|api)?[_\\-]?token|authorization|x-api-key)"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // "apiKey": "…" — значение строкой; числа ("maxTokens": 5) и чужие поля ("sessionId") не трогаются.
        Regex("""(?i)("$SECRET_FIELD"\s*:\s*)"(?:[^"\\]|\\.)*"""") to "$1\"\"",
        // DEEPSEEK_API_KEY=…, GITHUB_TOKEN: … — строки окружения и конфигураций.
        Regex("""\b([A-Z][A-Z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD))(\s*[=:]\s*)["']?[^\s"',;]+["']?""") to "$1$2",
        Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-.~+/=]{8,}""") to "Bearer ",
        Regex("""\bsk-[A-Za-z0-9_\-]{8,}""") to "sk-REDACTED",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""") to "gh_REDACTED",
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""") to "github_pat_REDACTED",
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}""") to "AIza_REDACTED",
    )

    fun scrubLine(line: String): String = RULES.fold(line) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /** Похож ли файл на текст: в первых 8 КБ нет нулевых байтов. */
    private fun looksText(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            val n = input.read(buf)
            (0 until maxOf(n, 0)).none { buf[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    /** Есть ли в текстовом файле что вычищать (построчно, без чтения файла в память целиком). */
    private fun hasSecrets(file: File): Boolean =
        file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.any { scrubLine(it) != it } }

    /**
     * Записать [file] в [out]: текст с секретами — построчно очищенным (переводы строк — `\n`),
     * остальное — как есть.
     */
    fun copyScrubbed(file: File, out: OutputStream) {
        if (!looksText(file) || !hasSecrets(file)) {
            file.inputStream().use { it.copyTo(out) }
            return
        }
        val endsWithNewline = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.length() > 0 && raf.also { it.seek(it.length() - 1) }.read() == '\n'.code
        }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var first = true
            lines.forEach { line ->
                if (!first) writer.write("\n")
                writer.write(scrubLine(line))
                first = false
            }
        }
        if (endsWithNewline) writer.write("\n")
        writer.flush()
    }
}
