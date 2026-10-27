package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dsh.core.data.DshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    installer: com.dsh.runtime.host.PluginBundleInstaller,
    onProgress: (Float, String) -> Unit,
    onManagedPackages: (Set<String>) -> Unit,
    onNativeCompanion: (com.dsh.core.bridge.BridgeNativeCompanion?) -> Unit,
): Result<String> {
    // Сторонние плагины из сети — только если пользователь их разрешил (G-48); файл с телефона — его явный выбор.
    if (!isLocalPluginSpec(pkgName) && !com.dsh.runtime.bridge.BridgePolicy.isThirdPartyPluginsAllowed(context)) {
        return Result.failure(ThirdPartyPluginsDisabled(context.getString(R.string.plugins_third_party_off)))
    }
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
                restartFailure = com.dsh.runtime.host.HostRuntimeHolder.restartHost
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
                if (companion is com.dsh.core.bridge.BridgeResult.Success &&
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

/** Установка из сети запрещена настройкой «Сторонние плагины» (G-48). */
internal class ThirdPartyPluginsDisabled(message: String) : IllegalStateException(message)

/** Спецификация — файл на телефоне (папка проекта или распакованный архив), а не адрес в сети. */
internal fun isLocalPluginSpec(spec: String): Boolean = spec.startsWith("/") || spec.startsWith("file:")
