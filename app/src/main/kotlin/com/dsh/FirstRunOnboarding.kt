package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.dsh.feature.runtime.KeyCheck
import com.dsh.feature.runtime.LinuxInstall
import com.dsh.feature.runtime.OnboardingKey
import com.dsh.feature.runtime.OnboardingProvider
import com.dsh.feature.runtime.OnboardingScreen
import com.dsh.runtime.host.HostRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Первый запуск: экран из прототипа поверх настоящей логики.
 *
 * Linux ставится в фоне с первой секунды (эффект установки в [MainAppHost] работает
 * независимо от экрана). Host стартует после выбора папки на шаге 2. «Начать работу»
 * доступна, когда Host отвечает; тогда же ключ записывается в Host.
 */
@Composable
internal fun FirstRunOnboarding(
    runtimeUi: AppRuntimeUiState,
    ui: AppUiState,
    scope: CoroutineScope,
    dshRepository: com.dsh.core.data.DshRepository,
    runtimeManager: HostRuntimeManager,
    activeWorkspace: java.io.File,
    modelProviders: ModelProviders,
    onboardingPreferences: com.dsh.feature.runtime.OnboardingPreferences,
    settingsRepository: com.dsh.core.data.DefaultSettingsRepository,
    oauthManager: com.dsh.core.protocol.DeepSeekOAuthManager,
    openAllFilesSettings: () -> Unit,
    onWorkspaceChosen: () -> Unit,
    backup: com.dsh.runtime.host.RuntimeBackupAndDiagnostics? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notify by rememberSaveable { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }

    val install = rememberFriendlyLinuxInstall(runtimeUi)

    // «Сообщить о готовности»: одно уведомление, если в этот момент приложение свёрнуто.
    LaunchedEffect(install.done) {
        if (install.done && notify && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            DshNotifications.notify(
                context,
                context.getString(com.dsh.feature.runtime.R.string.ob_notify_title),
                context.getString(com.dsh.feature.runtime.R.string.ob_notify_text),
            )
        }
    }

    val language = remember {
        AppLanguage.selection(context).takeIf { it != "system" }
            ?: java.util.Locale.getDefault().language.takeIf { it in setOf("ru", "en") }
            ?: "en"
    }

    // «Восстановить из резервной копии»: та же копия, что делают Настройки (без ключей).
    val toast = com.dsh.core.designsystem.kit.rememberToast()
    var restoring by remember { mutableStateOf(false) }
    // Восстановление в два шага (G-05): распаковать и показать, что заменится (и чья копия), затем перенести.
    var restorePlan by remember { mutableStateOf<com.dsh.runtime.host.RestorePlan?>(null) }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || backup == null || restoring) return@rememberLauncherForActivityResult
        restoring = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { backup.prepareRestore(it).getOrThrow() } }
            }.onSuccess { restorePlan = it }.onFailure { toast.show(context.getString(R.string.ob_restore_failed)) }
            restoring = false
        }
    }
    val plan = restorePlan
    if (plan != null && backup != null) {
        RestoreConfirmDialog(
            plan = plan,
            onCancel = {
                restorePlan = null
                backup.discardRestore(plan)
            },
            onConfirm = {
                restorePlan = null
                restoring = true
                scope.launch {
                    val wasRunning = runCatching { runtimeManager.getStatus().phase == com.dsh.runtime.host.RuntimePhase.RUNNING }.getOrDefault(false)
                    if (wasRunning) runCatching { runtimeManager.stop() }
                    val r = backup.applyRestore(plan)
                    if (wasRunning) withContext(Dispatchers.IO) { runCatching { runtimeManager.start(activeWorkspace.absolutePath) } }
                    restoring = false
                    toast.show(
                        r.fold(
                            { context.getString(R.string.ob_restore_done, it) },
                            { context.getString(R.string.settings_restore_apply_failed) },
                        ),
                    )
                }
            },
        )
    }

    androidx.compose.foundation.layout.Box {
    OnboardingScreen(
        onRestoreBackup = if (backup != null) ({ restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) else null,
        install = install,
        sharedAccessGranted = ui.hasAllFilesAccess && !StorageAccess.usesPrivateWorkspace(context),
        checkKey = { provider, baseUrl, key -> checkApiKey(provider, baseUrl, key) },
        onWhereKey = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://platform.deepseek.com/api_keys"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onChoosePrivate = {
            if (!StorageAccess.usesPrivateWorkspace(context)) {
                StorageAccess.choosePrivateWorkspace(context)
                onWorkspaceChosen()
            }
            ui.hasAllFilesAccess = true
        },
        onRequestShared = openAllFilesSettings,
        onRetryInstall = {
            if (runtimeUi.rootfsSetupError != null) {
                runtimeUi.rootfsInstallAttempt++
            } else {
                runtimeUi.hostStartupError = false
                runtimeUi.hostStartupDetail = null
                runtimeUi.hostRetryKey += 1
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { runtimeManager.start(activeWorkspace.absolutePath) } }
                }
            }
        },
        notify = notify,
        onNotifyChange = { notify = it },
        language = language,
        onLanguage = { tag -> AppLanguage.apply(context, tag) },
        onFinish = { key ->
            if (finishing) return@OnboardingScreen
            finishing = true
            scope.launch {
                if (key != null) {
                    applyKey(key, ui.activeHostId, modelProviders, dshRepository, oauthManager, context)
                }
                onboardingPreferences.markCompleted()
                // Первый запуск включает вид работы с кодом и обычный пресет агента.
                val updated = ui.currentAppSettings.copy(showCodingView = true, transcriptView = "standard")
                ui.currentAppSettings = updated
                settingsRepository.updateSettings { updated }
                runtimeUi.showOnboarding = false
            }
        },
    )
    com.dsh.core.designsystem.kit.ToastHost(toast)
    }
}

