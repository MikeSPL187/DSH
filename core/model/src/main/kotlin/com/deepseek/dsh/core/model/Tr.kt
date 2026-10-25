package com.deepseek.dsh.core.model

import java.util.Locale

/**
 * Локализация текстов в модулях без Android Context (протокол, репозиторий, установщики).
 *
 * Раньше такие сообщения были захардкожены по-китайски и показывались пользователям
 * любой локали. Приложение подставляет [language] из текущей конфигурации ресурсов,
 * чтобы выбранный в настройках язык действовал и здесь; без него берётся системная локаль.
 */
object Tr {
    @Volatile
    var language: () -> String = { Locale.getDefault().language }

    /** Языки приложения — русский и английский; любой другой получает английский. */
    fun t(en: String, ru: String): String =
        if (runCatching(language).getOrDefault("en") == "ru") ru else en
}
