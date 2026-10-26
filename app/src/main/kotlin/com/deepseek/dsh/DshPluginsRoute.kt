package com.deepseek.dsh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.deepseek.dsh.core.bridge.BridgePluginBundleSummary
import com.deepseek.dsh.core.data.MarketEntry
import com.deepseek.dsh.core.model.PluginSettingsDocument
import com.deepseek.dsh.core.model.PluginSettingsPage
import com.deepseek.dsh.core.model.PluginSlotEntry
import com.deepseek.dsh.feature.settings.logic.NativePluginPrefs
import com.deepseek.dsh.feature.settings.logic.StorageScanner
import com.deepseek.dsh.feature.settings.logic.ToolCallLog
import com.deepseek.dsh.feature.settings.logic.ToolPolicy
import com.deepseek.dsh.feature.settings.ui.CatalogUi
import com.deepseek.dsh.feature.settings.ui.PluginCall
import com.deepseek.dsh.feature.settings.ui.PluginPerm
import com.deepseek.dsh.feature.settings.ui.PluginSetting
import com.deepseek.dsh.feature.settings.ui.PluginTool
import com.deepseek.dsh.feature.settings.ui.PluginUi
import com.deepseek.dsh.feature.settings.ui.PluginsActions
import com.deepseek.dsh.feature.settings.ui.PluginsHomeScreen
import com.deepseek.dsh.feature.settings.ui.ToolMode
import com.deepseek.dsh.feature.settings.ui.TrustCandidate
import com.deepseek.dsh.core.designsystem.kit.rememberToast
import com.deepseek.dsh.plugin.nativehost.InstalledNativePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.util.Locale

/** Что «Плагинам» нужно от [MainAppHost]: данные и действия над Host и APK. */
internal class PluginDeps(
    val bundles: List<BridgePluginBundleSummary>,
    val natives: List<InstalledNativePlugin>,
    val slotEntries: List<PluginSlotEntry>,
    val settingsPages: List<PluginSettingsPage>,
    val rootfsDir: java.io.File,
    /** Папка, которую установщик видит как `/cache`. */
    val installCacheDir: java.io.File,
    val pins: () -> Map<String, com.deepseek.dsh.plugin.nativehost.NativePluginPin>,
    val loadSettings: suspend (String) -> PluginSettingsDocument?,
    val saveSettings: suspend (String, JsonObject, Int) -> Boolean,
    val marketIndexes: suspend () -> Result<List<com.deepseek.dsh.core.data.MarketIndexSummary>>,
    val marketSearch: suspend (String, String) -> Result<com.deepseek.dsh.core.data.MarketListing?>,
    val toggleBundle: suspend (String, Boolean) -> Boolean,
    val install: suspend (String) -> Result<String>,
    val uninstallBundle: suspend (String) -> Unit,
    val uninstallApk: (String) -> Unit,
    val onNativeChanged: () -> Unit,
    val trustCandidate: InstalledNativePlugin?,
    val onTrust: (InstalledNativePlugin) -> Unit,
    val onDismissTrust: (InstalledNativePlugin) -> Unit,
    val onAsk: (String) -> Unit,
    val onCreateWithAgent: () -> Unit,
    val onOpenSource: (java.io.File) -> Unit,
    val onOpenPluginUi: (String) -> Unit,
    val speech: SpeechHooks?,
    val onClose: () -> Unit,
)

private fun bundleDir(rootfs: java.io.File, name: String) = java.io.File(rootfs, "root/.dsh/profiles/web/node_modules/$name")

/** Буква и оттенок иконки — по имени, чтобы у плагина всегда был свой цвет. */
internal fun pluginHue(id: String): Float = ((id.hashCode().toLong() and 0x7fffffff) % 360).toFloat()

