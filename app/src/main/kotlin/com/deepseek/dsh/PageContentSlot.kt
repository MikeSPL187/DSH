package com.deepseek.dsh

import androidx.compose.runtime.Composable

/**
 * Слот содержимого страницы: код страницы упаковывается в отдельную, не inline,
 * composable-лямбду.
 *
 * Compose компилирует такую лямбду в отдельный метод, поэтому код страниц не входит
 * в тело `MainAppHost` — раньше оно упиралось в предел проверки ART
 * и любое добавление падало с VerifyError.
 */
@Composable
internal fun PageContentSlot(content: @Composable () -> Unit) {
    content()
}
