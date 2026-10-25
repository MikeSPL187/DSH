package com.deepseek.dsh.plugin.nativehost

import android.content.Context

/**
 * Одобренная пользователем личность компаньона: pluginId привязан к «имени пакета + отпечатку подписи».
 *
 * Код компаньона загружается в процесс приложения (см. [NativePluginLoader]) со всеми его правами,
 * поэтому сам pluginId доверия не даёт: такой id может объявить в meta-data любой APK.
 * Загружается только пара (packageName, fingerprint), которую подтвердил пользователь.
 *
 * @property packageName имя пакета компаньона при одобрении.
 * @property fingerprint SHA-256 сертификата подписи при одобрении (hex в нижнем регистре).
 */
data class NativePluginPin(
    val packageName: String,
    val fingerprint: String,
)

/** Чистая логика: сравнить найденную личность с одобренным pin и решить, можно ли загружать. */
object NativePluginTrustPolicy {

    enum class Verdict { TRUSTED, UNTRUSTED, MISMATCH }

    /**
     * @param pin одобренная личность для этого pluginId; null — не одобрялся (найден впервые).
     */
    fun evaluate(packageName: String, fingerprint: String, pin: NativePluginPin?): Verdict = when {
        pin == null -> Verdict.UNTRUSTED
        !pin.packageName.equals(packageName, ignoreCase = false) -> Verdict.MISMATCH
        !constantTimeEquals(pin.fingerprint.lowercase(), fingerprint.lowercase()) -> Verdict.MISMATCH
        else -> Verdict.TRUSTED
    }

    /** Формат: `packageName\nfingerprint`; в имени пакета и hex-отпечатке переводов строки не бывает. */
    fun encode(pin: NativePluginPin): String = pin.packageName + "\n" + pin.fingerprint

    fun decode(raw: String?): NativePluginPin? {
        val parts = raw?.split('\n') ?: return null
        if (parts.size != 2) return null
        val (pkg, fp) = parts
        if (pkg.isBlank() || !FINGERPRINT.matches(fp)) return null
        return NativePluginPin(pkg, fp.lowercase())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private val FINGERPRINT = Regex("^[0-9a-fA-F]{64}$")
}

/**
 * Сохранённая таблица доверия компаньонам (личные SharedPreferences приложения, allowBackup=false).
 *
 * Новый компаньон — [InstalledNativePlugin.Status.UNTRUSTED]: пользователь одобряет его в окне подтверждения,
 * увидев имя пакета и отпечаток сертификата; смена подписи или имени (подмена, переподпись) даёт SIGNATURE_MISMATCH,
 * и снова нужно подтверждение — молча ничего не пропускается.
 */
class NativePluginTrustStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Все одобренные pluginId → pin. */
    fun pins(): Map<String, NativePluginPin> = prefs.all.mapNotNull { (key, value) ->
        NativePluginTrustPolicy.decode(value as? String)?.let { key to it }
    }.toMap()

    /** Пользователь одобрил: закрепить текущие имя пакета и отпечаток компаньона (старый pin заменяется). */
    fun trust(plugin: InstalledNativePlugin) {
        prefs.edit()
            .putString(
                plugin.pluginId,
                NativePluginTrustPolicy.encode(
                    NativePluginPin(plugin.packageName, plugin.signatureFingerprint.lowercase()),
                ),
            )
            .apply()
    }

    private companion object {
        const val PREFS = "dsh_native_plugin_trust"
    }
}
