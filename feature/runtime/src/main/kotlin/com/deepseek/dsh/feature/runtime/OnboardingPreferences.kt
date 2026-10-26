package com.deepseek.dsh.feature.runtime

import android.content.Context

/**
 * Пройден ли первый запуск на этой установке: знак хранится в закрытых
 * SharedPreferences, чтобы знакомство показывалось один раз.
 */
class OnboardingPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Первый запуск уже пройден. */
    val isCompleted: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED, false)

    /** Отметить первый запуск пройденным. */
    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    private companion object {
        const val FILE_NAME = "dsh_onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
