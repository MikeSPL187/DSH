package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.Tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Распознавание речи в Host (`speech/...`): каталог сервисов и языков, подготовка модели,
 * распознавание записи. Состояние общее с веб-версией — это тот же сервис Host.
 */
class SpeechRepository internal constructor(private val calls: HostCalls) {

    /** Сервисы, языки и состояние подготовки. */
    suspend fun speechCatalog(hostId: String): Result<SpeechCatalog> =
        calls.valueOrThrow(hostId, "speech/catalog").mapCatching { value ->
            parseSpeechCatalog(value)
                ?: error(Tr.t("Speech service returned no catalog", "Голосовой сервис не вернул каталог"))
        }

    /** Сохранить выбор сервиса и языка (`configure(patch)`). */
    suspend fun speechConfigure(hostId: String, providerId: String, language: String): Result<Boolean> {
        val args = buildJsonObject {
            put("patch", buildJsonObject {
                put("providerId", providerId)
                put("language", language)
            })
        }
        return calls.rpc(hostId, "speech/configure", args).map { true }
    }

    /**
     * Подготовить сервис: скачать, проверить и загрузить локальную модель.
     * @param downloadSource источник загрузки; null или пусто — выбрать автоматически.
     */
    suspend fun speechPrepare(hostId: String, providerId: String, downloadSource: String?): Result<Boolean> {
        val args = buildJsonObject {
            put("providerId", providerId)
            if (!downloadSource.isNullOrBlank()) {
                put("options", buildJsonObject { put("downloadSource", downloadSource) })
            }
        }
        return calls.rpc(hostId, "speech/prepare", args).map { true }
    }

    /** Отменить идущую подготовку. */
    suspend fun speechCancelPrepare(hostId: String, providerId: String): Result<Boolean> =
        calls.rpc(hostId, "speech/cancelPreparation", buildJsonObject { put("providerId", providerId) }).map { true }

    /**
     * Распознать запись.
     * @param language язык распознавания (auto/en/ru/…).
     * @param audioBase64 WAV 16 кГц, моно, PCM16 в base64 (без префикса data URL).
     */
    suspend fun speechTranscribe(
        hostId: String,
        providerId: String,
        language: String,
        audioBase64: String,
    ): Result<String> {
        val args = buildJsonObject {
            put("request", buildJsonObject {
                put("audioBase64", audioBase64)
                put("providerId", providerId)
                put("language", language)
            })
        }
        return calls.valueOrThrow(hostId, "speech/transcribe", args).mapCatching { value ->
            val result = value as? JsonObject
                ?: error(Tr.t("Transcription returned no result", "Распознавание речи не вернуло результат"))
            result.str("text").orEmpty()
        }
    }
}
