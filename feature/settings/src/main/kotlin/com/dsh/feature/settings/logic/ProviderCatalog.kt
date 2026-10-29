package com.dsh.feature.settings.logic

/**
 * Провайдеры моделей, которые DSH предлагает подключить сам: только по API-ключу из консоли провайдера.
 *
 * Вход по подписке (Claude Pro/Max, ChatGPT Plus/Pro, Gemini CLI / Antigravity, GitHub Copilot) в сторонних
 * приложениях запрещён правилами провайдеров — за него блокируют аккаунт. Поэтому:
 * - такие маршруты каталога Host ([BLOCKED_ROUTES]) не показываются и не настраиваются;
 * - токен подписки, вставленный вместо API-ключа, распознаётся и не сохраняется ([issue]).
 * API-ключ — разрешённый путь: оплата по токенам у провайдера.
 */
object ProviderCatalog {
    /** Провайдер: [id] — маршрут каталога Host (pi-ai), [keyPrefixes] — как начинается API-ключ. */
    data class Spec(
        val id: String,
        val name: String,
        val by: String,
        /** Где взять ключ — показывается и открывается. */
        val site: String,
        val siteUrl: String,
        val placeholder: String,
        val keyPrefixes: List<String>,
        /** Так начинаются токены входа по подписке этого провайдера — их сохранять нельзя. */
        val subscriptionPrefixes: List<String>,
    )

    val all: List<Spec> = listOf(
        Spec(
            "anthropic", "Claude", "Anthropic", "console.anthropic.com", "https://console.anthropic.com/settings/keys", "sk-ant-api03-…",
            keyPrefixes = listOf("sk-ant-api"), subscriptionPrefixes = listOf("sk-ant-oat", "sk-ant-ort", "sk-ant-sid"),
        ),
        Spec(
            "openai", "OpenAI", "OpenAI", "platform.openai.com", "https://platform.openai.com/api-keys", "sk-proj-…",
            keyPrefixes = listOf("sk-"), subscriptionPrefixes = listOf("eyJ"),
        ),
        Spec(
            "google", "Gemini", "Google", "aistudio.google.com", "https://aistudio.google.com/apikey", "AIza…",
            keyPrefixes = listOf("AIza"), subscriptionPrefixes = listOf("ya29.", "1//"),
        ),
        Spec(
            "openrouter", "OpenRouter", "OpenRouter", "openrouter.ai/keys", "https://openrouter.ai/keys", "sk-or-v1-…",
            keyPrefixes = listOf("sk-or-"), subscriptionPrefixes = emptyList(),
        ),
    )

    fun spec(id: String): Spec? = all.firstOrNull { it.id == id }

    /**
     * Маршруты каталога Host, которые работают только через вход по подписке: в DSH их нет вовсе
     * (ни в списке провайдеров, ни в выборе модели).
     */
    val BLOCKED_ROUTES: Set<String> = setOf(
        "openai-codex", "github-copilot", "google-gemini-cli", "google-antigravity", "anthropic-oauth", "claude-pro", "claude-max",
    )

    fun blocked(routeId: String): Boolean = routeId in BLOCKED_ROUTES

    enum class Issue { SUBSCRIPTION, FORMAT }

    /** Что не так с ключом [key] для провайдера [id]; null — похож на API-ключ (проверку у провайдера это не заменяет). */
    fun issue(id: String, key: String): Issue? {
        val k = key.trim()
        if (k.isEmpty()) return null
        if (isSubscriptionToken(k)) return Issue.SUBSCRIPTION
        val spec = spec(id) ?: return null
        return if (spec.keyPrefixes.any { k.startsWith(it) }) null else Issue.FORMAT
    }

    /** Токен входа по подписке любого известного провайдера — не сохранять ни в один провайдер, включая «свой». */
    fun isSubscriptionToken(key: String): Boolean {
        val k = key.trim()
        return all.any { s -> s.subscriptionPrefixes.any { k.startsWith(it) } }
    }
}
