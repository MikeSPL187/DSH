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
}
