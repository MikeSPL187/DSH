package com.dsh

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Язык интерфейса: выбирается в «Настройки → Язык»; Host всегда на английском.
 *
 * - `system` — как в системе;
 * - `en` / `ru` — языки приложения (per-app locale); Host получает `en`.
 *   Сохранённый ранее `zh` больше не поддерживается и читается как `system`.
 *
 * Запись — через язык приложения платформы (Android 13+), система пересоздаёт Activity.
 * В Android 8–12 языка приложения нет: раньше переключение там ничего не делало. Теперь
 * язык применяется в [wrap] (attachBaseContext у Application и Activity),
 * а при переключении обновляются ресурсы и пересоздаётся Activity.
 */
object AppLanguage {
    private const val PREFS = "app_locale"
    private const val KEY = "selection"
    private val SUPPORTED = setOf("system", "en", "ru")

    /** Выбор пользователя: `system` / `en` / `ru`. */
    fun selection(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system")
            ?.takeIf { it in SUPPORTED } ?: "system"

    /**
     * Применить сохранённый выбор при запуске (повторный вызов безопасен). В Android 8–12 язык
     * уже применён в [wrap]: [applyLegacy] здесь вызвал бы бесконечный цикл
     * onCreate → recreate → onCreate (на эмуляторе Android 8 — чёрный экран и загруженный поток).
     */
    fun applySaved(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        apply(context, selection(context))
    }

    /** Применить выбор: язык приложения и сохранение. */
    fun apply(context: Context, selection: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selection).apply()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            applyLegacy(context, selection)
            return
        }
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = when (selection) {
            "system" -> LocaleList.getEmptyLocaleList()
            else -> LocaleList.forLanguageTags(selection)
        }
    }

    /** Android 8–12: базовый Context с выбранной локалью (для attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = legacyLocale(selection(base)) ?: return base
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun legacyLocale(selection: String): java.util.Locale? =
        if (selection == "system") null else java.util.Locale.forLanguageTag(selection)

    @Suppress("DEPRECATION")
    private fun applyLegacy(context: Context, selection: String) {
        // Без изменения языка ничего не пересоздаём: recreate() из onCreate зацикливается.
        if (legacyLocale(selection)?.language == context.resources.configuration.locales[0]?.language &&
            selection != "system"
        ) return
        val locale = legacyLocale(selection)
            ?: android.content.res.Resources.getSystem().configuration.locales[0]
        java.util.Locale.setDefault(locale)
        // Строки, которые берутся из applicationContext вне Activity, тоже должны сменить язык.
        val appResources = context.applicationContext.resources
        val config = android.content.res.Configuration(appResources.configuration)
        config.setLocale(locale)
        appResources.updateConfiguration(config, appResources.displayMetrics)
        var current: Context? = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) {
            current = current.baseContext
        }
        (current as? android.app.Activity)?.recreate()
    }
}
