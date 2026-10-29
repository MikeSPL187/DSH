package com.dsh.feature.settings

import com.dsh.feature.settings.logic.ProviderCatalog
import com.dsh.feature.settings.logic.ProviderCatalog.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun `subscription tokens are refused for every provider`() {
        // Claude Pro/Max: OAuth-токен, refresh и сессия — не API-ключ.
        assertEquals(Issue.SUBSCRIPTION, ProviderCatalog.issue("anthropic", "sk-ant-oat01-AbCd"))
        assertEquals(Issue.SUBSCRIPTION, ProviderCatalog.issue("anthropic", "sk-ant-ort01-AbCd"))
        // Вход ChatGPT (JWT) и Google (ya29.) — тоже.
        assertEquals(Issue.SUBSCRIPTION, ProviderCatalog.issue("openai", "eyJhbGciOiJSUzI1NiJ9.x.y"))
        assertEquals(Issue.SUBSCRIPTION, ProviderCatalog.issue("google", "ya29.a0AfH6"))
        // Токен подписки, вставленный в чужой провайдер, тоже не проходит.
        assertEquals(Issue.SUBSCRIPTION, ProviderCatalog.issue("openrouter", "sk-ant-oat01-AbCd"))
        assertTrue(ProviderCatalog.isSubscriptionToken("  sk-ant-oat01-x "))
    }

    @Test
    fun `api keys pass, wrong formats are named`() {
        assertNull(ProviderCatalog.issue("anthropic", "sk-ant-api03-Xq7f"))
        assertNull(ProviderCatalog.issue("openai", "sk-proj-9Kd2"))
        assertNull(ProviderCatalog.issue("google", "AIzaSyB4c1"))
        assertNull(ProviderCatalog.issue("openrouter", "sk-or-v1-7e3a"))
        assertEquals(Issue.FORMAT, ProviderCatalog.issue("anthropic", "sk-proj-9Kd2"))
        assertEquals(Issue.FORMAT, ProviderCatalog.issue("google", "sk-123"))
        assertNull(ProviderCatalog.issue("anthropic", "   "))
    }

    @Test
    fun `subscription-only routes are blocked`() {
        listOf("openai-codex", "github-copilot", "google-gemini-cli", "google-antigravity").forEach { assertTrue(it, ProviderCatalog.blocked(it)) }
        listOf("anthropic", "openai", "google", "openrouter", "deepseek").forEach { assertTrue(it, !ProviderCatalog.blocked(it)) }
    }
}
