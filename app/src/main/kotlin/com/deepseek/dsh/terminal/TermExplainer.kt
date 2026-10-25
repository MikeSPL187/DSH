package com.deepseek.dsh.terminal

import android.content.Context
import com.deepseek.dsh.DEEPSEEK_VAULT_REF
import com.deepseek.dsh.R
import com.deepseek.dsh.feature.settings.logic.ApiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Объяснить» (`.explain`): приложение само спрашивает DeepSeek о провалившейся команде
 * (команда, код выхода и хвост вывода) и печатает ответ по мере прихода.
 * Последняя строка `FIX: <команда>` становится кнопкой, которая подставляет команду в ввод.
 */
object TermExplainer {
    private const val URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    suspend fun explain(context: Context, block: TermBlock, into: Explain) = withContext(Dispatchers.IO) {
        into.text = ""; into.fix = null; into.error = null; into.done = false
        val tail = block.out.plain().lines().takeLast(40).joinToString("\n").takeLast(4000)
        val user = "$ ${block.cmd}\n(exit ${block.code})\n$tail"
        val res = ApiKeyVault.use(DEEPSEEK_VAULT_REF) { key ->
            runCatching { stream(key, context.getString(R.string.tm_explain_prompt), user, into) }
                .getOrElse { -1 }
        }
        into.error = when (res) {
            null -> context.getString(R.string.tm_explain_nokey)
            in 200..299 -> null
            401, 403 -> context.getString(R.string.tm_explain_badkey)
            402 -> context.getString(R.string.tm_explain_balance)
            else -> context.getString(R.string.tm_explain_offline)
        }
        if (into.error == null) {
            val (text, fix) = split(into.text)
            into.text = text; into.fix = fix
        }
        into.done = true
    }

    /** Потоковый ответ (SSE). Возвращает HTTP-код. */
    private fun stream(key: String, system: String, user: String, into: Explain): Int {
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
                .put("max_tokens", 300)
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
                    into.text = split(sb.toString()).first
                }
            }
            return code
        } finally { c.disconnect() }
    }

    /** Текст без строки `FIX:` и сама команда-исправление. */
    internal fun split(raw: String): Pair<String, String?> {
        val lines = raw.trimEnd().lines()
        val i = lines.indexOfLast { it.trimStart().startsWith("FIX:") || "FIX:".startsWith(it.trim()) && it.isNotBlank() }
        if (i < 0) return raw.trim() to null
        val fix = lines[i].substringAfter("FIX:", "").trim().trim('`').trim().takeIf { it.isNotEmpty() }
        return lines.filterIndexed { j, _ -> j != i }.joinToString("\n").trim() to fix
    }
}
