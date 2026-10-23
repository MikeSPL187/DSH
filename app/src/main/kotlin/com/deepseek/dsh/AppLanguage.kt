package com.deepseek.dsh

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * App 界面语言：由用户在「设置 → 通用 → 语言」里选择，选择同时写给 Host。
 *
 * - `system` 跟随系统；
 * - `zh` / `en` / `ru` 是 App 的 per-app locale；Host 只支持 zh/en，`ru` 同步为 `en`。
 *
 * 写入走平台的 per-app locale（Android 13+），系统会重建 Activity，资源随之切换。
 * Android 8–12 没有 per-app locale：此前那里切换语言毫无效果。现在在
 * [wrap]（Application/Activity 的 attachBaseContext）里套用所选语言，
 * 切换时同步更新应用资源并重建 Activity。
 */
object AppLanguage {
    private const val PREFS = "app_locale"
    private const val KEY = "selection"
    private val SUPPORTED = setOf("system", "zh", "en", "ru")

    /** 用户选择的原始值：`system` / `zh` / `en` / `ru`。 */
    fun selection(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system")
            ?.takeIf { it in SUPPORTED } ?: "system"

    /** 生效的语言标签（BCP-47），用于 Host 语言同步。 */
    fun effectiveTag(context: Context): String {
        val selection = selection(context)
        if (selection != "system") return selection
        return java.util.Locale.getDefault().toLanguageTag()
    }

    /** 启动时套用已保存的选择（幂等）。 */
    fun applySaved(context: Context) = apply(context, selection(context))

    /** 套用一次选择：更新 per-app locale 并持久化。 */
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
