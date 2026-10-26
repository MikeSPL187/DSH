package com.deepseek.dsh.runtime.host

/**
 * Вычистка секретов из текста, который уходит из приложения или показывается на экране:
 * журнал Host, текст ошибки, экспорт диагностики, «Скопировать отчёт».
 *
 * Одни правила для всех мест: раньше журнал Host чистился только от `token=`, и API-ключ,
 * заголовок Authorization или переменная окружения с ключом в выводе Host попадали в отчёт.
 */
object SecretScrubber {
    private val SECRET_KEYS =
        "(?:token|access_?token|refresh_?token|api_?key|apikey|secret|password|passwd|authorization|cookie|session|credential|private_?key)"

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)([?&](?:token|auth|access_token|key)=)[^&\s"']+""") to "$1REDACTED",
        Regex("""(?i)token=[a-zA-Z0-9_\-.]+""") to "token=REDACTED",
        Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-.~+/=]+""") to "Bearer REDACTED",
        Regex("""(?i)\b(authorization|x-api-key|cookie|set-cookie)\s*:\s*[^\r\n]+""") to "$1: REDACTED",
        Regex("""(?i)("[A-Za-z0-9_\-]*$SECRET_KEYS[A-Za-z0-9_\-]*"\s*:\s*)"[^"]*"""") to "$1\"REDACTED\"",
        // Переменные окружения и строки конфигурации: DEEPSEEK_API_KEY=…, GITHUB_TOKEN: …
        Regex("""\b([A-Z][A-Z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIALS?))(\s*[=:]\s*)(?!REDACTED)["']?[^\s"',;]+["']?""") to "$1$2REDACTED",
        Regex("""(?i)\b(dsh-web-session|session|sid)=[^;\s"']+""") to "$1=REDACTED",
        Regex("""\bsk-[A-Za-z0-9_\-]{8,}""") to "sk-REDACTED",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""") to "gh_REDACTED",
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""") to "github_pat_REDACTED",
    )

    /** Текст без секретов. */
    fun scrub(text: String): String = RULES.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
}