/** Ход установки с понятной причиной ошибки и технической подробностью. */
@Composable
internal fun rememberFriendlyLinuxInstall(runtimeUi: AppRuntimeUiState): LinuxInstall {
    val context = LocalContext.current
    val s = rememberLinuxInstall(runtimeUi)
    val raw = s.error ?: return s
    return s.copy(error = context.getString(installErrorText(raw)), errorDetail = raw)
}

/** Понятная причина сбоя установки по тексту исключения. */
private fun installErrorText(raw: String): Int {
    val t = raw.lowercase()
    return when {
        "cert" in t || "ssl" in t || "tls" in t || "handshake" in t -> com.dsh.feature.runtime.R.string.ob_err_tls
        "unknownhost" in t || "unable to resolve" in t || "timeout" in t || "timed out" in t ||
            "network" in t || "connect" in t || "offline" in t || "нет сети" in t -> com.dsh.feature.runtime.R.string.ob_err_offline
        "enospc" in t || "no space" in t || "space left" in t -> com.dsh.feature.runtime.R.string.ob_err_space
        else -> com.dsh.feature.runtime.R.string.ob_err_generic
    }
}

/**
 * Ход установки для шага 3 из настоящего прогресса rootfs и запуска Host.
 *
 * Установщик сообщает: загрузка 2–50 %, проверка SHA-256 52 %, распаковка 55–85 %,
 * донастройка до 100 %. На экране: «Загрузка», «Распаковка» (вместе с донастройкой)
 * и «Проверка» — запуск Host и ответ на первый запрос.
 */
@Composable
private fun rememberLinuxInstall(runtimeUi: AppRuntimeUiState): LinuxInstall {
    val (p, _) = runtimeUi.rootfsSetupProgress
    val rootfsDone = runtimeUi.rootfsReady == true
    val hostDone = runtimeUi.hostProgressReady
    val rootfs = if (rootfsDone) 1f else p.coerceIn(0f, 1f)
    val progress = if (hostDone && rootfsDone) 1f else rootfs * 0.92f
    val stage = when {
        rootfsDone -> 2
        rootfs < 0.51f -> 0
        else -> 1
    }
    val error = runtimeUi.rootfsSetupError ?: if (rootfsDone && runtimeUi.hostStartupError) {
        runtimeUi.hostStartupDetail ?: "Host"
    } else null

    // Оставшееся время — по скорости с начала установки.
    var startedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(progress > 0.02f) { if (progress > 0.02f && startedAt == 0L) startedAt = System.currentTimeMillis() }
    val eta = if (startedAt > 0 && progress in 0.05f..0.99f) {
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000f
        (elapsed / progress * (1f - progress)).toInt()
    } else null

    return LinuxInstall(
        progress = progress,
        stage = stage,
        downloadPercent = (((rootfs - 0.02f) / 0.48f) * 100).toInt().coerceIn(0, 100),
        unpackPercent = (((rootfs - 0.52f) / 0.48f) * 100).toInt().coerceIn(0, 100),
        etaSeconds = eta,
        done = rootfsDone && hostDone,
        error = error,
    )
}

