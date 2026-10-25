package com.deepseek.dsh

import androidx.compose.runtime.Composable
import com.deepseek.dsh.core.data.PluginsRepository
import com.deepseek.dsh.core.model.PluginSettingsDocument
import kotlinx.serialization.json.JsonObject

/**
 * Возможности Host для нативного интерфейса плагинов.
 *
 * Интерфейс плагина читает документ настроек (`configForms.get(ns)`) и учётные данные
 * (`remote.credentials`). Нативные страницы используют те же вызовы — поведение как в веб-версии.
 */
internal data class PluginUiHooks(
    /** Прочитать форму и значения пространства настроек. */
    val loadDocument: suspend (String) -> PluginSettingsDocument?,
    /** Записать пространство настроек (с revision; при конфликте запись отклоняется). */
    val saveDocument: suspend (String, JsonObject, Int) -> Boolean,
    /** Состояние секрета (без значения: задан ли и можно ли писать). */
    val loadCredential: suspend (String) -> PluginsRepository.CredentialStatus?,
    /** Записать секрет. */
    val saveCredential: suspend (String, String) -> Boolean,
    /** Распознавание речи Host (`speech`); у плагинов, кроме голосового, null. */
    val speech: SpeechHooks? = null,
)

/**
 * Нативный интерфейс экспериментального плагина.
 *
 * Правило: **один `.kt` на плагин** — в нём реализация этого интерфейса и единственный экземпляр,
 * который регистрируется в [PluginUis]. Страница bundle рисует [Section] под
 * «Компонентами»; плагины без интерфейса не регистрируются.
 */
internal interface PluginUi {
    /** Bundle, к которому относится интерфейс (имя пакета Host, например `@deepseek-ai/dsh-experimental-cot-translation-bundle`). */
    val bundle: String

    /** Блок на странице bundle. */
    @Composable
    fun Section(hooks: PluginUiHooks)
}

/** Зарегистрированные нативные интерфейсы плагинов. */
internal object PluginUis {
    private val entries: List<PluginUi> = listOf(
        // Новые плагины регистрируются здесь (по одному .kt на плагин).
        CotTranslationPluginUi,
        VoiceInputPluginUi,
    )

    /** @param bundle имя пакета bundle. @returns нативный интерфейс или null. */
    fun forBundle(bundle: String): PluginUi? = entries.firstOrNull { it.bundle == bundle }

    /** Bundle с нативным интерфейсом — список отмечает их «Есть нативные настройки». */
    val bundles: Set<String>
        get() = entries.map { it.bundle }.toSet()
}
