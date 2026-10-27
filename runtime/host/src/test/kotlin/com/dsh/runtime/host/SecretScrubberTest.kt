package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretScrubberTest {
    private val key = "sk-0123456789abcdef0123456789abcdef"

    @Test
    fun `ключи, токены и заголовки вычищаются`() {
        val raw = listOf(
            "GET http://127.0.0.1:8080/?token=abc123&x=1",
            "Authorization: Bearer $key",
            "env DEEPSEEK_API_KEY=$key GITHUB_TOKEN='ghp_abcdefghijklmnopqrstuvwxyz0123'",
            """{"apiKey":"secret-value","name":"ok"}""",
            "Cookie: dsh-web-session=s1",
            "OPENAI_API_KEY: plainvalue",
            "pat github_pat_11ABCDEFG0123456789_abcdefghijklmnop",
        ).joinToString("\n")

        val out = SecretScrubber.scrub(raw)

        for (secret in listOf("abc123", key, "ghp_abcdefghij", "secret-value", "s1\n", "plainvalue", "github_pat_11ABC")) {
            assertFalse("в выводе остался $secret:\n$out", out.contains(secret))
        }
        assertFalse(out.contains("0123456789abcdef"))
    }

    @Test
    fun `обычный текст не меняется`() {
        val text = "npm ERR! code ENOENT\nnode v20.11.1 PATH=/usr/bin MAX_TOKENS_LIMIT is fine"
        assertEquals(text, SecretScrubber.scrub(text))
    }

    @Test
    fun `пароль в адресе и токен npm вычищаются`() {
        val raw = "fatal: unable to access 'https://mike:ghs_Secret123@github.com/x/y.git/': 403\n" +
            "npm ERR! //registry.npmjs.org/:_authToken=npm_SecretToken42\n" +
            "remote: https://oauth2:glpat-Secret789@gitlab.com/a.git"
        val out = SecretScrubber.scrub(raw)
        for (secret in listOf("ghs_Secret123", "npm_SecretToken42", "glpat-Secret789")) {
            assertFalse("в выводе остался $secret:\n$out", out.contains(secret))
        }
        // Адрес без пароля и порт не трогаются.
        val plain = "GET http://127.0.0.1:8080/api ssh://git@github.com:22/x"
        assertEquals(plain, SecretScrubber.scrub(plain))
    }
}
