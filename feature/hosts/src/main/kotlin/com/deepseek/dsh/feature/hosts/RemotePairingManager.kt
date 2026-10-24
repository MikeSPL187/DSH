package com.deepseek.dsh.feature.hosts

import com.deepseek.dsh.core.model.HostDescriptor
import com.deepseek.dsh.core.model.PairPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * v4.0 设计文档 §11.2 & §11.3:
 * 远程网关配对与多主机凭据管理器
 */
class RemotePairingManager(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    fun parseQrCodePayload(qrContent: String): Result<PairPayload> {
        return try {
            val payload = json.decodeFromString<PairPayload>(qrContent)
            if (payload.exp > 0 && payload.exp < System.currentTimeMillis()) {
                Result.failure(IllegalArgumentException("Pairing code has expired"))
            } else {
                Result.success(payload)
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid pairing QR format: ${e.message}"))
        }
    }

    /**
     * 执行配对。
     *
     * 安全约束：
     * - 非回环地址必须是 HTTPS，配对码与设备令牌不走明文；
     * - 二维码带指纹时，只信任与指纹一致的证书，不一致在握手阶段就失败（审计 N-07）；
     * - 网关必须真的返回令牌，绝不自己编一个（审计 N-06）。
     */
    suspend fun executePairing(payload: PairPayload, deviceName: String): Result<HostDescriptor.Remote> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = payload.url.trim().trimEnd('/')
            val httpUrl = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid gateway URL"))
            val loopback = httpUrl.host == "127.0.0.1" || httpUrl.host == "localhost" || httpUrl.host == "::1"
            if (!httpUrl.isHttps && !loopback) {
                return@withContext Result.failure(
                    IllegalArgumentException("Remote pairing requires an HTTPS gateway URL")
                )
            }
            val pin = PairingFingerprint.parse(payload.fp)
            val pairingClient = if (pin != null && httpUrl.isHttps) PairingTls.pinnedClient(client, pin) else client

            val requestBody = buildJsonObject {
                put("pairCode", payload.pair)
                put("deviceName", deviceName)
            }.toString()

            val req = Request.Builder()
                .url("$baseUrl/pair")
                .post(requestBody.toRequestBody(mediaTypeJson))
                .build()

            pairingClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Pairing rejected by gateway (HTTP ${response.code})")
                    )
                }

                val bodyStr = response.body?.string().orEmpty()
                val jsonObject = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull()
                val token = ((jsonObject as? kotlinx.serialization.json.JsonObject)?.get("token")
                    as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeIf { isValidToken(it) }
                    ?: return@withContext Result.failure(
                        IOException("Gateway did not return a valid device token")
                    )

                val remoteHost = HostDescriptor.Remote(
                    id = "host_" + sha256Hex(baseUrl).take(16),
                    name = payload.name,
                    baseUrl = baseUrl,
                    deviceToken = token,
                    tlsFingerprint = payload.fp.takeIf { it.isNotBlank() }
                )
                Result.success(remoteHost)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isValidToken(token: String): Boolean =
        token.length in 16..4096 && token.none { it.isWhitespace() || it.isISOControl() }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
