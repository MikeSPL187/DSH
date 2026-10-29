package com.dsh

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dsh.core.data.DshRepository
import com.dsh.feature.settings.ModelProviderUi
import com.dsh.feature.settings.logic.ApiKeyVault
import com.dsh.feature.settings.parseModelProviders
import com.dsh.runtime.host.AgentNetworkPolicy
import com.dsh.runtime.host.hostOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Провайдеры моделей Host: документы, из которых строится список (каталог провайдеров,
 * настройки, состояние ключей), чтение и сохранение ключа и полей провайдера.
 *
 * Одна копия на приложение: её читают «Настройки», первый запуск и вход по ключу.
 */
@Stable
internal class ModelProviders(private val repository: DshRepository) {
    private var providersRaw by mutableStateOf<String?>(null)
    private var settingsRaw by mutableStateOf<String?>(null)
    private var credentialsRaw by mutableStateOf<String?>(null)

    /** Провайдеры для экрана; [officialNamespaces] отделяют официальных от провайдеров плагинов. */
    fun list(officialNamespaces: Set<String>): List<ModelProviderUi> =
        runCatching { parseModelProviders(providersRaw, settingsRaw, credentialsRaw, officialNamespaces) }
            .getOrDefault(emptyList())
            // Адреса настроенных провайдеров нужны агенту — сеть агента их пропускает (G-50).
            .also { providers -> AgentNetworkPolicy.addDynamic(providers.flatMap { listOf(it.baseUrl, it.defaultBaseUrl) }) }

    /** Провайдер DeepSeek из каталога Host (свой API-ключ). */
    fun deepSeek(officialNamespaces: Set<String>): ModelProviderUi? = list(officialNamespaces).firstOrNull(::isDeepSeek)

    /** Перечитать каталог, настройки и состояние ключей всех провайдеров. */
    suspend fun refresh(hostId: String) {
        val settings = repository.hostSettings
        settingsRaw = settings.describeSettingsRaw(hostId)
        providersRaw = settings.configurableProvidersRaw(hostId)
        val refs = list(repository.plugins.officialSettingsNamespaces()).map { it.keyRef }.distinct()
        credentialsRaw = settings.describeCredentialsRaw(hostId, refs)
    }

