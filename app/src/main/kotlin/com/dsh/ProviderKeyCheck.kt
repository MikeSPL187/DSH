package com.dsh

import com.dsh.feature.runtime.KeyCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Проверка API-ключа провайдера каталога прямо у провайдера, до сохранения: только чтение списка моделей
 * (бесплатно, без запроса к модели). Ключ уходит лишь на официальный адрес провайдера, не сохраняется и не логируется.
 *
 * - Anthropic — `GET /v1/models`, заголовки `x-api-key` и `anthropic-version`;
 * - OpenAI — `GET /v1/models`, `Authorization: Bearer`;
 * - Google — `GET /v1beta/models`, `x-goog-api-key` (не в адресе: адрес попадает в журналы прокси);
 * - OpenRouter — `GET /api/v1/key` (проверка ключа), число моделей неизвестно.
 */
internal suspend fun checkCatalogKey(id: String, key: String): KeyCheck = withContext(Dispatchers.IO) {
    val request = runCatching {
        when (id) {
            "anthropic" -> Request.Builder().url("https://api.anthropic.com/v1/models?limit=100")
                .header("x-api-key", key).header("anthropic-version", "2023-06-01")
            "openai" -> Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key")
            "google" -> Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").header("x-goog-api-key", key)
            "openrouter" -> Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization", "Bearer $key")
            else -> return@withContext KeyCheck.Bad
        }.get().build()
    }.getOrNull() ?: return@withContext KeyCheck.Bad
    try {
        keyClient.newCall(request).execute().use { response ->
            when {
                response.code == 400 || response.code == 401 || response.code == 403 -> KeyCheck.Bad
                !response.isSuccessful -> if (response.code >= 500 || response.code == 429) KeyCheck.Offline else KeyCheck.Bad
                else -> KeyCheck.Ok(models = catalogModels(id, response.body?.string().orEmpty()))
            }
        }
    } catch (e: java.io.IOException) {
        KeyCheck.Offline
    }
}

/** Модели из ответа провайдера: `data[].id` (Anthropic, OpenAI) или `models[].name` без `models/` (Google). */
internal fun catalogModels(id: String, body: String): List<String> = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    fun ids(array: String, field: String) = (root[array] as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    when (id) {
        "google" -> ids("models", "name").map { it.removePrefix("models/") }
        "openrouter" -> emptyList()
        else -> ids("data", "id")
    }
}.getOrDefault(emptyList())
