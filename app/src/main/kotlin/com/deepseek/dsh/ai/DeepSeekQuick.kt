package com.deepseek.dsh.ai

import com.deepseek.dsh.DEEPSEEK_VAULT_REF
import com.deepseek.dsh.feature.settings.logic.ApiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Короткий запрос к DeepSeek от имени приложения (не агента): «Объяснить» в терминале,
 * сообщение коммита. Ключ берётся из Keystore на время запроса и никуда не выходит.
 * Сеть — в `Dispatchers.IO`.
 */
object DeepSeekQuick {
    private const val URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** Итог запроса: текст или причина, почему его нет. */
    sealed interface Outcome {
        data class Ok(val text: String) : Outcome
        data object NoKey : Outcome
        data object BadKey : Outcome
        data object NoBalance : Outcome
        data object Offline : Outcome
    }

    /**
     * Потоковый ответ: [onText] получает накопленный текст после каждого куска.
     */
    suspend fun ask(system: String, user: String, maxTokens: Int, onText: (String) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        var text = ""
        val code = ApiKeyVault.use(DEEPSEEK_VAULT_REF) { key ->
            runCatching { stream(key, system, user, maxTokens) { text = it; onText(it) } }.getOrElse { -1 }
        }
        when (code) {
            null -> Outcome.NoKey
            in 200..299 -> Outcome.Ok(text)
            401, 403 -> Outcome.BadKey
            402 -> Outcome.NoBalance
            else -> Outcome.Offline
        }
    }

    /** SSE-поток chat/completions. Возвращает HTTP-код. */
    private fun stream(key: String, system: String, user: String, maxTokens: Int, onText: (String) -> Unit): Int {
        val c = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val body = JSONObject()
                .put("model", MODEL)
                .put("stream", true)
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) return code
            val sb = StringBuilder()
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty()
                    }.getOrDefault("")
                    if (delta.isEmpty()) continue
                    sb.append(delta)
                    onText(sb.toString())
                }
            }
            return code
        } finally { c.disconnect() }
    }
}
