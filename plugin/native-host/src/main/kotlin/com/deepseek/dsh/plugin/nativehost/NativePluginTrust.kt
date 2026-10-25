package com.deepseek.dsh.plugin.nativehost

import android.content.Context

/**
 * 用户明确批准过的原生伴侣身份：pluginId 绑定到「包名 + 签名证书指纹」。
 *
 * 伴侣 APK 的代码会被加载进 App 进程（见 [NativePluginLoader]），拥有 App 的全部权限，
 * 因此 pluginId 本身不能作为信任依据——任何 APK 都能在 meta-data 里声明同一个 id。
 * 只有用户确认过的 (packageName, fingerprint) 组合才会被装载。
 *
 * @property packageName 批准时伴侣 APK 的包名。
 * @property fingerprint 批准时的签名证书 SHA-256（小写十六进制）。
 */
data class NativePluginPin(
    val packageName: String,
    val fingerprint: String,
)

/** 纯逻辑：把扫描到的身份与已批准的 pin 对比，决定能否加载。 */
object NativePluginTrustPolicy {

    enum class Verdict { TRUSTED, UNTRUSTED, MISMATCH }

    /**
     * @param pin 该 pluginId 已批准的身份；null 表示从未批准（首次发现）。
     */
    fun evaluate(packageName: String, fingerprint: String, pin: NativePluginPin?): Verdict = when {
        pin == null -> Verdict.UNTRUSTED
        !pin.packageName.equals(packageName, ignoreCase = false) -> Verdict.MISMATCH
        !constantTimeEquals(pin.fingerprint.lowercase(), fingerprint.lowercase()) -> Verdict.MISMATCH
        else -> Verdict.TRUSTED
    }

    /** 序列化格式：`packageName\nfingerprint`；包名与十六进制指纹都不会包含换行。 */
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
 * 持久化的伴侣信任表（App 私有 SharedPreferences，allowBackup=false）。
 *
 * 首次发现的伴侣处于 [InstalledNativePlugin.Status.UNTRUSTED]，必须由用户在确认框里
 * 看过包名与证书指纹后批准；签名或包名变化（掉包/重签）会进入 SIGNATURE_MISMATCH，
 * 同样需要用户重新确认，绝不静默放行。
 */
class NativePluginTrustStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前全部已批准的 pluginId → pin。 */
    fun pins(): Map<String, NativePluginPin> = prefs.all.mapNotNull { (key, value) ->
        NativePluginTrustPolicy.decode(value as? String)?.let { key to it }
    }.toMap()

    /** 用户批准：把该伴侣当前的包名与指纹钉死（覆盖旧 pin）。 */
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
