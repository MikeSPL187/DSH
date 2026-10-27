package com.dsh.feature.settings.logic

import com.dsh.core.designsystem.kit.*
import android.content.Context

/**
 * Что приложение помнит о нативных плагинах сверх доверенной подписи:
 * когда ему доверились и выключен ли он (выключенный не загружается в процесс DSH).
 */
object NativePluginPrefs {
    private const val PREFS = "dsh_native_plugin_prefs"

    fun trustedAt(context: Context, pluginId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("trusted.$pluginId", 0L).takeIf { it > 0 }

    fun markTrusted(context: Context, pluginId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("trusted.$pluginId", System.currentTimeMillis()).apply()
    }

    fun isDisabled(context: Context, pluginId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("off.$pluginId", false)

    fun setDisabled(context: Context, pluginId: String, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("off.$pluginId", off).apply()
    }
}
