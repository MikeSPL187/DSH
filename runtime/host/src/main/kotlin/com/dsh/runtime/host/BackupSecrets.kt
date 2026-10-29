package com.dsh.runtime.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream

/**
 * Очистка содержимого резервной копии «без API-ключей» (DSH-13, R2-01, R2-02).
 *
 * Файлы с секретами по имени ([RuntimeBackupAndDiagnostics]) в копию не идут вовсе, но ключ бывает и внутри
 * обычного файла: `"apiKey": "sk-…"` в config.json, `DEEPSEEK_API_KEY=…` в заметке, токен в истории сессии.
 * Вычищаются только узнаваемые секреты: значения полей с именем секрета и ключи известного вида.
 * Общий [SecretScrubber] для копии слишком широк — он стёр бы `"sessionId"` и сломал историю сессий.
 *
 * Очистка учитывает формат, чтобы копия оставалась рабочей после восстановления:
 * - JSON-файл разбирается целиком и чистится по дереву (поле `apiKey` на следующей строке после `:`
 *   тоже находится), затем записывается заново — всегда валидным JSON;
 * - JSONL и прочий текст — построчно: строка-JSON чистится по дереву, остальное — правилами, которые
 *   не выходят за границу значения и не съедают кавычку соседней JSON-строки;
 * - файл без секретов и двоичный файл копируются байт в байт.
 */
internal object BackupSecrets {
    /** Имя поля-секрета целиком: apiKey, openai_api_key, accessToken, token, clientSecret, password, privateKey… */
    private const val SECRET_FIELD =
        "(?:[A-Za-z0-9_\\-]*(?:api[_\\-]?key|secret|password|passwd|private[_\\-]?key)" +
            "|(?:access|refresh|auth|bearer|id|session|api)?[_\\-]?token|authorization|x-api-key)"

    private val SECRET_FIELD_NAME = Regex("(?i)$SECRET_FIELD")

    /** Имя переменной окружения с секретом: DEEPSEEK_API_KEY, GITHUB_TOKEN, DB_PASSWORD… */
    private const val SECRET_ENV = "\\b([A-Z][A-Z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD))(\\s*[=:]\\s*)"

    /** Ключи известного вида — в любом месте текста. */
    private val KEY_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-.~+/=]{8,}""") to "Bearer ",
        Regex("""\bsk-[A-Za-z0-9_\-]{8,}""") to "sk-REDACTED",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""") to "gh_REDACTED",
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""") to "github_pat_REDACTED",
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}""") to "AIza_REDACTED",
    )

    /** `NAME="значение с пробелами"`, `NAME='…'` или `NAME=слово` — значение убирается целиком. */
    private val ENV_ANY = Regex(SECRET_ENV + """(?:"(?:[^"\\\n]|\\.)*"|'[^'\n]*'|[^\s"'\\,;]+)""")

    /** Без кавычек: внутри JSON-строки значение кончается на `"` или `\` и не съедает её закрывающую кавычку. */
    private val ENV_BARE = Regex(SECRET_ENV + """[^\s"'\\,;]+""")

    /** `"apiKey": "…"` в строке, которую не удалось разобрать как JSON (фрагмент большого JSON-файла). */
    private val JSON_FIELD = Regex("""(?i)("$SECRET_FIELD"\s*:\s*)"(?:[^"\\]|\\.)*"""")

    /** Целиком разбирается JSON-файл до этого размера; больший чистится построчно. */
    private const val WHOLE_JSON_LIMIT = 16L * 1024 * 1024

    private val json = Json { prettyPrint = false }
    private val prettyJson = Json { prettyPrint = true }

    /** Строка вне JSON (переменная окружения, заметка, YAML): значения секретов убираются целиком. */
    fun scrubText(text: String): String = KEY_RULES.fold(ENV_ANY.replace(text, "$1$2")) { acc, (re, to) -> re.replace(acc, to) }

    /**
     * Строка файла: JSON-строка (JSONL) — по дереву; похожая на фрагмент JSON — правилами, которые не выходят
     * за границу JSON-строки; остальное — [scrubText].
     */
    fun scrubLine(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parse(line)?.let { element ->
                val clean = scrubJson(element)
                return if (clean == element) line else json.encodeToString(JsonElement.serializer(), clean)
            }
        }
        if (!line.contains("\":")) return scrubText(line)
        val fields = JSON_FIELD.replace(line, "$1\"\"")
        return KEY_RULES.fold(ENV_BARE.replace(fields, "$1$2")) { acc, (re, to) -> re.replace(acc, to) }
    }

    /** JSON без секретов: значения полей-секретов — пустая строка, в остальных строках — [scrubText]. */
    fun scrubJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (value is JsonPrimitive && value.isString && SECRET_FIELD_NAME.matches(key)) JsonPrimitive("") else scrubJson(value)
        })
        is JsonArray -> JsonArray(element.map(::scrubJson))
        is JsonPrimitive -> if (element.isString) {
            val clean = scrubText(element.content)
            if (clean == element.content) element else JsonPrimitive(clean)
        } else {
            element
        }
    }

    /** Разбор JSON; глубокая вложенность (StackOverflowError) и ошибки разбора — null. */
    private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    /** Похож ли файл на текст: в первых 8 КБ нет нулевых байтов. */
    private fun looksText(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            val n = input.read(buf)
            (0 until maxOf(n, 0)).none { buf[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    /** Первый значащий символ — `{` или `[`: файл может быть одним JSON-документом. */
    private fun startsLikeJson(file: File): Boolean = runCatching {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            generateSequence { reader.read().takeIf { it >= 0 } }.map { it.toChar() }
                .firstOrNull { !it.isWhitespace() && it != '\uFEFF' }?.let { it == '{' || it == '[' } ?: false
        }
    }.getOrDefault(false)

    /** Записать [file] в [out]: секреты вычищены с сохранением формата, файл без секретов — как есть. */
    fun copyScrubbed(file: File, out: OutputStream) {
        if (!looksText(file)) return raw(file, out)
        if (file.length() <= WHOLE_JSON_LIMIT && startsLikeJson(file)) {
            val text = file.readText(Charsets.UTF_8)
            val element = parse(text)
            if (element != null) {
                val clean = runCatching { scrubJson(element) }.getOrNull() ?: return copyLines(file, out)
                if (clean == element) return raw(file, out)
                // Многострочный файл остаётся многострочным, однострочный — однострочным.
                val format = if (text.trimEnd().contains('\n')) prettyJson else json
                val encoded = format.encodeToString(JsonElement.serializer(), clean)
                out.write((if (text.endsWith("\n")) encoded + "\n" else encoded).toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
        }
        copyLines(file, out)
    }

    private fun raw(file: File, out: OutputStream) {
        file.inputStream().use { it.copyTo(out) }
    }

    /** Построчно, без чтения файла в память целиком; файл без секретов — байт в байт. */
    private fun copyLines(file: File, out: OutputStream) {
        val dirty = file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.any { scrubLine(it) != it } }
        if (!dirty) return raw(file, out)
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
