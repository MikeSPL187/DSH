package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.feature.settings.ModelProviderUi
import com.deepseek.dsh.feature.settings.parseModelProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Обновить данные провайдеров моделей: каталог провайдеров, документ настроек, состояние ключей.
 *
 * Вне MainAppHost: это не composable-код, ему не место
 * в корневой функции интерфейса.
 */
internal fun refreshModelProviderData(
    scope: CoroutineScope,
    repository: DshRepository,
    hostId: String,
    onProviderIds: (List<String>) -> Unit,
    onSettingsRaw: (String?) -> Unit,
    onProvidersRaw: (String?) -> Unit,
    onCredentialsRaw: (String?) -> Unit,
) {
    scope.launch {
        val registeredRaw = repository.rpcRaw(hostId, "llm/listProviders", "{}").getOrNull()
        onProviderIds(
            runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(registeredRaw.orEmpty())
                val value = ((root as? kotlinx.serialization.json.JsonObject)
                    ?.get("result") as? kotlinx.serialization.json.JsonObject)?.get("value")
                (value as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { item ->
                        ((item as? kotlinx.serialization.json.JsonObject)
                            ?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    .orEmpty()
            }.getOrDefault(emptyList()),
        )
        val settingsRaw = repository.rpcRaw(hostId, "settings/describe", "{}").getOrNull()
        val providersRaw = repository.rpcRaw(hostId, "llm/listConfigurableProviders", "{}").getOrNull()
        onSettingsRaw(settingsRaw)
        onProvidersRaw(providersRaw)
        val providerRefs = parseModelProviders(
            providersRaw,
            settingsRaw,
            null,
            repository.plugins.officialSettingsNamespaces(),
        ).map { it.keyRef }.distinct()
        val refsJson = providerRefs.joinToString(prefix = "{\"refs\":[", postfix = "]}") { "\"$it\"" }
        onCredentialsRaw(repository.rpcRaw(hostId, "credentials/describe", refsJson).getOrNull())
    }
}

/** Сохранить провайдера моделей: ключ и поля настроек (apiKeyEnv/api/baseURL/models). */
internal fun saveModelProvider(
    scope: CoroutineScope,
    repository: DshRepository,
    hostId: String,
    provider: ModelProviderUi,
    key: String,
    baseUrl: String,
    models: List<String>,
    onSettingsRaw: (String?) -> Unit,
    onCredentialsRaw: (String?) -> Unit,
) {
    scope.launch {
        if (key.isNotBlank()) {
            val credArgs = JSONObject().put("ref", provider.keyRef).put("value", key).toString()
            repository.rpcRaw(hostId, "credentials/set", credArgs)
            // Копия в Android Keystore: «Настройки» показывают ••••1234 и баланс, сам ключ наружу не отдаётся.
            val ref = if (provider.id.contains("deepseek") || provider.keyRef.contains("DEEPSEEK", ignoreCase = true)) DEEPSEEK_VAULT_REF else provider.keyRef
            com.deepseek.dsh.feature.settings.logic.ApiKeyVault.save(ref, key)
        }
        if (provider.settingsNs.isNotBlank()) {
            val ops = JSONArray()
            if (key.isNotBlank()) {
                ops.put(
                    JSONObject()
                        .put("op", "set")
                        .put("path", JSONArray(provider.settingsPath + "apiKeyEnv"))
                        .put("value", provider.keyRef),
                )
            }
            // Маршрут не из адаптера обязан объявить протокол, иначе Host отклонит
            // весь провайдер с settings/rejected.
            if (!provider.declared) {
                ops.put(
                    JSONObject()
                        .put("op", "set")
                        .put("path", JSONArray(provider.settingsPath + "api"))
                        .put("value", wireProtocolFor(provider.id)),
                )
            }
            if (baseUrl.isNotBlank()) {
                ops.put(
                    JSONObject()
                        .put("op", "set")
                        .put("path", JSONArray(provider.settingsPath + "baseURL"))
                        .put("value", baseUrl),
                )
            }
            if (models.isNotEmpty()) {
                val modelArray = JSONArray()
                models.forEach { model ->
                    modelArray.put(JSONObject().put("id", model).put("name", model))
                }
                ops.put(
                    JSONObject()
                        .put("op", "set")
                        .put("path", JSONArray(provider.settingsPath + "models"))
                        .put("value", modelArray),
                )
            }
            val args = JSONObject()
                .put("ns", provider.settingsNs)
                .put("ops", ops)
                .put("expectedRevision", JSONObject.NULL)
                .toString()
            repository.rpcRaw(hostId, "settings/mutate", args)
        }
        onSettingsRaw(repository.rpcRaw(hostId, "settings/describe", "{}").getOrNull())
        val refsJson = "{\"refs\":[\"${provider.keyRef}\"]}"
        onCredentialsRaw(repository.rpcRaw(hostId, "credentials/describe", refsJson).getOrNull())
        repository.hostSettings.refreshModelCatalog(hostId)
    }
}

/**
 * Язык интерфейса: выбранное значение и переключение (язык приложения + язык Host).
 * @param hostId текущий Host; пусто — меняется только язык интерфейса.
 */
@Composable
internal fun rememberAppLanguage(
    repository: DshRepository,
    hostId: String,
    scope: CoroutineScope,
): Pair<String, (String) -> Unit> {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(AppLanguage.selection(context)) }
    val change: (String) -> Unit = { next ->
        selection = next
        AppLanguage.apply(context, next)
        scope.launch {
            repository.hostSettings.syncHostLocale(hostId)
        }
    }
    return selection to change
}

/**
 * Полная установка «Добавить плагин»: установка Host → запасной установщик приложения → перезапуск Host → обновление каталога.
 *
 * Каждый шаг сообщается через [onProgress], обновление состояния
 * — через колбэки вызывающего.
 */
internal suspend fun installPluginWithProgress(
    context: android.content.Context,
    repository: DshRepository,
    hostId: String,
    pkgName: String,
    installer: com.deepseek.dsh.runtime.host.PluginBundleInstaller,
    onProgress: (Float, String) -> Unit,
    onManagedPackages: (Set<String>) -> Unit,
    onNativeCompanion: (com.deepseek.dsh.core.bridge.BridgeNativeCompanion?) -> Unit,
): Result<String> {
    // Установка идёт секунды: сначала «Принято», затем установщик сообщает каждый шаг.
    onProgress(0.02f, context.getString(R.string.plugins_install_preparing))
    val hostRes = kotlinx.coroutines.coroutineScope {
        val progressJob = launch {
            repository.plugins.installProgressFlow.collect { progress ->
                if (progress == null) return@collect
                val label = when (progress.phase) {
                    "installing" -> if (progress.total > 1) {
                        context.getString(
                            R.string.plugins_install_state_installing,
                            progress.registry.orEmpty(),
                            progress.index,
                            progress.total,
                        )
                    } else {
                        context.getString(R.string.plugins_install_progress_title)
                    }
                    "applying" -> context.getString(R.string.plugins_install_state_applying)
                    "cancelling" -> context.getString(R.string.plugins_install_state_cancelling)
                    else -> context.getString(R.string.plugins_install_progress_title)
                }
                val fraction = 0.1f + 0.45f * progress.index.toFloat() /
                    progress.total.coerceAtLeast(1)
                onProgress(fraction.coerceIn(0.05f, 0.6f), label)
            }
        }
        try {
            repository.plugins.installPlugin(hostId, pkgName)
        } finally {
            progressJob.cancel()
        }
    }
    // pnpm в Host использует rename/unlink/mkdtemp под proot — здесь они возвращают ENOSYS.
    // Тогда то же делает приложение своими файлами и сетью, а Host перезапускается.
    val usedFallback = hostRes.isFailure
    val result = if (hostRes.isSuccess) {
        onProgress(0.6f, context.getString(R.string.plugins_install_progress_title))
        Result.success(pkgName)
    } else {
        onProgress(0.05f, context.getString(R.string.plugins_install_fallback))
        installer.install(pkgName) { fraction, step ->
            onProgress(0.05f + fraction * 0.75f, step)
        }
    }
    return result.fold(
        onSuccess = { installed ->
            var restartFailure: Throwable? = null
            if (usedFallback) {
                // Файлы на месте, но плагин попадёт в Loader только после перезапуска Host. Ошибка
                // перезапуска — не успех: экран показал бы «Установлен», а Host его не загрузил.
                onProgress(0.85f, context.getString(R.string.plugins_install_restarting))
                restartFailure = com.deepseek.dsh.runtime.host.HostRuntimeHolder.restartHost
                    ?.invoke()
                    ?.exceptionOrNull()
                kotlinx.coroutines.delay(1_200)
            }
            onManagedPackages(runCatching { installer.managedPackages() }.getOrDefault(emptySet()))
            repository.plugins.refreshPluginInventory(hostId)
            repository.plugins.refreshBridgePluginCatalog()
            repository.plugins.refreshPluginSettingsPages()
            // Если у JS-плагина есть нативный компаньон APK — подсказать установить.
            runCatching {
                val pkg = installed.substringAfterLast('/').substringBefore('@').ifBlank { installed }
                val companion = repository.plugins.bridge()?.nativeCompanionFor(pkg)
                if (companion is com.deepseek.dsh.core.bridge.BridgeResult.Success &&
                    companion.value != null
                ) {
                    onNativeCompanion(companion.value)
                }
            }
            val failure = restartFailure
            if (failure != null) {
                Result.failure(
                    IllegalStateException(
                        context.getString(
                            R.string.plugins_install_restart_failed,
                            failure.message ?: "",
                        ),
                    ),
                )
            } else {
                onProgress(1f, context.getString(R.string.plugins_install_success, installed))
                Result.success(installed)
            }
        },
        onFailure = { error ->
            Result.failure(
                IllegalStateException(
                    error.message ?: context.getString(R.string.plugin_install_failed),
                ),
            )
        },
    )
}
