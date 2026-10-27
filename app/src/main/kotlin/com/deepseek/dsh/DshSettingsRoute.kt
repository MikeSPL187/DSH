package com.deepseek.dsh

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deepseek.dsh.core.model.HostDescriptor
import com.deepseek.dsh.feature.runtime.KeyCheck
import com.deepseek.dsh.feature.runtime.OnboardingProvider
import com.deepseek.dsh.feature.settings.ModelProviderUi
import com.deepseek.dsh.feature.settings.HostPermissionPreset
import com.deepseek.dsh.feature.settings.HostTheme
import com.deepseek.dsh.feature.settings.UpdateCenter
import com.deepseek.dsh.feature.settings.logic.ApiKeyVault
import com.deepseek.dsh.feature.settings.logic.BalanceLedger
import com.deepseek.dsh.feature.settings.logic.DshUserPrefs
import com.deepseek.dsh.feature.settings.logic.HostStats
import com.deepseek.dsh.feature.settings.logic.StorageScanner
import com.deepseek.dsh.feature.settings.ui.KeyState
import com.deepseek.dsh.feature.settings.ui.ProviderRow
import com.deepseek.dsh.feature.settings.ui.RemoteHostRow
import com.deepseek.dsh.feature.settings.ui.SettingsActions

import com.deepseek.dsh.feature.settings.ui.SettingsHomeScreen
import com.deepseek.dsh.feature.settings.ui.SettingsUi
import com.deepseek.dsh.feature.settings.ui.StPage
import com.deepseek.dsh.feature.settings.ui.StorageUsage
import com.deepseek.dsh.feature.settings.ui.TrustedPluginRow
import com.deepseek.dsh.core.designsystem.kit.rememberToast
import com.deepseek.dsh.runtime.bridge.BridgePolicy
import com.deepseek.dsh.runtime.host.RuntimePhase
import com.deepseek.dsh.runtime.host.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ссылка на ключ DeepSeek в [ApiKeyVault]. */
internal const val DEEPSEEK_VAULT_REF = "deepseek"

/** Всё, что «Настройкам» нужно от [MainAppHost]. */
internal class SettingsDeps(
    val ui: AppUiState,
    val runtimeUi: AppRuntimeUiState,
    val repository: com.deepseek.dsh.core.data.DshRepository,
    val settingsRepository: com.deepseek.dsh.core.data.DefaultSettingsRepository,
    val runtimeManager: com.deepseek.dsh.runtime.host.HostRuntimeManager,
    val layout: com.deepseek.dsh.runtime.host.RuntimeLayout,
    val backup: com.deepseek.dsh.runtime.host.RuntimeBackupAndDiagnostics,
    val oauthManager: com.deepseek.dsh.core.protocol.DeepSeekOAuthManager,
    val officialNamespaces: Set<String>,
    val modelProviders: ModelProviders,
    val workspace: java.io.File,
    val availableModels: List<String>,
    val remotes: List<HostDescriptor>,
    val pluginsOn: Int,
    val trustedNative: List<TrustedPluginRow>,
    val openAllFilesSettings: () -> Unit,
    val onWorkspaceChanged: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenPlugins: (String?) -> Unit,
    val onOpenAutomation: () -> Unit,
    val automationsOn: Int,
    val automationModel: Pair<String, Int>,
    val onAutomationModel: (String, Int) -> Unit,
    val onClose: () -> Unit,
)

/**
 * «Настройки» по прототипу поверх настоящей логики: ключ и баланс провайдера, тема и язык,
 * правила одобрения Host, буфер обмена моста, Host (старт/стоп, сон, фон), место на телефоне,
 * резервная копия, вход по отпечатку, уведомления и обновления.
 */
