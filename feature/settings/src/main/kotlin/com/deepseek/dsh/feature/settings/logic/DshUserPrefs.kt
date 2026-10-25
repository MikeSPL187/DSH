package com.deepseek.dsh.feature.settings.logic

import com.deepseek.dsh.core.designsystem.kit.*
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Настройки, которые живут только в приложении: размер шрифта кода, вибрация,
 * вход по отпечатку, уведомления «агент ждёт решения» и об автоматизациях.
 * Значения — Compose-состояние: экраны перерисовываются сразу после изменения.
 */
object DshUserPrefs {
    private const val PREFS = "dsh_user_prefs"
    private var app: Context? = null

    private var _codeSize by mutableIntStateOf(13)
    private var _haptics by mutableStateOf(true)
    private var _biometric by mutableStateOf(false)
    private var _notifAsk by mutableStateOf(true)
    private var _notifAuto by mutableStateOf("err")

    var codeSize: Int get() = _codeSize; set(v) { _codeSize = v.coerceIn(11, 16); edit { putInt("codeSize", _codeSize) } }
    var haptics: Boolean get() = _haptics; set(v) { _haptics = v; edit { putBoolean("haptics", v) } }
    var biometric: Boolean get() = _biometric; set(v) { _biometric = v; edit { putBoolean("biometric", v) } }
    var notifAsk: Boolean get() = _notifAsk; set(v) { _notifAsk = v; edit { putBoolean("notifAsk", v) } }
    /** `always` / `err` / `never`. */
    var notifAuto: String get() = _notifAuto; set(v) { _notifAuto = v; edit { putString("notifAuto", v) } }

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _codeSize = p.getInt("codeSize", 13).coerceIn(11, 16)
        _haptics = p.getBoolean("haptics", true)
        _biometric = p.getBoolean("biometric", false)
        _notifAsk = p.getBoolean("notifAsk", true)
        _notifAuto = p.getString("notifAuto", "err") ?: "err"
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply(block)?.apply()
    }

}
