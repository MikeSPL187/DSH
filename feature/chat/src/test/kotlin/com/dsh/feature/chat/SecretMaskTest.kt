package com.dsh.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskTest {
    @Test
    fun `keys and tokens keep only the last four characters`() {
        assertEquals("ключ ••••Q9z1 для DeepSeek", SecretMask.mask("ключ sk-4f8a2b9c0d1e2f3a4b5c6d7eQ9z1 для DeepSeek"))
        assertEquals("••••abcd", SecretMask.mask("ghp_" + "x".repeat(32) + "abcd"))
        assertEquals("Authorization: Bearer ••••0001", SecretMask.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload0001"))
        assertEquals("DB_PASSWORD=••••ret!", SecretMask.mask("DB_PASSWORD=sup3rSecret!"))
        assertEquals("••••••••", SecretMask.mask("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `ordinary text and short values stay`() {
        val text = "Обнови sk-tool до 2.0 и проверь token: abc, AKIA мало"
        assertEquals(text, SecretMask.mask(text))
        assertFalse(SecretMask.ranges("git push origin main").isNotEmpty())
        assertTrue(SecretMask.ranges("AKIAIOSFODNN7EXAMPLE").isNotEmpty())
    }
}
