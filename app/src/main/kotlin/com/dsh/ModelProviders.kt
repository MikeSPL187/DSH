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
     * @return false — Host не принял ключ: в Keystore он не пишется, «Настройки» не покажут его сохранённым.
     */
    suspend fun save(hostId: String, provider: ModelProviderUi, key: String, baseUrl: String = "", models: List<String> = emptyList()): Boolean {
        if (key.isNotBlank()) {
            if (!repository.plugins.setCredential(hostId, provider.keyRef, key)) return false
            ApiKeyVault.save(if (isDeepSeek(provider)) DEEPSEEK_VAULT_REF else provider.keyRef, key)
        }
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
            if (values.isNotEmpty()) repository.hostSettings.setSettings(hostId, provider.settingsNs, values, null)
        }
        refresh(hostId)
        repository.hostSettings.refreshModelCatalog(hostId)
        return true
    }

    /** Удалить ключ провайдера из Host и Keystore. */
    suspend fun deleteKey(hostId: String, provider: ModelProviderUi?) {
        if (provider != null) repository.plugins.unsetCredential(hostId, provider.keyRef)
        ApiKeyVault.delete(if (provider == null || isDeepSeek(provider)) DEEPSEEK_VAULT_REF else provider.keyRef)
        refresh(hostId)
    }
}

/** Провайдер — это DeepSeek (официальный маршрут или ключ DeepSeek). */
internal fun isDeepSeek(provider: ModelProviderUi): Boolean =
    provider.id == "deepseek-official" || provider.id.contains("deepseek") ||
        provider.keyRef.contains("deepseek", ignoreCase = true)
