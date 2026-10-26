package com.deepseek.dsh.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Модель перевода рассуждений (`cotTranslation`).
 *
 * `limits()` даёт доступные сервисы, выбранный сервис и предел фрагмента; затем
 * `translate(request)` вызывается по фрагментам; перевод кэширует интерфейс.
 */

/** Ответ `cotTranslation/limits`. */
data class CotTranslationLimits(
    /** Предел символов во фрагменте; длиннее — режется. */
    val maxTextChars: Int,
    /** Выбранный сервис (bing/google/deepseek-account/deepseek-official). */
    val provider: String,
    /** Язык перевода; `auto` — язык интерфейса. */
    val targetLanguage: String,
    /** Доступные сервисы. */
    val availableProviders: List<String>,
)

internal fun parseCotTranslationLimits(value: JsonElement?): CotTranslationLimits? {
    val root = value as? JsonObject ?: return null
    val preferences = root["preferences"] as? JsonObject
    return CotTranslationLimits(
        maxTextChars = (root["maxTextChars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 4000,
        provider = (preferences?.get("provider") as? JsonPrimitive)?.contentOrNull ?: "bing",
        targetLanguage = (preferences?.get("targetLanguage") as? JsonPrimitive)?.contentOrNull ?: "auto",
        availableProviders = (root["availableProviders"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )
}