internal fun pluginLetter(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/**
 * «Плагины» по прототипу поверх Host и нативных APK: включение, удаление, обновления и
 * рекомендации из маркета, настройки по Config-схеме, журнал вызовов и режимы инструментов
 * (применяются к запросам одобрения), доверие к подписи нативного плагина.
 */
@Composable
internal fun DshPluginsRoute(d: PluginDeps) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var tick by remember { mutableIntStateOf(0) }

    // Маркет: рекомендации и доступные обновления.
    var market by remember { mutableStateOf<List<MarketEntry>>(emptyList()) }
    LaunchedEffect(tick) {
        val urls = d.marketIndexes().getOrNull().orEmpty().filter { it.error == null }.map { it.url }
        market = urls.flatMap { url ->
            d.marketSearch(url, """{"sort":{"kind":"index"},"offset":0,"limit":40}""").getOrNull()?.entries.orEmpty()
        }.distinctBy { it.name }
    }

    // Настройки плагинов (Config-схема Host): документы по модулю.
    val docs = remember { mutableStateMapOf<String, PluginSettingsDocument>() }
    LaunchedEffect(d.settingsPages, tick) {
        d.settingsPages.forEach { page -> d.loadSettings(page.id)?.let { docs[page.id] = it } }
    }

    // Размеры папок Host-плагинов.
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(d.bundles.map { it.name }) {
        sizes = withContext(Dispatchers.IO) {
            d.bundles.associate { b -> b.name to StorageScanner.size(bundleDir(d.rootfsDir, b.name)) }.filterValues { it > 0 }
        }
    }

    val log = ToolCallLog.entries
    val policy = ToolPolicy.modes
    val units = context.resources.getStringArray(com.deepseek.dsh.feature.settings.R.array.st_units)
    val pm = context.packageManager
    val pins = remember(d.natives) { d.pins() }

    fun toolsAndLog(pkg: String, title: String): Triple<List<PluginTool>, List<PluginCall>, List<ToolCallLog.Entry>> {
        val mine = log.filter { ToolCallLog.belongs(it.tool, pkg, title) }
        val tools = mine.map { it.tool }.distinct().sorted().map { name ->
            val asked = mine.any { it.tool == name && (it.status == "ask" || it.status == "deny") }
            val mode = when (policy[name]) { "auto" -> ToolMode.Auto; "off" -> ToolMode.Off; "ask" -> ToolMode.Ask; else -> if (asked) ToolMode.Ask else ToolMode.Auto }
            PluginTool(name, null, mode)
        }
        val calls = mine.take(50).map { e -> PluginCall(e.tool, callTime(context, e.at), e.durationMs?.let { durationLabel(context, it) } ?: "—", if (e.status == "deny") "ask" else e.status, callResult(context, e)) }
        return Triple(tools, calls, mine)
    }

    fun stats(mine: List<ToolCallLog.Entry>): Triple<Int, Int, Pair<String, String>?> {
        val week = System.currentTimeMillis() - 7 * 86_400_000L
        val recent = mine.filter { it.at >= week && (it.status == "ok" || it.status == "err") }
        val last = mine.firstOrNull { it.status == "ok" || it.status == "err" }?.let { e ->
            DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(e.at)) to dayLabel(context, e.at)
        }
        return Triple(recent.size, recent.count { it.status == "err" }, last)
    }

    fun settingsFor(module: String): List<PluginSetting> = d.settingsPages.filter { it.module == module || it.id == module }.flatMap { page ->
        val doc = docs[page.id] ?: return@flatMap emptyList()
        val form = parseSettingsForm(doc.schema) ?: return@flatMap emptyList()
        form.dict.mapNotNull { (key, node) -> settingOf(page.id, key, node, doc.value[key]) }
    }

    val hosts = d.bundles.filter { !it.hidden }.map { b ->
        val (tools, calls, mine) = toolsAndLog(b.name, b.title)
        val (callsWeek, errs, last) = stats(mine)
        val m = market.firstOrNull { it.name == b.name }
        val update = m?.takeIf { it.installationStatus == "update-available" }?.presentedVersion
        val dir = bundleDir(d.rootfsDir, b.name)
        PluginUi(
            id = b.name, name = b.title.ifBlank { b.name.substringAfterLast('/') }, letter = pluginLetter(b.title.ifBlank { b.name.substringAfterLast('/') }),
            hue = pluginHue(b.name), native = false, version = b.version ?: "—", on = b.enabled,
            update = update?.version, changelog = null,
            by = if (b.official) "DSH Team" else (m?.author ?: b.name.substringBefore('/').removePrefix("@").takeIf { b.name.startsWith("@") }),
            size = sizes[b.name]?.let { com.deepseek.dsh.feature.settings.ui.formatBytes(it, units) },
            description = b.description.orEmpty().ifBlank { m?.description.orEmpty() },
            compat = update?.requirement ?: m?.presentedVersion?.requirement,
            compatible = update?.compatible ?: true,
            tools = tools, settings = settingsFor(b.name),
            hasUi = d.slotEntries.any { it.module == b.name && it.slot == "settings.section" },
            calls = callsWeek, errors = errs, last = last, log = calls,
            removable = b.removable && !b.locked,
            error = b.errorCode?.let { context.getString(R.string.plr_error_code, it) },
            hasSource = dir.isDirectory,
        )
    }

    val natives = d.natives.map { n ->
        val info = runCatching { pm.getPackageInfo(n.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        val label = info?.applicationInfo?.loadLabel(pm)?.toString() ?: n.pluginId.substringAfterLast('.')
        val (tools, calls, mine) = toolsAndLog(n.pluginId, label)
        val (callsWeek, errs, last) = stats(mine)
        val blocked = n.status == InstalledNativePlugin.Status.SIGNATURE_MISMATCH
        val pin = pins[n.pluginId]
        val apkSize = info?.applicationInfo?.sourceDir?.let { java.io.File(it).length() }
        PluginUi(
            id = "apk:" + n.pluginId, name = label, letter = pluginLetter(label), hue = pluginHue(n.pluginId), native = true,
            version = info?.versionName ?: n.version.toString(),
            on = n.status == InstalledNativePlugin.Status.READY && !NativePluginPrefs.isDisabled(context, n.pluginId),
            blocked = blocked,
            by = publisher(context, n.packageName), size = apkSize?.let { com.deepseek.dsh.feature.settings.ui.formatBytes(it, units) },
            description = context.getString(R.string.plugin_native_description, n.packageName),
            compat = when (n.status) {
                InstalledNativePlugin.Status.ABI_TOO_NEW -> context.getString(R.string.plugin_status_update_plugin)
                InstalledNativePlugin.Status.APP_TOO_OLD -> context.getString(R.string.plugin_status_update_app)
                else -> "SDK ${n.apiVersion}"
            },
            compatible = n.status != InstalledNativePlugin.Status.ABI_TOO_NEW && n.status != InstalledNativePlugin.Status.APP_TOO_OLD,
            tools = tools, perms = permsOf(context, info),
            pkg = n.packageName, cert = shortCert(n.signatureFingerprint), certWas = pin?.fingerprint?.let(::shortCert),
            trustedAt = NativePluginPrefs.trustedAt(context, n.pluginId)?.let(::shortDay),
            hasUi = com.deepseek.dsh.plugin.nativehost.NativePageRegistry.registrations.value.any { it.pluginId == n.pluginId && it.pages.isNotEmpty() },
            calls = callsWeek, errors = errs, last = last, log = calls,
        )
    }
    // Порядок как в прототипе: заблокированные — в конце списка установленных.
    val plugins = (hosts + natives).sortedBy { it.blocked }

    val catalog = market.filter { e -> e.installationStatus == "not-installed" && d.bundles.none { it.name == e.name } }.take(6).map { e ->
        CatalogUi(e.name, e.title.ifBlank { e.name }, pluginLetter(e.title.ifBlank { e.name }), pluginHue(e.name), e.description.orEmpty())
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { installFromFile(context, uri, d, toast) }
    }

    val actions = object : PluginsActions {
        override fun toggle(id: String, on: Boolean) {
            if (id.startsWith("apk:")) {
                NativePluginPrefs.setDisabled(context, id.removePrefix("apk:"), !on)
                d.onNativeChanged()
            } else scope.launch { d.toggleBundle(id, on) }
        }

        override fun setToolMode(id: String, tool: String, mode: ToolMode) {
            ToolPolicy.set(tool, when (mode) { ToolMode.Auto -> "auto"; ToolMode.Ask -> "ask"; ToolMode.Off -> "off" })
        }

        override fun setSetting(id: String, key: String, value: Int) {
            val (ns, field) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val doc = docs[ns] ?: return
            val node = parseSettingsForm(doc.schema)?.dict?.firstOrNull { it.first == field }?.second ?: return
            val v = node.list.getOrNull(value)?.constValue ?: return
            scope.launch { if (d.saveSettings(ns, JsonObject(mapOf(field to v)), doc.revision)) d.loadSettings(ns)?.let { docs[ns] = it } }
        }

        override fun setSettingToggle(id: String, key: String, on: Boolean) {
            val (ns, field) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val doc = docs[ns] ?: return
            scope.launch { if (d.saveSettings(ns, JsonObject(mapOf(field to JsonPrimitive(on))), doc.revision)) d.loadSettings(ns)?.let { docs[ns] = it } }
        }

        override fun open(id: String) = d.onOpenPluginUi(id)
        override fun ask(id: String) { plugins.find { it.id == id }?.let { d.onAsk(context.getString(R.string.plr_ask_prefill, it.name)) } }

        override fun fixWithAgent(id: String, call: PluginCall?) {
            val p = plugins.find { it.id == id } ?: return
            d.onAsk(if (call != null) context.getString(R.string.plr_fix_prefill, call.tool, call.result) else context.getString(R.string.plr_ask_prefill, p.name))
        }

        override fun openSource(id: String) = d.onOpenSource(bundleDir(d.rootfsDir, id))

        override suspend fun restart(id: String): Boolean = d.toggleBundle(id, false) && d.toggleBundle(id, true)

        override suspend fun update(id: String): Boolean {
            val spec = market.firstOrNull { it.name == id }?.presentedVersion?.spec ?: return false
            return d.install(spec).isSuccess.also { tick++ }
        }

        override suspend fun updateAll(): List<String> = plugins.filter { it.update != null }.filter { update(it.id) }.map { it.name }

        override suspend fun uninstall(id: String): Boolean {
            if (id.startsWith("apk:")) {
                d.natives.firstOrNull { it.pluginId == id.removePrefix("apk:") }?.let { d.uninstallApk(it.packageName) }
                return true
            }
            d.uninstallBundle(id)
            return true
        }

        override suspend fun install(catalogId: String): Boolean {
            val spec = market.firstOrNull { it.name == catalogId }?.presentedVersion?.spec ?: catalogId
            return d.install(spec).isSuccess.also { tick++ }
        }

        override suspend fun installFromLink(link: String): Boolean {
            val spec = normalizeSpec(link) ?: return false
            return d.install(spec).isSuccess.also { tick++ }
        }

        override fun installFromFile() = fileLauncher.launch(arrayOf("application/zip", "application/gzip", "application/x-gzip", "application/vnd.android.package-archive", "application/octet-stream"))
        override fun createWithAgent() = d.onCreateWithAgent()
        override fun trust(candidateId: String) { d.trustCandidate?.let(d.onTrust) }
        override fun dismissTrust(candidateId: String) { d.trustCandidate?.let(d.onDismissTrust) }
    }

    val hooks = remember(d) { PluginUiHooks(d.loadSettings, d.saveSettings, d.speech) }
    PluginsHomeScreen(
        plugins, catalog, onClose = d.onClose, actions = actions, trust = d.trustCandidate?.let { trustOf(context, it) }, toast = toast,
        extraSection = { id -> PluginUis.forBundle(id)?.Section(hooks) },
    )
}

/** Лист доверия для нативного плагина, найденного при сканировании. */
internal fun trustOf(context: Context, n: InstalledNativePlugin): TrustCandidate {
    val pm = context.packageManager
    val info = runCatching { pm.getPackageInfo(n.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
    val label = info?.applicationInfo?.loadLabel(pm)?.toString() ?: n.pluginId.substringAfterLast('.')
    return TrustCandidate(
        id = n.pluginId, name = label, letter = pluginLetter(label), hue = pluginHue(n.pluginId),
        version = info?.versionName ?: n.version.toString(), pkg = n.packageName, publisher = publisher(context, n.packageName),
        cert = shortCert(n.signatureFingerprint), perms = permsOf(context, info, forTrust = true),
        changed = n.status == InstalledNativePlugin.Status.SIGNATURE_MISMATCH,
    )
}

/** `A1:7C:0E…54:9B` из полного отпечатка. */
internal fun shortCert(fp: String): String {
    val parts = if (fp.contains(':')) fp.split(':') else fp.chunked(2)
    val up = parts.map { it.uppercase(Locale.ROOT) }
    return if (up.size > 5) up.take(3).joinToString(":") + "…" + up.takeLast(2).joinToString(":") else up.joinToString(":")
}

private fun shortDay(millis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())).trimEnd('.')

/** Издатель — организация (O=) из сертификата подписи APK. */
private fun publisher(context: Context, pkg: String): String? = runCatching {
    val pm = context.packageManager
    val sig = if (android.os.Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    } ?: return null
    val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(sig.toByteArray().inputStream()) as java.security.cert.X509Certificate
    val dn = cert.subjectX500Principal.name
    Regex("(?:^|,)O=([^,]+)").find(dn)?.groupValues?.get(1) ?: Regex("(?:^|,)CN=([^,]+)").find(dn)?.groupValues?.get(1)
}.getOrNull()

/** Разрешения Android, которые плагин запросил, — понятными словами. */
private fun permsOf(context: Context, info: android.content.pm.PackageInfo?, forTrust: Boolean = false): List<PluginPerm> {
    info ?: return emptyList()
    val known = linkedMapOf(
        "android.permission.INTERNET" to ("globe" to R.string.plr_perm_internet),
        "android.permission.POST_NOTIFICATIONS" to ("bell" to R.string.plr_perm_notifications),
        "android.permission.CAMERA" to ("camera" to R.string.plr_perm_camera),
        "android.permission.RECORD_AUDIO" to ("mic" to R.string.plr_perm_mic),
        "android.permission.ACCESS_FINE_LOCATION" to ("pin" to R.string.plr_perm_location),
        "android.permission.READ_CONTACTS" to ("msg" to R.string.plr_perm_contacts),
        "android.permission.READ_EXTERNAL_STORAGE" to ("folder" to R.string.plr_perm_files),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to ("folder" to R.string.plr_perm_files),
    )
    val req = info.requestedPermissions.orEmpty()
    val flags = info.requestedPermissionsFlags ?: IntArray(req.size)
    return req.mapIndexedNotNull { i, p ->
        val (icon, name) = known[p] ?: return@mapIndexedNotNull null
        val granted = (flags.getOrElse(i) { 0 } and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        PluginPerm(icon, context.getString(name), if (forTrust) "" else context.getString(if (granted) R.string.plr_perm_granted else R.string.plr_perm_denied), granted)
    }.distinctBy { it.name }
}

/** Поле Config-схемы → настройка: переключатель или варианты (union из const). */
private fun settingOf(ns: String, key: String, node: FormNode, value: JsonElement?): PluginSetting? {
    val label = (node.meta["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: key
    val current = value ?: node.meta["default"]
    return when (node.type) {
        "boolean" -> PluginSetting("$ns|$key", label, emptyList(), 0, toggle = (current as? JsonPrimitive)?.booleanOrNull ?: false)
        "union" -> {
            val options = node.list.takeIf { l -> l.isNotEmpty() && l.all { it.type == "const" } } ?: return null
            if (options.size > 4) return null
            val names = options.map { (it.constValue as? JsonPrimitive)?.contentOrNull ?: "?" }
            PluginSetting("$ns|$key", label, names, options.indexOfFirst { it.constValue == current }.coerceAtLeast(0))
        }
        else -> null
    }
}

/** Имя пакета npm (можно с версией) или ссылка на репозиторий GitHub; иначе null. */
internal fun normalizeSpec(link: String): String? {
    val t = link.trim()
    if (t.isEmpty()) return null
    if (Regex("""^(?:https://)?(?:www\.)?github\.com/[^/\s#]+/[^/\s#]+?(?:\.git)?(?:#\S+)?/?$""", RegexOption.IGNORE_CASE).matches(t)) return t
    if (Regex("""^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*(@[\w.^~<>=*| -]+)?$""").matches(t)) return t
    return null
}

private suspend fun installFromFile(context: Context, uri: Uri, d: PluginDeps, toast: com.deepseek.dsh.core.designsystem.kit.ToastState) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull().orEmpty().lowercase(Locale.ROOT)
    if (name.endsWith(".apk") || context.contentResolver.getType(uri) == "application/vnd.android.package-archive") {
        // Нативный плагин ставит системный установщик; после возврата DSH найдёт его и спросит о доверии.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return
    }
    // Архив .zip Host-плагина: распаковываем в папку установщика (без выхода за её пределы) и ставим по пути.
    val dirName = "upload-" + System.currentTimeMillis()
    val root = withContext(Dispatchers.IO) {
        runCatching {
            val uploads = java.io.File(d.installCacheDir, "plugin-upload")
            // Прошлые загрузки уже установлены или не удались — в кэше им делать нечего.
            uploads.listFiles()?.forEach { com.deepseek.dsh.runtime.host.SafeFiles.deleteTree(it) }
            val dest = java.io.File(uploads, dirName)
            try {
                // Пути не выходят из папки, размер и число записей ограничены (zip-бомба не заполнит память).
                context.contentResolver.openInputStream(uri)!!.use { com.deepseek.dsh.runtime.host.SafeZip.extract(it, dest) }
            } catch (error: Exception) {
                com.deepseek.dsh.runtime.host.SafeFiles.deleteTree(dest)
                throw error
            }
            // Архив может содержать одну верхнюю папку.
            val single = dest.listFiles()?.singleOrNull()?.takeIf { it.isDirectory && !java.io.File(dest, "package.json").exists() }
            "/cache/plugin-upload/$dirName" + (single?.let { "/" + it.name } ?: "")
        }.getOrNull()
    } ?: return toast.show(context.getString(R.string.plr_file_failed))
    val ok = d.install(root).isSuccess
    toast.show(context.getString(if (ok) R.string.plr_file_done else R.string.plr_file_failed))
}

private fun callTime(context: Context, at: Long): String {
    val day = dayLabel(context, at)
    return if (day == context.getString(R.string.plr_today)) DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(at)) else day
}

private fun dayLabel(context: Context, at: Long): String {
    val d = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> context.getString(R.string.plr_today)
        today.minusDays(1) -> context.getString(R.string.plr_yesterday)
        else -> shortDay(at)
    }
}

private fun durationLabel(context: Context, ms: Long): String = when {
    ms < 1_000 -> context.getString(R.string.plr_dur_s, String.format(Locale.getDefault(), "%.1f", ms / 1000.0))
    ms < 60_000 -> context.getString(R.string.plr_dur_s, (ms / 1000).toString())
    else -> context.getString(R.string.plr_dur_m, ms / 60_000, (ms / 1000) % 60)
}

private fun callResult(context: Context, e: ToolCallLog.Entry): String = when (e.result) {
    "allowed" -> context.getString(R.string.plr_call_allowed)
    "denied" -> context.getString(R.string.plr_call_denied)
    "auto-allowed" -> context.getString(R.string.plr_call_auto_allowed)
    "auto-denied" -> context.getString(R.string.plr_call_auto_denied)
    else -> e.result.ifBlank { context.getString(if (e.status == "err") R.string.plr_call_err else R.string.plr_call_ok) }
}

/**
 * Экран плагина («Открыть»): его собственная страница — нативная, если её зарегистрировал APK,
 * иначе веб-страница Host — под общей шапкой «Назад».
 */
@Composable
internal fun PluginUiPage(
    id: String,
    title: String,
    slotEntries: List<PluginSlotEntry>,
    registry: com.deepseek.dsh.core.bridge.BridgeRegistry?,
    refresh: kotlinx.coroutines.flow.SharedFlow<String>?,
    hostPort: Int,
    hostToken: String?,
    hostPortProvider: () -> Int,
    pluginRpc: suspend (String, JsonObject) -> Result<String>,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSizeBg(),
    ) {
        com.deepseek.dsh.core.designsystem.kit.SBar(title, null, onBack, backDescription = androidx.compose.ui.res.stringResource(com.deepseek.dsh.feature.settings.R.string.st_back))
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
            if (id.startsWith("apk:")) {
                val pluginId = id.removePrefix("apk:")
                val registrations by com.deepseek.dsh.plugin.nativehost.NativePageRegistry.registrations.collectAsState()
                val page = registrations.firstOrNull { it.pluginId == pluginId }?.pages?.firstOrNull()
                if (page != null) {
                    com.deepseek.dsh.plugin.nativehost.NativePluginComponentPane(
                        page = page, componentId = "$pluginId#${page.entry.id}", spec = JsonObject(emptyMap()), onBack = onBack,
                        pluginRpc = pluginRpc, dispatchEvent = { cid, event, payload -> registry?.dispatchUiEvent(cid, event, payload) },
                        refreshEvents = refresh, showHeader = false,
                    )
                }
            } else {
                val entry = slotEntries.firstOrNull { it.module == id && it.slot == "settings.section" }
                val componentId = entry?.resolvedComponentId() ?: entry?.let { "${it.module}#${it.slot}" }
                if (componentId != null && entry != null) {
                    com.deepseek.dsh.feature.settings.PluginComponentPane(
                        componentId = componentId, title = entry.title ?: title, registry = registry, onBack = onBack,
                        refreshEvents = refresh, hostPort = hostPort, hostToken = hostToken, hostPortProvider = hostPortProvider,
                        nativeFallbackReason = null, showHeader = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.ui.Modifier.fillMaxSizeBg() =
    this.then(androidx.compose.ui.Modifier.fillMaxSize().background(com.deepseek.dsh.core.designsystem.Dsh.colors.bg))
