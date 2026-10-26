package com.deepseek.dsh.plugin.nativehost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Снимок установленного нативного компаньона.
 *
 * @property pluginId id плагина (`dsh.plugin.id`), совпадает с префиксом componentId JS-плагина.
 * @property packageName имя пакета компаньона APK.
 * @property version версия плагина (из descriptor.version).
 * @property versionCode versionCode APK.
 * @property apiVersion версия ABI нативного SDK, под которую собран компаньон.
 * @property minAppVersion минимальный код версии приложения.
 * @property signatureFingerprint отпечаток сертификата подписи (SHA-256) — для обнаружения подмены.
 * @property status состояние; загружаются только READY.
 * @property serviceComponent компонент Service плагина (для bindService).
 */
data class InstalledNativePlugin(
    val pluginId: String,
    val packageName: String,
    val version: Int,
    val versionCode: Int,
    val apiVersion: Int,
    val minAppVersion: Int,
    val signatureFingerprint: String,
    val status: Status,
    val serviceComponent: ComponentName,
    /**
     * Адрес установки парного JS-плагина (`dsh.plugin.jsPackage`: имя npm или адрес GitHub).
     * При установке APK приложение по нему ставит и JS-плагин; если нет — пробует [pluginId] как имя npm.
     */
    val jsPackage: String? = null,
    /** Время установки или обновления APK (мс) — для напоминания об обновлении. */
    val lastUpdateTime: Long = 0L,
) {
    /**
     * - READY: пользователь одобрил, имя пакета и подпись совпадают с одобренными — можно загружать.
     * - UNTRUSTED: найден впервые, не одобрен; код не загружается.
     * - SIGNATURE_MISMATCH: у того же pluginId другое имя пакета или подпись (подмена, переподпись) — не загружается.
     */
    enum class Status { READY, UNTRUSTED, ABI_TOO_NEW, APP_TOO_OLD, SIGNATURE_MISMATCH, DISABLED }
}

/**
 * Поиск плагинов: компаньоны APK, объявившие сервис `com.deepseek.dsh.action.PLUGIN_UI`;
 * их meta-data разбирается в список установленных. Читается только meta-data, код не привязывается и не загружается.
 *
 * Приложение пересканирует всё при запуске и следит за `PACKAGE_ADDED`/`PACKAGE_REMOVED`/
 * `PACKAGE_REPLACED` — удалённый плагин убирается из мест вставки сразу, без висящих ссылок.
 */
class NativePluginDiscovery(private val context: Context) {

    /**
     * Полное сканирование установленных нативных компаньонов.
     *
     * @param appVersionCode код версии приложения (для проверки ABI).
     * @param supportedApiVersion версия ABI нативного SDK приложения.
     * @param pins одобренные пользователем pluginId → (имя пакета, отпечаток), см. [NativePluginTrustStore].
     *   Компаньон без pin всегда UNTRUSTED: «увидели впервые» — не повод доверять.
     */
    fun scan(
        appVersionCode: Int,
        supportedApiVersion: Int,
        pins: Map<String, NativePluginPin>,
    ): List<InstalledNativePlugin> {
        val pm = context.packageManager
        val intent = Intent(ACTION_PLUGIN_UI)
        val services = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return services.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            val meta = serviceInfo.metaData ?: return@mapNotNull null

            val pluginId = meta.getString(META_ID)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val apiVersion = meta.getInt(META_API_VERSION, -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val minAppVersion = meta.getInt(META_MIN_APP_VERSION, 0)
            // Адрес парного JS-плагина (первый источник — manifest). Если нет — вызывающий смотрит индекс магазина (второй).
            val jsPackage = meta.getString(META_JS_PACKAGE)?.takeIf { it.isNotBlank() }
            val packageName = serviceInfo.packageName
            val fingerprint = signingFingerprint(pm, packageName) ?: return@mapNotNull null

            val pkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
            val versionCode = pkgInfo?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
            val lastUpdateTime = pkgInfo?.lastUpdateTime ?: 0L

            // Проверка ABI: любое несовпадение — только состояние, до загрузки дело не доходит.
            val status = when {
                apiVersion > supportedApiVersion -> InstalledNativePlugin.Status.ABI_TOO_NEW
                minAppVersion > appVersionCode -> InstalledNativePlugin.Status.APP_TOO_OLD
                else -> when (NativePluginTrustPolicy.evaluate(packageName, fingerprint, pins[pluginId])) {
                    NativePluginTrustPolicy.Verdict.TRUSTED -> InstalledNativePlugin.Status.READY
                    NativePluginTrustPolicy.Verdict.UNTRUSTED -> InstalledNativePlugin.Status.UNTRUSTED
                    NativePluginTrustPolicy.Verdict.MISMATCH -> InstalledNativePlugin.Status.SIGNATURE_MISMATCH
                }
            }

            InstalledNativePlugin(
                pluginId = pluginId,
                packageName = packageName,
                version = meta.getInt(META_VERSION, 1),
                versionCode = versionCode,
                apiVersion = apiVersion,
                minAppVersion = minAppVersion,
                signatureFingerprint = fingerprint,
                status = status,
                serviceComponent = ComponentName(packageName, serviceInfo.name),
                jsPackage = jsPackage,
                lastUpdateTime = lastUpdateTime,
            )
        }
    }

    internal companion object {
        /**
         * Отпечаток SHA-256 сертификата подписи пакета (нижний регистр, без разделителей).
         *
         * APK с несколькими подписантами не поддерживаются: если взять одного, злоумышленник добавил бы свой
         * сертификат и обошёл pin, поэтому для них всегда null (не находится и не загружается).
         */
        fun signingFingerprint(pm: PackageManager, packageName: String): String? = runCatching {
            val cert = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) return null
                signingInfo.apkContentsSigners?.singleOrNull()?.toByteArray() ?: return null
            } else {
                // Android 8.x: SigningInfo появился в API 28; раньше этот путь всегда падал,
                // и на Android 8 нативные плагины не обнаруживались вовсе.
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.singleOrNull()?.toByteArray() ?: return null
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert)
            digest.joinToString("") { "%02x".format(it) }
        }.getOrNull()

        const val ACTION_PLUGIN_UI = "com.deepseek.dsh.action.PLUGIN_UI"
        const val META_ID = "dsh.plugin.id"
        const val META_API_VERSION = "dsh.plugin.apiVersion"
        const val META_MIN_APP_VERSION = "dsh.plugin.minAppVersion"
        const val META_VERSION = "dsh.plugin.version"
        /** Адрес установки парного JS-плагина (имя npm или адрес GitHub). */
        const val META_JS_PACKAGE = "dsh.plugin.jsPackage"
    }
}
