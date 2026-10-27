package com.dsh.runtime.host

import java.util.regex.Pattern

/**
 * Порт — случайный на loopback; из stdout разбираются URL `dsh web:` и токен,
 * например:
 * "dsh web: listening on http://127.0.0.1:45321/?token=a8f9c1b7e4..."
 * или "dsh web: listening on http://localhost:45321/?token=..."
 */
object ProcessOutputParser {

    /**
     * Только строка самого Host в начале вывода: `dsh web: <url>` или `dsh web: listening on <url>`, с токеном.
     * Раньше подходил любой адрес loopback (H-5) — «Server at http://localhost:3000» из плагина при запуске
     * становился адресом Host, и приложение ждало ответа не от того процесса.
     */
    private val WEB_URL_PATTERN = Pattern.compile(
        """^dsh\s+web:\s+(?:listening\s+on\s+)?(https?://(?:127\.0\.0\.1|localhost):(\d+)/?[^\s]*\?token=([a-zA-Z0-9_\-]+))""",
        Pattern.CASE_INSENSITIVE
    )

    data class ParsedEndpoint(
        val rawUrl: String,
        val port: Int,
        val token: String?
    )

    fun parseLine(line: String): ParsedEndpoint? {
        val matcher = WEB_URL_PATTERN.matcher(line)
        if (matcher.find()) {
            val url = matcher.group(1) ?: return null
            val port = matcher.group(2)?.toIntOrNull() ?: return null
            val token = matcher.group(3)
            return ParsedEndpoint(rawUrl = url, port = port, token = token)
        }

        return null
    }
}
