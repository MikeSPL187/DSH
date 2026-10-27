package com.dsh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dsh.plugin.nativehost.InstalledNativePlugin
import com.dsh.plugin.nativehost.NativePageRegistration
import com.dsh.plugin.nativehost.NativePageRegistry
import com.dsh.plugin.nativehost.NativePluginDiscovery
import com.dsh.plugin.nativehost.NativePluginItem
import com.dsh.plugin.nativehost.NativePluginLoader
import com.dsh.plugin.nativehost.NativePluginPairing
import com.dsh.plugin.nativehost.NativePluginTrustStore
import com.deepseek.dsh.plugin.nativesdk.pages.PageRegistry
import com.dsh.runtime.host.PluginBundleInstaller
import com.dsh.runtime.host.RuntimeLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Нативные плагины-компаньоны (APK): поиск в системе, доверие, загрузка в процесс DSH
 * и очередь удаления APK.
 *
 * Безопасность: код компаньона работает в процессе DSH со всеми его правами, поэтому
 * загружаются только плагины, которым пользователь доверился (пакет + отпечаток сертификата).
 * Новые и сменившие подпись попадают в [pendingApprovals]. Парный JS-плагин ставится только
 * в момент согласия; повторный поиск никогда сам ничего не скачивает.
 */
class NativePluginsController internal constructor(
    private val context: Context,
    private val runtimeUi: AppRuntimeUiState,
    private val layout: RuntimeLayout,
    private val installer: PluginBundleInstaller,
    private val scope: CoroutineScope,
) {
    val trustStore = NativePluginTrustStore(context)

    /** Плагины, которые ждут решения «Доверять?». */
    val pendingApprovals = mutableStateListOf<InstalledNativePlugin>()

    /** «Не сейчас» в этом запуске: пакет/отпечаток. После перезапуска DSH спросит снова. */
    private val dismissed = mutableStateListOf<String>()

    /** Удаление APK по одному: системное окно удаления показывает только одно за раз. */
    internal val pendingApkUninstalls = mutableStateListOf<String>()

    internal var scanTick by mutableIntStateOf(0)
        private set

    /** Пересканировать (после доверия, включения или выключения). */
    fun rescan() { scanTick += 1 }

    fun requestApkUninstall(packageName: String?) {
        val target = packageName?.takeIf { it.isNotBlank() } ?: return
        if (target !in pendingApkUninstalls) pendingApkUninstalls.add(target)
    }

    /** Поставить в очередь удаления все APK, объявившие парой этот JS-плагин (`dsh.plugin.jsPackage`). */
    fun uninstallCompanionsOf(jsPackageName: String) {
        val normalized = jsPackageName.substringAfterLast('/').substringBefore('@')
        runtimeUi.nativePluginItems
            .filter { it.installed && it.packageName != null && it.jsPackage != null }
            .filter { item ->
                val declared = item.jsPackage!!
                declared == jsPackageName || declared.substringAfterLast('/').substringBefore('@') == normalized
            }
            .forEach { item -> requestApkUninstall(item.packageName) }
    }

    fun dismiss(candidate: InstalledNativePlugin) {
        dismissed.add("${candidate.packageName}/${candidate.signatureFingerprint}")
        pendingApprovals.remove(candidate)
    }

    /** Довериться: запомнить подпись, поставить парный JS-плагин (если его нет) и пересканировать. */
    fun trust(candidate: InstalledNativePlugin) {
        val jsPackage = NativePluginPairing.resolveJsPackage(candidate, storeLookup = null)
        trustStore.trust(candidate)
        com.dsh.feature.settings.logic.NativePluginPrefs.markTrusted(context, candidate.pluginId)
        pendingApprovals.remove(candidate)
        scope.launch(Dispatchers.IO) {
            if (jsPackage != null) runCatching {
                val pkgName = jsPackage.substringAfterLast('/').substringBefore('@').ifBlank { jsPackage }
                val inProfile = java.io.File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules/$pkgName/package.json").isFile
                val managed = runtimeUi.managedPluginPackages.any { it.equals(pkgName, ignoreCase = true) || it.equals(jsPackage, ignoreCase = true) }
                if (!inProfile && !managed && installer.install(jsPackage) { _, _ -> }.isSuccess) {
                    com.dsh.runtime.host.HostRuntimeHolder.restartHost?.invoke()
                }
            }
            withContext(Dispatchers.Main) { rescan() }
        }
    }

    /** Найти компаньоны, загрузить доверенные и включённые, обновить список «Плагинов». */
    internal suspend fun scan() {
        val result = withContext(Dispatchers.IO) { runCatching { discoverAndLoad() }.getOrNull() } ?: return
        val (discovered, awaiting) = result
        pendingApprovals.clear()
        pendingApprovals.addAll(awaiting.filter { "${it.packageName}/${it.signatureFingerprint}" !in dismissed })
        runtimeUi.nativeDiscovered = discovered
        runtimeUi.nativePluginItems = discovered.map(::itemOf)
    }

    private fun discoverAndLoad(): Pair<List<InstalledNativePlugin>, List<InstalledNativePlugin>> {
        val loader = NativePluginLoader(context)
        val appVersionCode = context.packageManager.getPackageInfo(context.packageName, 0)
            .let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toInt() }
        val discovered = NativePluginDiscovery(context).scan(
            appVersionCode = appVersionCode,
            supportedApiVersion = com.dsh.plugin.nativehost.NativeAbi.CURRENT,
            pins = trustStore.pins(),
        )
        // Выключенный в «Плагинах» нативный плагин в процесс DSH не загружается.
        val ready = discovered.filter {
            it.status == InstalledNativePlugin.Status.READY &&
                !com.dsh.feature.settings.logic.NativePluginPrefs.isDisabled(context, it.pluginId)
        }
        val awaiting = discovered.filter {
            it.status == InstalledNativePlugin.Status.UNTRUSTED || it.status == InstalledNativePlugin.Status.SIGNATURE_MISMATCH
        }
        // Страницы удалённых пакетов снимаем до загрузки: открытая страница не должна
        // исполнять код из уже удалённого ClassLoader.
        val readyIds = ready.map { it.pluginId }.toSet()
        PageRegistry.snapshot().map { it.first }.filter { it.isNotBlank() && it !in readyIds }.forEach { PageRegistry.removeOwner(it) }
        val loaded = ready.mapNotNull { installed ->
            PageRegistry.removeOwner(installed.pluginId)
            PageRegistry.withOwner(installed.pluginId) { loader.load(installed) }?.let { plugin -> installed to plugin }
        }
        // Регистрируются все страницы, которые компаньон загрузил; раздел настроек
        // подбирается по id, названию или один к одному.
        val registrations = loaded.map { (installed, plugin) ->
            NativePageRegistration(
                pluginId = plugin.descriptor.id,
                jsPackage = installed.jsPackage,
                pages = PageRegistry.snapshot().filter { (owner, _) -> owner == installed.pluginId }.map { (_, page) -> page },
            )
        }
        NativePageRegistry.replaceAll(registrations)
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "NativePluginHost",
                "loaded companions=" + registrations.joinToString { r -> "${r.pluginId}/${r.jsPackage}=" + r.pages.joinToString { p -> "${p.entry.placement}:${p.entry.id}" } },
            )
        }
        return discovered to awaiting
    }

    private fun itemOf(installed: InstalledNativePlugin) = NativePluginItem(
        pluginId = installed.pluginId,
        name = installed.pluginId.substringAfterLast('.'),
        description = context.getString(R.string.plugin_native_description, installed.packageName),
        version = installed.version.toString(),
        capabilities = emptyList(),
        installed = true,
        statusLabel = context.getString(
            when (installed.status) {
                InstalledNativePlugin.Status.READY -> R.string.plugin_status_installed
                InstalledNativePlugin.Status.UNTRUSTED -> R.string.plugin_status_needs_approval
                InstalledNativePlugin.Status.ABI_TOO_NEW -> R.string.plugin_status_update_plugin
                InstalledNativePlugin.Status.APP_TOO_OLD -> R.string.plugin_status_update_app
                InstalledNativePlugin.Status.SIGNATURE_MISMATCH -> R.string.plugin_status_identity_mismatch
                InstalledNativePlugin.Status.DISABLED -> R.string.plugin_status_disabled
            },
        ),
        signatureFingerprint = installed.signatureFingerprint,
        jsPackage = installed.jsPackage,
        packageName = installed.packageName,
        lastUpdateTime = installed.lastUpdateTime,
    )
}

/**
 * Компаньоны, привязанные к экрану: поиск при открытии, при возврате в DSH ([rescanSignal] —
 * после установки или удаления APK) и после [NativePluginsController.rescan]; очередь удаления APK.
 */
@Composable
internal fun rememberNativePlugins(
    runtimeUi: AppRuntimeUiState,
    layout: RuntimeLayout,
    installer: PluginBundleInstaller,
    rescanSignal: StateFlow<Int>,
    scope: CoroutineScope,
): NativePluginsController {
    val context = LocalContext.current
    val controller = remember(layout, installer) { NativePluginsController(context, runtimeUi, layout, installer, scope) }
    val signal by rescanSignal.collectAsState()
    LaunchedEffect(signal, controller.scanTick) { controller.scan() }
    LaunchedEffect(signal, controller.pendingApkUninstalls.size) {
        val next = controller.pendingApkUninstalls.firstOrNull() ?: return@LaunchedEffect
        controller.pendingApkUninstalls.removeAt(0)
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$next"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    return controller
}
