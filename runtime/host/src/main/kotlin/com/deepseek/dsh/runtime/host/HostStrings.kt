package com.deepseek.dsh.runtime.host

import android.content.Context

/**
 * Локализованные тексты среды выполнения (прогресс установки rootfs, ошибки запуска Host).
 *
 * Раньше эти строки были захардкожены по-китайски и показывались пользователям любой
 * локали. Приложение вызывает [init] при старте; без Context (JVM-тесты) используется
 * английский текст-подстраховка.
 */
object HostStrings {
    // Только applicationContext (живёт столько же, сколько процесс) — утечки Activity нет (G-40).
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    internal fun get(resId: Int, fallback: String, vararg args: Any?): String {
        val ctx = context
        return if (ctx != null) {
            runCatching { ctx.getString(resId, *args) }.getOrElse { fallback.format(*args) }
        } else {
            fallback.format(*args)
        }
    }
}