    /**
     * Сохранить провайдера: ключ — в Host и в Keystore (в «Настройках» видно только ••••1234),
     * поля — в пространство настроек провайдера. Затем перечитать список и каталог моделей.
     * @param key новый ключ; пусто — ключ не меняется.
     * @param baseUrl свой адрес API; пусто — не меняется.
     * @param models модели провайдера; пусто — не меняются.
     * @return false — Host не принял ключ или настройки провайдера: экран не сообщает «сохранено» (DSH-05).
     *   Новый провайдер при отказе в настройках откатывается: его ключ убирается из Host и Keystore,
     *   чтобы не остаться наполовину настроенным (ключ есть, адреса и протокола нет).
     */
    suspend fun save(hostId: String, provider: ModelProviderUi, key: String, baseUrl: String = "", models: List<String> = emptyList()): Boolean {
        // Токен входа по подписке (Claude Pro/Max, ChatGPT, Google) в стороннем приложении запрещён правилами провайдеров —
        // за него блокируют аккаунт. Не сохраняется ни в один провайдер, включая «свой», где бы его ни вставили.
        if (com.dsh.feature.settings.logic.ProviderCatalog.isSubscriptionToken(key)) return false
        if (com.dsh.feature.settings.logic.ProviderCatalog.blocked(provider.id)) return false
        val vaultRef = vaultRefOf(provider)
        if (key.isNotBlank()) {
            if (!repository.plugins.setCredential(hostId, provider.keyRef, key)) return false
            ApiKeyVault.save(vaultRef, key)
        }
        var saved = true
        if (provider.settingsNs.isNotBlank()) {
            val path = provider.settingsPath
            val values = buildList<Pair<List<String>, JsonElement>> {
                if (key.isNotBlank()) add(path + "apiKeyEnv" to JsonPrimitive(provider.keyRef))
                // Маршрут не из адаптера обязан объявить протокол, иначе Host отклонит весь провайдер.
                if (!provider.declared) add(path + "api" to JsonPrimitive(wireProtocolFor(provider.id)))
                if (baseUrl.isNotBlank()) {
                    add(path + "baseURL" to JsonPrimitive(baseUrl))
                    // Свой адрес API — разрешён сети агента и после перезапуска, ещё до чтения настроек (G-50).
                    hostOf(baseUrl)?.let { AgentNetworkPolicy.allowHost(it) }
                }
                if (models.isNotEmpty()) {
                    add(path + "models" to JsonArray(models.map { buildJsonObject { put("id", it); put("name", it) } }))
                }
            }
            if (values.isNotEmpty()) saved = repository.hostSettings.setSettings(hostId, provider.settingsNs, values, null)
        }
        if (!saved && !provider.configured && key.isNotBlank()) {
            if (repository.plugins.unsetCredential(hostId, provider.keyRef)) ApiKeyVault.delete(vaultRef)
        }
        refresh(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        return saved
    }

    /** Удалить провайдера целиком: ключ из Host и Keystore и его профиль настроек (он уйдёт из списка и выбора модели). */
    suspend fun remove(hostId: String, provider: ModelProviderUi): Boolean {
        // Host не удалил ключ — провайдер остаётся как был: иначе ключ жил бы в Host без следа в «Настройках».
        if (!repository.plugins.unsetCredential(hostId, provider.keyRef)) return false
        ApiKeyVault.delete(vaultRefOf(provider))
        val ok = provider.settingsNs.isBlank() || provider.settingsPath.isEmpty() ||
            repository.hostSettings.unsetSettings(hostId, provider.settingsNs, listOf(provider.settingsPath))
        refresh(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        return ok
    }

    /**
     * Удалить ключ провайдера из Host и Keystore.
     * @return false — Host не подтвердил удаление: локальная отметка о ключе остаётся, потому что Host
     *   продолжает хранить и использовать ключ (DSH-06). Повтор безопасен: удаление в Host идемпотентно.
     */
    suspend fun deleteKey(hostId: String, provider: ModelProviderUi?): Boolean {
        if (provider != null && !repository.plugins.unsetCredential(hostId, provider.keyRef)) return false
        ApiKeyVault.delete(if (provider == null) DEEPSEEK_VAULT_REF else vaultRefOf(provider))
        refresh(hostId)
        return true
    }
}

/** Ссылка на ключ в Keystore: у официального DeepSeek — общий слот, у остальных — свой. */
private fun vaultRefOf(provider: ModelProviderUi): String = if (isDeepSeek(provider)) DEEPSEEK_VAULT_REF else provider.keyRef

/** Ключ официального DeepSeek в Host. */
internal const val DEEPSEEK_KEY_REF = "DEEPSEEK_API_KEY"

/** id официального DeepSeek: маршрут Host и общий провайдер pi-ai, который подменяет его без маршрута. */
private val DEEPSEEK_PROVIDER_IDS = setOf("deepseek-official", "deepseek")

/**
 * Провайдер — официальный DeepSeek. Только точные id и ссылка на ключ: подстрока в имени своего шлюза
 * (`my-deepseek-proxy`) не делает его DeepSeek — иначе его ключ лёг бы в слот официального ключа,
 * а баланс и быстрые функции отправляли бы ключ шлюза в api.deepseek.com (DSH-07).
 */
internal fun isDeepSeek(provider: ModelProviderUi): Boolean =
    provider.id in DEEPSEEK_PROVIDER_IDS || provider.keyRef == DEEPSEEK_KEY_REF

/**
 * id нового своего провайдера из [name]: не совпадает ни с одним провайдером каталога [taken]
 * и с официальным DeepSeek — иначе «свой» провайдер переписал бы чужой профиль и ключ.
 */
internal fun customProviderId(name: String, taken: Set<String>): String {
    val base = name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom-api" }
    val reserved = taken + DEEPSEEK_PROVIDER_IDS + "deepseek-account"
    if (base !in reserved && base.uppercase(java.util.Locale.ROOT).replace('-', '_') + "_API_KEY" != DEEPSEEK_KEY_REF) return base
    return generateSequence(1) { it + 1 }.map { if (it == 1) "$base-custom" else "$base-custom-$it" }.first { it !in reserved }
}
