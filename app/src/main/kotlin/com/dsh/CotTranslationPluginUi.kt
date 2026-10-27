package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Нативный интерфейс плагина «Машинный перевод рассуждений».
 *
 * Плагин даёт два места: панель настроек перевода (`plugins.bundle.config`)
 * и переключатель перевод/оригинал в развёрнутом рассуждении (`conversation.chat.reasoning.body`).
 * Здесь панель настроек (`TranslationPreferencesForm`): сервис перевода и
 * язык, сохранение в пространство `cot-translation`.
 *
 * Тексты взяты из `packages/experimental/client-ui-cot-translation/src/client/locales.ts`
 * (provider/paidNotice/privacy/targetLanguage/targetLanguageHint).
 */
internal object CotTranslationPluginUi : PluginUi {
    override val bundle: String = "@deepseek-ai/dsh-experimental-cot-translation-bundle"

    @Composable
    override fun Section(hooks: PluginUiHooks) {
        PluginSettingsSection(
            namespace = "cot-translation",
            hooks = hooks,
            fields = listOf(
                PluginField(
                    key = "provider",
                    label = stringResource(R.string.cot_service),
                    kind = PluginFieldKind.CHOICE,
                    choices = listOf(
                        "bing" to "Bing",
                        "google" to "Google",
                        "deepseek-account" to stringResource(R.string.cot_provider_account),
                        "deepseek-official" to stringResource(R.string.cot_provider_official),
                    ),
                ),
                PluginField(
                    key = "targetLanguage",
                    label = stringResource(R.string.cot_target_language),
                    hint = stringResource(R.string.cot_target_language_hint),
                ),
            ),
            notices = listOf(
                stringResource(R.string.cot_privacy_note),
                stringResource(R.string.cot_billing_note),
                stringResource(R.string.cot_free_note),
            ),
            emptyHint = stringResource(R.string.cot_unavailable),
        )
    }
}
