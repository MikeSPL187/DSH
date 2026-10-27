package com.dsh.core.data

import com.dsh.core.model.Tr
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Перевод хода рассуждений модели (`cotTranslation/...`): «Показать перевод / оригинал». */
class TranslationRepository internal constructor(private val calls: HostCalls) {

    /** Предел длины фрагмента, выбранный сервис и язык. */
    suspend fun cotTranslationLimits(hostId: String): Result<CotTranslationLimits> =
        calls.valueOrThrow(hostId, "cotTranslation/limits").mapCatching { value ->
            parseCotTranslationLimits(value)
                ?: error(Tr.t("Translation service returned no configuration", "Сервис перевода не вернул настройки"))
        }

    /**
     * Перевести рассуждение. Текст делится на абзацы по пустым строкам, абзац — на куски
     * не длиннее `maxTextChars`; куски переводятся по очереди и собираются обратно.
     * Ошибка любого куска — ошибка всего перевода: интерфейс покажет оригинал.
     * @param sessionId сессия (Host ведёт по ней кэш и учёт).
     */
    suspend fun cotTranslationTranslate(hostId: String, text: String, sessionId: String): Result<String> {
        val limits = cotTranslationLimits(hostId).getOrElse { return Result.failure(it) }
        val translated = mutableListOf<String>()
        for (paragraph in text.split(PARAGRAPH_BREAK)) {
            if (paragraph.isBlank()) continue
            val builder = StringBuilder()
            for (piece in paragraph.chunked(limits.maxTextChars.coerceAtLeast(200))) {
                val args = buildJsonObject {
                    put("request", buildJsonObject {
                        put("provider", limits.provider)
                        put("targetLanguage", limits.targetLanguage)
                        put("text", piece)
                        put("sessionId", sessionId)
                    })
                }
                val result = calls.valueOrThrow(hostId, "cotTranslation/translate", args)
                    .map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .getOrElse { return Result.failure(it) }
                builder.append(result)
            }
            translated.add(builder.toString())
        }
        return Result.success(translated.joinToString("\n\n"))
    }

    private companion object {
        val PARAGRAPH_BREAK = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+")
    }
}