private val keyClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

/**
 * Проверка ключа прямо у провайдера, до запуска Host.
 * DeepSeek — `GET /user/balance` (заодно остаток), свой API — `GET {base}/models`.
 * Ключ никуда не сохраняется и не логируется.
 */
internal suspend fun checkApiKey(provider: OnboardingProvider, baseUrl: String, key: String): KeyCheck =
    withContext(Dispatchers.IO) {
        val url = when (provider) {
            OnboardingProvider.DEEPSEEK -> "https://api.deepseek.com/user/balance"
            OnboardingProvider.CUSTOM -> baseUrl.trimEnd('/') + "/models"
        }
        if (!url.startsWith("https://")) return@withContext KeyCheck.Bad
        val request = runCatching {
            Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
        }.getOrNull() ?: return@withContext KeyCheck.Bad
        try {
            keyClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> KeyCheck.Bad
                    !response.isSuccessful -> if (response.code >= 500) KeyCheck.Offline else KeyCheck.Bad
                    else -> {
                        val body = response.body?.string().orEmpty()
                        when (provider) {
                            OnboardingProvider.DEEPSEEK -> KeyCheck.Ok(balance = parseBalance(body))
                            OnboardingProvider.CUSTOM -> KeyCheck.Ok(models = parseModels(body))
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            KeyCheck.Offline
        }
    }

/** `balance_infos[0]` → «12,40 $» / «85,00 ¥» в формате текущего языка. */
internal fun parseBalance(body: String): String? = runCatching {
    val info = JSONObject(body).getJSONArray("balance_infos").getJSONObject(0)
    val amount = info.getString("total_balance").toDouble()
    val sign = when (info.optString("currency")) {
        "USD" -> "$"
        "CNY" -> "¥"
        else -> info.optString("currency")
    }
    String.format(java.util.Locale.getDefault(), "%.2f %s", amount, sign)
}.getOrNull()

/** Список моделей OpenAI-совместимого `/models`. */
internal fun parseModels(body: String): List<String> = runCatching {
    val data = JSONObject(body).getJSONArray("data")
    (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf { id -> id.isNotBlank() } }
}.getOrDefault(emptyList())

/** Записывает ключ в Host тем же путём, что «Настройки → Модели», и обновляет каталог моделей. */
private suspend fun applyKey(
    key: OnboardingKey,
    hostId: String,
    modelProviders: ModelProviders,
    dshRepository: com.dsh.core.data.DshRepository,
    oauthManager: com.dsh.core.protocol.DeepSeekOAuthManager,
    context: android.content.Context,
) {
    modelProviders.refresh(hostId)
    val provider = when (key.provider) {
        OnboardingProvider.DEEPSEEK -> modelProviders.deepSeek(dshRepository.plugins.officialSettingsNamespaces())
        OnboardingProvider.CUSTOM -> com.dsh.feature.settings.ModelProviderUi(
            id = "custom-api",
            name = "custom-api",
            models = key.models,
            keyRef = "CUSTOM_API_API_KEY",
            keyConfigured = false,
            settingsNs = "llm-pi-ai",
            settingsPath = listOf("providers", "custom-api"),
            baseUrl = key.baseUrl,
            declared = false,
            configured = false,
        )
    }
    if (provider == null) {
        android.widget.Toast.makeText(context, context.getString(R.string.onboarding_key_failed), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val baseUrl = if (key.provider == OnboardingProvider.CUSTOM) key.baseUrl else ""
    if (!modelProviders.save(hostId, provider, key.key, baseUrl, key.models)) {
        android.widget.Toast.makeText(context, context.getString(R.string.onboarding_key_failed), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    if (key.provider == OnboardingProvider.DEEPSEEK) oauthManager.signInWithApiKey(key.key)
}
