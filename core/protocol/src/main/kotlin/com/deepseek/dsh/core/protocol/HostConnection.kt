package com.deepseek.dsh.core.protocol

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Соединение с Host: адрес (меняется, когда локальный Host поднялся на новом порту),
 * HTTP-клиент, JSON и общие вызовы. Клиенты областей API работают поверх него.
 */
internal class HostConnection(
    @Volatile var baseUrl: String,
    val client: OkHttpClient,
    val json: Json = Json { ignoreUnknownKeys = true },
) : HostRpcApi {
    val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override suspend fun authenticate(baseUrl: String, token: String?): RemoteResult<String> {
        val auth = HostAuthenticator(client = client)
        return when (val res = auth.exchangeTokenForCookie(baseUrl, token)) {
            is RemoteResult.Success -> RemoteResult.Success("Authenticated, ${res.data.size} cookies acquired")
            is RemoteResult.Failure -> res
        }
    }

    /** Читаемая ошибка из ответа RPC; null, если её нет. */
    fun extractRpcError(body: String): String? {
        val root = runCatching { json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return null
        val error = (root["result"] as? kotlinx.serialization.json.JsonObject)?.get("error")
            as? kotlinx.serialization.json.JsonObject
        val message = error?.get("message")?.toString()?.trim('"')
        if (!message.isNullOrBlank()) return message
        val diagnostic = error?.get("diagnostic")?.toString()?.trim('"')
        if (!diagnostic.isNullOrBlank()) return diagnostic
        return (root["message"]?.toString())?.trim('"')?.takeIf { it.isNotBlank() }
    }

    override suspend fun rpcRaw(hostId: String, method: String, argsJson: String): RemoteResult<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val args = runCatching { json.parseHostJson(argsJson) }.getOrNull() ?: buildJsonObject {}
            val payload = buildJsonObject {
                put("type", "client-request")
                put("rpcId", java.util.UUID.randomUUID().toString())
                put("method", method)
                put("payload", buildJsonObject { put("args", args) })
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/$method")
                .post(payload.toRequestBody(mediaTypeJson))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) RemoteResult.Success(text)
                    else RemoteResult.Failure("dsh/rpc-failed", "HTTP ${response.code}: ${text.take(200)}")
                }
            } catch (e: Exception) {
                RemoteResult.Failure("dsh/rpc-network", e.message ?: "rpc failed")
            }
        }
}
