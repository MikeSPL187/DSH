package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.Tr
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.RemoteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Вызовы Host для репозиториев: RPC с [Result] и разбор конверта ответа
 * `{result: {ok, value, error}}`. Один экземпляр на [DshRepository], его делят все области.
 */
internal class HostCalls(private val client: DshClientProtocol) {

    /** Сырой ответ RPC; ошибка сети или Host — [Result.failure] с кодом и текстом. */
    suspend fun rpc(hostId: String, method: String, args: JsonObject = EMPTY): Result<String> =
        when (val res = client.rpcRaw(hostId, method, args.toString())) {
            is RemoteResult.Success -> Result.success(res.data)
            is RemoteResult.Failure -> Result.failure(IllegalStateException("${res.code}: ${res.message}"))
        }

    /** `result.value` ответа или null, если вызов не удался или ответ не разобрать. */
    suspend fun value(hostId: String, method: String, args: JsonObject = EMPTY): JsonElement? {
        val raw = rpc(hostId, method, args).getOrNull() ?: return null
        val root = runCatching { hostJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return (root["result"] as? JsonObject)?.get("value")
    }

    /** Вызов завершён и Host ответил `ok: true` (по разобранному конверту, а не по подстроке). */
    suspend fun ok(hostId: String, method: String, args: JsonObject = EMPTY): Boolean = valueOrThrow(hostId, method, args).isSuccess

    /** `result.value` для вызовов, где важна причина отказа: при `ok=false` — исключение с кодом Host. */
    suspend fun valueOrThrow(hostId: String, method: String, args: JsonObject = EMPTY): Result<JsonElement?> =
        rpc(hostId, method, args).mapCatching(::remoteValueOrThrow)

    private fun remoteValueOrThrow(raw: String): JsonElement? {
        val root = hostJson.parseToJsonElement(raw) as? JsonObject
            ?: error(Tr.t("Could not parse the Host response", "Не удалось разобрать ответ Host"))
        val result = root["result"] as? JsonObject
            ?: error(Tr.t("Host response has no result", "В ответе Host нет result"))
        if ((result["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val error = result["error"] as? JsonObject
            error(error?.str("code") ?: Tr.t("Host rejected the request", "Host отклонил запрос"))
        }
        val value = result["value"]
        return if (value == null || value is JsonNull) null else value
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}

/** Ответы Host разбираются мягко: незнакомые поля и нестрогий JSON не ломают разбор. */
internal val hostJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Строковое поле объекта; null, если поля нет или оно не строка/число. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
