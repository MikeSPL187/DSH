package com.deepseek.dsh.runtime.bridge

import android.content.Context

/**
 * Что коду внутри Host (агенту и его npm-плагинам) разрешено делать через мост.
 *
 * Чтение буфера обмена было открыто всегда и без уведомления: в буфере часто лежат
 * пароли, коды и токены, а прочитать его мог любой установленный в Host плагин.
 * Теперь по умолчанию запрещено и включается пользователем явно.
 */
object BridgePolicy {
    private const val PREFS = "dsh_bridge_policy"
    private const val KEY_CLIPBOARD_READ = "allow_clipboard_read"

    fun isClipboardReadAllowed(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLIPBOARD_READ, false)
    }.getOrDefault(false)

    fun setClipboardReadAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLIPBOARD_READ, allowed).apply()
    }

    private const val KEY_THIRD_PARTY_PLUGINS = "allow_third_party_plugins"

    /**
     * Можно ли ставить плагины Host из сети — npm, GitHub, рынок (G-48). По умолчанию нет: это чужой код с правами
     * приложения, без закреплённого в APK хэша. Плагин из выбранного пользователем файла этим не ограничивается.
     */
    fun isThirdPartyPluginsAllowed(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_THIRD_PARTY_PLUGINS, false)
    }.getOrDefault(false)

    fun setThirdPartyPluginsAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_THIRD_PARTY_PLUGINS, allowed).apply()
    }
}