@Composable
internal fun DshSettingsRoute(d: SettingsDeps, status: RuntimeStatus?, initialPage: StPage?) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val toast = rememberToast()
    val settings = d.ui.currentAppSettings
    val hostId = d.ui.activeHostId
    var tick by remember { mutableIntStateOf(0) }

    // Ключ, баланс и расход.
    var keyState by remember { mutableStateOf(if (ApiKeyVault.has(DEEPSEEK_VAULT_REF)) KeyState.Unknown else KeyState.None) }
    var balance by remember { mutableStateOf<Pair<Double, String>?>(null) }
    var ledger by remember { mutableStateOf<BalanceLedger.Summary?>(null) }
    LaunchedEffect(tick) {
        val result = ApiKeyVault.use(DEEPSEEK_VAULT_REF) { key -> fetchDeepSeekBalance(key) }
        keyState = when {
            !ApiKeyVault.has(DEEPSEEK_VAULT_REF) -> if (d.oauthManager.accountFlow.value.hasApiKey) KeyState.Unknown else KeyState.None
            result == null -> KeyState.Unknown
            else -> result.first
        }
        balance = result?.second
        balance?.let { (amount, _) -> ledger = withContext(Dispatchers.IO) { BalanceLedger.record(context, amount) } }
    }
    val money = { v: Double -> balance?.let { String.format(Locale.getDefault(), "%.2f %s", v, it.second) } }

    // Провайдеры Host: свой ключ DeepSeek и OpenAI-совместимые.
    val providers = d.modelProviders.list(d.officialNamespaces)
    LaunchedEffect(hostId) {
        d.modelProviders.refresh(hostId)
        HostPermissionPreset.refresh()
        HostTheme.refresh()
    }
    val custom = providers.filter { it.configured && !isDeepSeek(it) }

    // Host: время работы, память и процессор.
    val running = status?.phase == RuntimePhase.RUNNING
    var runningSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) { runningSince = if (running) (status?.updatedAtMillis?.takeIf { it > 0 } ?: System.currentTimeMillis()) else 0L }
    val stats = remember { HostStats() }
    var mem by remember { mutableStateOf<Long?>(null) }
    var cpu by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status?.pid, running) {
        val pid = status?.pid ?: return@LaunchedEffect
        while (running) {
            withContext(Dispatchers.IO) { stats.sample(pid) }?.let { mem = it.rssBytes; cpu = it.cpuPercent }
            now = System.currentTimeMillis()
            delay(3_000)
        }
    }
    val totalRam = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    // Место на телефоне.
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    LaunchedEffect(tick) { storage = withContext(Dispatchers.IO) { StorageScanner.scan(context, d.layout.rootfsDir, d.workspace) } }

    // Рабочая папка и тема — живые значения, без перезапуска экрана.
    var sharedWs by remember { mutableStateOf(!StorageAccess.usesPrivateWorkspace(context)) }
    var clipboard by remember { mutableStateOf(BridgePolicy.isClipboardReadAllowed(context)) }
    val (language, setLanguage) = rememberAppLanguage(d.repository, hostId, scope)
    val update by UpdateCenter.update.collectAsState()
    val currentVersion = remember { UpdateCenter.currentVersion(context) }

    val reasoningLevels = com.deepseek.dsh.feature.chat.ReasoningLevel.entries
    val reasoningLabels = reasoningLevels.map { context.getString(it.labelResId) }
    val reasoningIndex = reasoningLevels.indexOfFirst { it.name == settings.defaultReasoningLevel }.coerceAtLeast(0)

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { d.runtimeManager.stop() }
            val r = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { d.backup.restoreUserDataBackup(it).getOrThrow() } }
            }
            runCatching { d.runtimeManager.start(d.workspace.absolutePath) }
            toast.show(
                r.fold(
                    { context.getString(R.string.settings_restore_done, it) },
                    { context.getString(R.string.settings_restore_failed) },
                ),
            )
        }
    }

    val rootfs = remember(d.runtimeUi.installedRootfsVersion) { com.deepseek.dsh.runtime.host.RootfsVersion.parse(d.runtimeUi.installedRootfsVersion) }
    val distro = remember(tick) {
        runCatching {
            d.layout.rootfsDir.resolve("etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')?.substringBefore(" GNU/Linux")?.replace(" (", "|")?.substringBefore('|')
        }.getOrNull()
    }
    val ui = SettingsUi(
        appVersion = currentVersion,
        appBuild = remember { runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L) },
        hostVersion = rootfs?.dsh,
        sdkVersion = rootfs?.bridge?.let { "bridge $it" },
        distro = distro,
        androidVersion = "Android ${android.os.Build.VERSION.RELEASE}",
        accountName = d.oauthManager.accountFlow.collectAsState().value.profile?.name,
        keyState = keyState,
        keyLast4 = ApiKeyVault.last4(DEEPSEEK_VAULT_REF),
        keyAdded = ApiKeyVault.addedAt(DEEPSEEK_VAULT_REF)?.let { shortDate(it) },
        balance = balance?.let { money(it.first) },
        today = ledger?.let { money(it.today) },
        monthSpent = ledger?.let { money(it.month) },
        monthSpentFraction = ledger?.let { l -> balance?.first?.let { b -> (l.month / (l.month + b)).toFloat() } } ?: 0f,
        balanceDays = ledger?.daysLeft,
        customProviders = custom.map { p ->
            ProviderRow(p.id, p.name, ApiKeyVault.last4(p.keyRef)?.let { context.getString(R.string.settings_key_dots, it) } ?: p.baseUrl.removePrefix("https://"), if (p.keyConfigured) true else null)
        },
        defaultModel = settings.defaultModel.ifBlank { d.availableModels.firstOrNull().orEmpty() },
        reasoning = reasoningLabels.getOrElse(reasoningIndex) { "" },
        automationModel = d.automationModel.let { (m, r) -> listOf(m.ifBlank { d.availableModels.firstOrNull().orEmpty() }, reasoningLabels.getOrElse(r) { "" }).filter { it.isNotBlank() }.joinToString(" · ") },
        models = d.availableModels,
        reasoningLevels = reasoningLabels,
        reasoningIndex = reasoningIndex,
        autoModelName = d.automationModel.first.ifBlank { d.availableModels.firstOrNull().orEmpty() },
        autoReasoningIndex = d.automationModel.second,
        pluginsOn = d.pluginsOn,
        automationsOn = d.automationsOn,
        autoApprove = approveState(),
        clipboard = clipboard,
        workspaceShared = sharedWs,
        theme = HostTheme.preference,
        language = language.takeIf { it in setOf("ru", "en") } ?: Locale.getDefault().language,
        codeSize = DshUserPrefs.codeSize,
        haptics = DshUserPrefs.haptics,
        hostRunning = running,
        hostUptime = if (running && runningSince > 0) uptime(context, now - runningSince) else null,
        hostMemory = mem?.let { com.deepseek.dsh.feature.settings.ui.formatBytes(it, context.resources.getStringArray(com.deepseek.dsh.feature.settings.R.array.st_units)) },
        hostMemoryFraction = mem?.let { (it.toDouble() / totalRam).toFloat() } ?: 0f,
        hostCpu = cpu,
        sleepMinutes = settings.idleStopMinutes,
        keepBg = settings.keepAliveOnTask,
        remotes = d.remotes.filterIsInstance<HostDescriptor.Remote>().map { r ->
            RemoteHostRow(r.id, r.name, r.tlsFingerprint?.let(::shortFingerprint) ?: r.baseUrl.removePrefix("https://"), online = r.id == hostId)
        },
        rootfsUpdated = remember(tick) { d.layout.rootfsDir.resolve("etc/os-release").takeIf { it.exists() }?.lastModified()?.let { shortDate(it) } },
        storage = storage,
        biometricAvailable = BiometricLock.available(context),
        biometric = DshUserPrefs.biometric,
        trusted = d.trustedNative,
        notifDone = settings.notifyOnTurnComplete,
        notifAsk = DshUserPrefs.notifAsk,
        notifAuto = DshUserPrefs.notifAuto,
        updateAvailable = update?.tag?.takeIf { UpdateCenter.hasUpdate(context) },
    )

    fun saveSettings(transform: (com.deepseek.dsh.core.model.AppSettings) -> com.deepseek.dsh.core.model.AppSettings) {
        val next = transform(d.ui.currentAppSettings)
        d.ui.currentAppSettings = next
        scope.launch { d.settingsRepository.updateSettings { next } }
    }

    val actions = object : SettingsActions {
        override fun openPlugins() { d.onOpenPlugins(null) }
        override fun openPlugin(id: String) { d.onOpenPlugins(id) }
        override fun openAutomation() { d.onOpenAutomation() }
        override fun setDefaultModel(model: String, reasoning: Int) = saveSettings {
            it.copy(defaultModel = model, defaultReasoningLevel = reasoningLevels.getOrElse(reasoning) { reasoningLevels.first() }.name)
        }
        override fun setAutomationModel(model: String, reasoning: Int) { d.onAutomationModel(model, reasoning) }

        override suspend fun checkKey(baseUrl: String?, key: String): KeyState = when (
            checkApiKey(if (baseUrl == null) OnboardingProvider.DEEPSEEK else OnboardingProvider.CUSTOM, baseUrl.orEmpty(), key)
        ) {
            is KeyCheck.Ok -> KeyState.Ok
            KeyCheck.Bad -> KeyState.Bad
            KeyCheck.Offline -> KeyState.Offline
        }

        override suspend fun saveKey(key: String): Boolean {
            val provider = d.modelProviders.deepSeek(d.officialNamespaces) ?: run {
                d.modelProviders.refresh(hostId)
                d.modelProviders.deepSeek(d.officialNamespaces)
            } ?: return false
            if (!d.modelProviders.save(hostId, provider, key)) return false
            d.oauthManager.signInWithApiKey(key)
            tick++
            return true
        }

        override suspend fun addProvider(name: String, baseUrl: String, key: String): Boolean {
            val id = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom-api" }
            val models = (checkApiKey(OnboardingProvider.CUSTOM, baseUrl, key) as? KeyCheck.Ok)?.models.orEmpty()
            val provider = ModelProviderUi(
                id = id, name = name, models = models, keyRef = id.uppercase(Locale.ROOT).replace('-', '_') + "_API_KEY",
                keyConfigured = false, settingsNs = "llm-pi-ai", settingsPath = listOf("providers", id),
                baseUrl = baseUrl, declared = false, configured = false,
            )
            return d.modelProviders.save(hostId, provider, key, baseUrl, models)
        }

        override fun manageKeysOnSite() { openWebLink(context, "https://platform.deepseek.com/api_keys") }

        override suspend fun deleteKey(): Boolean {
            d.modelProviders.deleteKey(hostId, providers.firstOrNull(::isDeepSeek))
            withContext(Dispatchers.IO) { BalanceLedger.clear(context) }
            d.oauthManager.signInWithApiKey("")
            balance = null; ledger = null; keyState = KeyState.None
            return true
        }

        override fun setTheme(value: String) { HostTheme.setPreference(value) }
        override fun setLanguage(tag: String) { setLanguage(tag) }
        override fun setCodeSize(size: Int) { DshUserPrefs.codeSize = size }
        override fun setHaptics(on: Boolean) { DshUserPrefs.haptics = on }

        override fun setAutoApprove(auto: Boolean) {
            val options = HostPermissionPreset.options.map { it.value }
            val pick = if (auto) options.firstOrNull { "workspace" in it || ("write" in it && "danger" !in it) }
            else options.firstOrNull { "read" in it || "ask" in it || "default" in it || "untrusted" in it }
            if (pick != null) HostPermissionPreset.set(pick)
        }

        override fun setClipboard(on: Boolean) { BridgePolicy.setClipboardReadAllowed(context, on); clipboard = on }

        override fun setWorkspaceShared(shared: Boolean) {
            if (shared) {
                StorageAccess.chooseSharedWorkspace(context)
                d.openAllFilesSettings()
            } else {
                StorageAccess.choosePrivateWorkspace(context)
            }
            sharedWs = shared
            d.onWorkspaceChanged()
        }

        override suspend fun restartHost(): Boolean {
            val restart = com.deepseek.dsh.runtime.host.HostRuntimeHolder.restartHost
            val ok = if (restart != null) restart().isSuccess else {
                runCatching { d.runtimeManager.stop() }
                d.runtimeManager.start(d.workspace.absolutePath).isSuccess
            }
            runningSince = System.currentTimeMillis()
            return ok
        }

        override suspend fun setHostRunning(run: Boolean): Boolean = withContext(Dispatchers.IO) {
            if (run) d.runtimeManager.start(d.workspace.absolutePath).isSuccess else d.runtimeManager.stop().isSuccess
        }

        override fun setSleep(minutes: Int) { saveSettings { it.copy(idleStopMinutes = minutes) } }
        override fun setKeepBg(on: Boolean) { saveSettings { it.copy(keepAliveOnTask = on) } }
        override fun openTerminal() { d.onOpenTerminal() }

        override fun shareHostLog() {
            scope.launch {
                shareArchive(context, d.backup.exportDiagnosticsZip(), context.getString(R.string.share_diagnostics_title), context.getString(R.string.diagnostics_failed))
            }
        }

        override suspend fun clean(kind: String): Long {
            val freed = withContext(Dispatchers.IO) { StorageScanner.clean(context, kind) }
            tick++
            return freed
        }

        override fun exportSessions() {
            scope.launch {
                shareArchive(context, d.backup.createUserDataBackup(), context.getString(R.string.share_backup_title), context.getString(R.string.backup_failed))
            }
        }

        override fun restoreBackup() { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
        override fun setBiometric(on: Boolean) { BiometricLock.locked = false; DshUserPrefs.biometric = on }

        override suspend fun wipeAll() {
            runCatching { d.runtimeManager.stop() }
            ApiKeyVault.clearAll()
            // Системная очистка данных приложения: сессии, ключи, Linux. Приложение закроется.
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
        }

        override fun setNotifDone(on: Boolean) { saveSettings { it.copy(notifyOnTurnComplete = on) } }
        override fun setNotifAsk(on: Boolean) { DshUserPrefs.notifAsk = on }
        override fun setNotifAuto(value: String) { DshUserPrefs.notifAuto = value }

        override fun openSystemNotifications() {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        override suspend fun checkUpdates(): String? {
            UpdateCenter.check(context)
            return UpdateCenter.update.value?.tag?.takeIf { UpdateCenter.hasUpdate(context) }
        }

        override fun reportProblem() {
            scope.launch {
                shareArchive(context, d.backup.exportDiagnosticsZip(), context.getString(R.string.share_diagnostics_title), context.getString(R.string.diagnostics_failed))
            }
        }

        override fun whatsNew() { openWebLink(context, update?.htmlUrl ?: com.deepseek.dsh.feature.settings.DSH_RELEASES_PAGE) }
        override fun licenses() { openWebLink(context, com.deepseek.dsh.feature.settings.DSH_LICENSES_PAGE) }
    }

    SettingsHomeScreen(ui, onClose = d.onClose, actions = actions, initialPage = initialPage ?: StPage.Root, toast = toast)
}


/** Правило одобрения из пресетов Host: true — правки в папке без вопросов. */
private fun approveState(): Boolean? {
    val current = HostPermissionPreset.current ?: return null
    if (HostPermissionPreset.options.isEmpty()) return null
    return "workspace" in current || ("write" in current && "danger" !in current)
}

private fun shortDate(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val pattern = if (d.year == LocalDate.now().year) "d MMM" else "d MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(d).trimEnd('.')
}

private fun uptime(context: Context, ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60) context.getString(R.string.settings_uptime_hm, m / 60, m % 60) else context.getString(R.string.settings_uptime_m, m.coerceAtLeast(1))
}

private fun shortFingerprint(fp: String): String {
    val parts = fp.uppercase(Locale.ROOT).split(':').filter { it.isNotBlank() }
    return if (parts.size > 4) parts.take(2).joinToString(":") + "…" + parts.last() else fp
}


/** Отпечаток: есть ли на телефоне биометрия (Android 11+, как у экрана блокировки). */
/** Баланс DeepSeek: `GET /user/balance`. Возвращает состояние ключа и (сумма, знак валюты). */
private suspend fun fetchDeepSeekBalance(key: String): Pair<KeyState, Pair<Double, String>?> = withContext(Dispatchers.IO) {
    runCatching {
        val c = (java.net.URL("https://api.deepseek.com/user/balance").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            when (c.responseCode) {
                401, 403 -> KeyState.Bad to null
                in 200..299 -> {
                    val info = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("balance_infos").getJSONObject(0)
                    val sign = when (info.optString("currency")) { "USD" -> "$"; "CNY" -> "¥"; else -> info.optString("currency") }
                    KeyState.Ok to (info.getString("total_balance").toDouble() to sign)
                }
                else -> KeyState.Offline to null
            }
        } finally { c.disconnect() }
    }.getOrElse { KeyState.Offline to null }
}
