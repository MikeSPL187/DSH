package com.dsh.feature.chat

/**
 * Секреты в тексте сообщения (ключи API, токены, пароли, приватные ключи): найденный фрагмент
 * рисуется как `••••` и последние 4 символа, сам текст сообщения не меняется.
 */
internal object SecretMask {
    private val PATTERNS = listOf(
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        Regex("""\bsk-(?:proj-|ant-[a-z0-9]+-)?[A-Za-z0-9_\-]{20,}"""),
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{40,}\b"""),
        Regex("""\bglpat-[A-Za-z0-9_\-]{20,}\b"""),
        Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}\b"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{35}\b"""),
        Regex("""(?<=\bBearer )[A-Za-z0-9._~+/\-]{20,}=*"""),
        // `password: …`, `api_key=…`, `TOKEN="…"` — маскируется только значение.
        Regex("""(?i)(?<![a-z])(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret)["']?\s{0,3}[:=]\s{0,3}["']?([^\s"']{8,})"""),
    )

    /** Диапазоны секретов в [text], без пересечений, по возрастанию. */
    fun ranges(text: String): List<IntRange> {
        if (text.length < 8) return emptyList()
        // Шаблон с группой маскирует только её (значение после `password=`), без группы — совпадение целиком.
        val found = PATTERNS.flatMap { p -> p.findAll(text).map { m -> if (m.groupValues.size > 1) m.groups[1]?.range ?: m.range else m.range } }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    /** Как показать секрет: `••••` и последние 4 символа (короткий — только точки). */
    fun shown(secret: String): String {
        val tail = secret.trimEnd().takeLast(4)
        return if (secret.length <= 8 || tail.any { it.isWhitespace() || it == '-' }) "••••••••" else "••••$tail"
    }

    /** [text] с замаскированными секретами. */
    fun mask(text: String): String {
        val rs = ranges(text)
        if (rs.isEmpty()) return text
        val sb = StringBuilder()
        var pos = 0
        for (r in rs) {
            sb.append(text, pos, r.first)
            sb.append(shown(text.substring(r)))
            pos = r.last + 1
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }
}
