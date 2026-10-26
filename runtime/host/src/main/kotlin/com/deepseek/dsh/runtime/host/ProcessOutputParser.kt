package com.deepseek.dsh.runtime.host

import java.util.regex.Pattern

/**
 * Порт — случайный на loopback; из stdout разбираются URL `dsh web:` и токен,
 * например:
 * "dsh web: listening on http://127.0.0.1:45321/?token=a8f9c1b7e4..."
 * или "dsh web: listening on http://localhost:45321/?token=..."
 */
object ProcessOutputParser {

    private val WEB_URL_PATTERN = Pattern.compile(
        """dsh\s+web:\s+listening\s+on\s+(https?://(?:127\.0\.0\.1|localhost):(\d+)(?:/?[^\s]*\?token=([a-zA-Z0-9_\-]+))?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val SIMPLE_URL_PATTERN = Pattern.compile(
        """(https?://(?:127\.0\.0\.1|localhost):(\d+)(?:/?[^\s]*\?token=([a-zA-Z0-9_\-]+))?)""",
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

        val simpleMatcher = SIMPLE_URL_PATTERN.matcher(line)
        if (simpleMatcher.find()) {
            val url = simpleMatcher.group(1) ?: return null
            val port = simpleMatcher.group(2)?.toIntOrNull() ?: return null
            val token = simpleMatcher.group(3)
            return ParsedEndpoint(rawUrl = url, port = port, token = token)
        }

        return null
    }
}
