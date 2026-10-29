package com.dsh.feature.settings

import com.dsh.core.protocol.parseHostJson
import com.dsh.core.designsystem.kit.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Провайдер моделей Host (settings/describe + llm/listConfigurableProviders + credentials/describe). */
data class ModelProviderUi(
    val id: String,
    val name: String,
    val models: List<String>,
    val keyRef: String,
    val keyConfigured: Boolean,
    val keyWritable: Boolean = true,
    val settingsNs: String = "",
    val settingsPath: List<String> = emptyList(),
    val baseUrl: String = "",
    /** Маршрут каталога даёт адаптер (true); false — протокол api объявляется самим. */
    val declared: Boolean = true,
    /** Адрес по умолчанию из каталога — подсказка в своих настройках. */
    val defaultBaseUrl: String = "",
    /** Провайдер из стороннего плагина (например, OAuth подписки) — показывается отдельно от встроенных. */
    val pluginProvided: Boolean = false,
    /**
     * Настроен ли в Host (есть значения профиля или сохранённый ключ).
     * Ненастроенные элементы каталога есть только в «Добавить провайдера», в редактор — после выбора.
     */
    val configured: Boolean = true,
)

/** result.value из ответа сервера. */
fun responseValue(raw: String?): JsonElement? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseHostJson(raw)
        val obj = root as? JsonObject ?: return null
        val result = obj["result"] as? JsonObject ?: return null
        result["value"] ?: result
    }.getOrNull()
}

/**
 * Запасной белый список пространств официальных провайдеров.
 *
 * Обычно принадлежность решает Host: `host-slots` моста перечисляет только пространства настроек
 * официальных строк Loader (`officialNamespaces` в [parseModelProviders]). Этот список — лишь
 * пока мост не готов, чтобы страница моделей не была пустой.
 */
private val FALLBACK_OFFICIAL_MODEL_NAMESPACES: Set<String> = setOf(
    "llm-deepseek",
    "llm-deepseek-account",
    "llm-pi-ai",
)

fun parseModelProviders(
    providersRaw: String?,
    settingsRaw: String?,
    credentialsRaw: String?,
    /** Официальные пространства настроек от Host; null — мост ещё не сообщил. */
    officialNamespaces: Set<String>? = null,
): List<ModelProviderUi> {
    val providers = (responseValue(providersRaw) as? JsonArray) ?: JsonArray(emptyList())
    val namespaces = runCatching {
        val value = responseValue(settingsRaw) as? JsonObject ?: return@runCatching emptyMap<String, JsonObject?>()
        (value["namespaces"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val ns = (obj["ns"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            ns to (obj["value"] as? JsonObject)
        }.toMap()
    }.getOrElse { emptyMap() }
    val credentials = runCatching {
        val value = responseValue(credentialsRaw) as? JsonObject ?: return@runCatching emptyMap()
        value.entries.associate { (ref, info) ->
            val configured = (info as? JsonObject)?.get("configured")?.toString() == "true"
            val writable = (info as? JsonObject)?.get("writable")?.toString() != "false"
            ref to (configured to writable)
        }
    }.getOrElse { emptyMap() }

    val parsed = providers.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val provider = (obj["provider"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val displayName = (obj["displayName"] as? JsonPrimitive)?.content ?: provider
        val settingsNs = (obj["settingsNs"] as? JsonPrimitive)?.content.orEmpty()
        val settingsPath = (obj["settingsPath"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

        var profile: JsonObject? = namespaces[settingsNs]
        for (segment in settingsPath) {
            profile = profile?.get(segment) as? JsonObject ?: null
        }
        if (provider == "deepseek-account") return@mapNotNull null
        // Маршруты только со входом по подписке (ChatGPT/Codex, Copilot, Gemini CLI…) — запрещены правилами
        // провайдеров в сторонних приложениях: в DSH их нет вовсе.
        if (com.dsh.feature.settings.logic.ProviderCatalog.blocked(provider)) return@mapNotNull null
        // Белый список: на странице моделей только провайдеры официальных пространств.
        // `llm/listConfigurableProviders` возвращает провайдеров всех пространств,
        // включая наш плагин подписок (llm-subscription-oauth: Kimi/Grok/GLM/Ark/MiMo/
        // WorkBuddy/Antigravity) и любые будущие плагины. Белый, а не чёрный список —
        // новые плагины не просочатся в официальный каталог; их провайдерами управляют страницы плагинов.
        val officialNames = officialNamespaces?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_OFFICIAL_MODEL_NAMESPACES
        if (settingsNs !in officialNames) return@mapNotNull null
        // Ненастроенные элементы каталога остаются (configured=false): по этому признаку
        // панель решает, показывать ли карточку, а остальные идут в «Добавить провайдера».
        val apiKeyEnv = (profile?.get("apiKeyEnv") as? JsonPrimitive)?.content
        val keyRef = apiKeyEnv ?: provider.uppercase().replace('-', '_') + "_API_KEY"
        val credential = credentials[keyRef]
        val baseUrl = (profile?.get("baseURL") as? JsonPrimitive)?.content.orEmpty()
        val modelList = (profile?.get("models") as? JsonArray)?.mapNotNull { model ->
            when (model) {
                is JsonObject -> (model["name"] as? JsonPrimitive)?.content
                    ?: (model["id"] as? JsonPrimitive)?.content
                is JsonPrimitive -> model.content
                else -> null
            }
        }.orEmpty().distinct()

        val declared = obj["declared"]?.toString() != "false"
        val pluginProvided = settingsNs.startsWith("llm-subscription")
        ModelProviderUi(
            id = provider,
            name = displayName,
            declared = declared,
            pluginProvided = pluginProvided,
            defaultBaseUrl = (obj["defaultBaseURL"] as? JsonPrimitive)?.content.orEmpty(),
            models = modelList,
            keyRef = keyRef,
            keyConfigured = credential?.first ?: false,
            keyWritable = credential?.second ?: true,
            settingsNs = settingsNs,
            settingsPath = settingsPath,
            baseUrl = baseUrl,
            configured = profile != null || credential?.first == true,
        )
    }
    // В каталоге Host есть и официальный `deepseek-official`, и общий `deepseek` из pi-ai:
    // названия почти одинаковые (DeepSeek / deepseek), и в настройках было бы две
    // карточки DeepSeek. Если есть официальный маршрут, общий отбрасывается.
    val hasOfficialDeepSeek = parsed.any { it.id == "deepseek-official" }
    val deduped = if (hasOfficialDeepSeek) {
        parsed.filterNot { it.id == "deepseek" }
    } else {
        parsed
    }
    // Встроенный DeepSeek API есть всегда — даже если каталог Host не пришёл, его можно настроить.
    val hasDeepSeek = deduped.any { it.id.contains("deepseek", ignoreCase = true) || it.name.contains("DeepSeek", ignoreCase = true) }
    return if (hasDeepSeek) deduped else deduped + ModelProviderUi(
        id = "deepseek",
        name = "DeepSeek",
        models = emptyList(),
        keyRef = "DEEPSEEK_API_KEY",
        keyConfigured = credentials["DEEPSEEK_API_KEY"]?.first ?: false,
        keyWritable = credentials["DEEPSEEK_API_KEY"]?.second ?: true,
        settingsNs = "llm",
        settingsPath = listOf("providers", "deepseek"),
        configured = credentials["DEEPSEEK_API_KEY"]?.first == true,
    )
}





