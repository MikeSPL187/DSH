package com.dsh.core.protocol

import com.dsh.core.model.AccountBalanceInfo
import com.dsh.core.model.AccountProfileInfo
import com.dsh.core.model.AccountViewInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Аккаунт DeepSeek через Host: состояние, вход, профиль, баланс (`account/...`). */
internal class AccountClient(private val conn: HostConnection) : AccountApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    private fun accountClientMetadata(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("version", "4.0.0")
        put("locale", "en-US")
        put(
            "timezoneOffsetSeconds",
            java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
        )
    }

    private suspend fun accountRpc(
        method: String,
        args: kotlinx.serialization.json.JsonObject,
    ): RemoteResult<kotlinx.serialization.json.JsonObject> = withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure(
                        "dsh/account/http",
                        "Account request failed, HTTP ${response.code}",
                    )
                }
                val bodyStr = response.body?.string() ?: "{}"
                val result = (json.parseHostJson(bodyStr) as? kotlinx.serialization.json.JsonObject)?.get("result")
                    as? kotlinx.serialization.json.JsonObject
                    ?: return@withContext RemoteResult.Failure("dsh/account/malformed", "Malformed account response")
                // Отказ Host (`ok: false`, сбой сервиса аккаунта) — ошибка, а не данные: раньше конверт с ошибкой
                // разбирался как AccountView без status и превращался в «signed-out» (R2-05).
                val ok = result["ok"] as? kotlinx.serialization.json.JsonPrimitive
                if (ok == null || ok.isString || ok.content != "true") {
                    val error = result["error"] as? kotlinx.serialization.json.JsonObject
                    fun text(key: String) = (error?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    return@withContext RemoteResult.Failure(
                        text("code") ?: "dsh/account/rejected",
                        text("message") ?: "Account request was rejected by the Host",
                    )
                }
                // `null` от Host (нет профиля или баланса) — пустой объект: вызывающий видит «нет данных».
                RemoteResult.Success(result["value"] as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap()))
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/account/network", e.message ?: "Account request failed")
        }
    }

    /** AccountView Host; без `status` — null: «вышел из аккаунта» нельзя выводить из неполного ответа. */
    private fun kotlinx.serialization.json.JsonObject.parseAccountView(): AccountViewInfo? {
        val attempt = this["attempt"] as? kotlinx.serialization.json.JsonObject
        return AccountViewInfo(
            status = (this["status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null,
            attemptId = attempt?.get("id")?.toString()?.trim('"'),
            attemptPhase = attempt?.get("phase")?.toString()?.trim('"'),
            authorizeUrl = attempt?.get("authorizeUrl")?.toString()?.trim('"'),
        )
    }

    override suspend fun getAccountState(hostId: String): RemoteResult<AccountViewInfo> {
        return when (val res = accountRpc("account/getState", buildJsonObject { })) {
            is RemoteResult.Success -> res.data.parseAccountView()?.let { RemoteResult.Success(it) }
                ?: RemoteResult.Failure("dsh/account/malformed", "Account state has no status")
            is RemoteResult.Failure -> res
        }
    }

    /**
     * Подписка на поток `account/watch` (потоковый вызов через Gateway mux).
     *
     * Каждый кадр `item` — снимок `AccountView`; `end`/`error` завершают подписку.
     */
    override fun watchAccount(hostId: String): Flow<AccountViewInfo> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        val socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "account/watch")
                        put("payload", buildJsonObject { put("args", buildJsonObject { }) })
                    }
                    webSocket.send(open.toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching {
                        val frame = json.parseHostJson(text) as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        fun field(key: String): String? =
                            (frame[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        if (field("streamId") != streamId) return@runCatching
                        when (field("type")) {
                            "item" -> {
                                val value = frame["value"] as? kotlinx.serialization.json.JsonObject
                                value?.parseAccountView()?.let { trySend(it) }
                            }
                            "error", "end" -> close()
                        }
                    }
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }

                // Host закрыл поток сам (перезапуск, обновление плагина): закрытие нужно подтвердить —
                // без ответа OkHttp не доводит рукопожатие до onClosed, и подписка не восстанавливалась (DSH-08).
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose {
            runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    override suspend fun startAccountSignIn(
        hostId: String,
        callbackOrigin: String,
    ): RemoteResult<AccountViewInfo> {
        val args = buildJsonObject {
            put("client", accountClientMetadata())
            put("callbackOrigin", callbackOrigin)
            put("loginSource", "web")
        }
        return when (val res = accountRpc("account/startSignIn", args)) {
            is RemoteResult.Success -> res.data.parseAccountView()?.let { RemoteResult.Success(it) }
                ?: RemoteResult.Failure("dsh/account/malformed", "Sign-in state has no status")
            is RemoteResult.Failure -> res
        }
    }

    override suspend fun getAccountProfile(hostId: String): RemoteResult<AccountProfileInfo?> {
        val args = buildJsonObject { put("client", accountClientMetadata()) }
        return when (val res = accountRpc("account/getProfile", args)) {
            is RemoteResult.Success -> {
                val value = res.data["value"] as? kotlinx.serialization.json.JsonObject
                if (value == null) {
                    RemoteResult.Success(null)
                } else {
                    RemoteResult.Success(
                        AccountProfileInfo(
                            id = value["id"]?.toString()?.trim('"'),
                            name = value["name"]?.toString()?.trim('"'),
                            contact = value["contact"]?.toString()?.trim('"'),
                            avatarUrl = value["avatarUrl"]?.toString()?.trim('"'),
                        )
                    )
                }
            }
            is RemoteResult.Failure -> res
        }
    }

    override suspend fun getAccountBalance(hostId: String): RemoteResult<AccountBalanceInfo> {
        val args = buildJsonObject { put("client", accountClientMetadata()) }
        fun parseWallets(elem: kotlinx.serialization.json.JsonElement?): List<com.dsh.core.model.DeepSeekWallet> {
            val array = elem as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                com.dsh.core.model.DeepSeekWallet(
                    currency = obj["currency"]?.toString()?.trim('"') ?: "CNY",
                    balance = obj["balance"]?.toString()?.trim('"') ?: "0.00",
                )
            }
        }
        return when (val res = accountRpc("account/getBalance", args)) {
            is RemoteResult.Success -> RemoteResult.Success(
                AccountBalanceInfo(
                    wallets = parseWallets(res.data["value"]),
                    bonusWallets = parseWallets(res.data["bonusWallets"]),
                )
            )
            is RemoteResult.Failure -> res
        }
    }
}
