package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Вход в GitHub по одноразовому коду (OAuth device flow): приложение получает код, пользователь вводит его на
 * github.com/login/device, приложение ждёт токен. Пароль GitHub приложение не видит; токен — только в памяти,
 * сохраняет его вызывающий (Android Keystore).
 *
 * @param clientId client id OAuth App владельца сборки; пустой — вход не настроен.
 */
class GitHubAuth(
    private val clientId: String,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    /** Код для пользователя и то, чем его ждать. [intervalSec] — не чаще этого спрашивать GitHub. */
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresInSec: Int, val intervalSec: Int)

    /** Ответ на очередной опрос. */
    sealed interface Poll {
        data object Pending : Poll
        /** GitHub просит опрашивать реже: интервал +5 с. */
        data object SlowDown : Poll
        data class Token(val token: String) : Poll
        data object Denied : Poll
        data object Expired : Poll
        data class Failed(val message: String) : Poll
    }

    val configured: Boolean get() = clientId.isNotBlank()

    /** Начать вход: код для пользователя. Права — `repo` (отправка и запросы на слияние, в том числе в закрытые). */
    suspend fun start(scope: String = "repo"): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            check(configured) { "GitHub sign-in is not configured in this build" }
            val body = FormBody.Builder().add("client_id", clientId).add("scope", scope).build()
            val json = post("https://github.com/login/device/code", body)
            parseDeviceCode(json) ?: error(json["error_description"]?.jsonPrimitive?.contentOrNull ?: "GitHub did not return a code")
        }
    }

    /** Один опрос: выдан ли токен по [code]. */
    suspend fun poll(code: DeviceCode): Poll = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            parsePoll(post("https://github.com/login/oauth/access_token", body))
        }.getOrElse { Poll.Failed(it.message.orEmpty()) }
    }

    /** Логин владельца токена — подпись «@login» в настройках. */
    suspend fun login(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://api.github.com/user")
                .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { r ->
                check(r.isSuccessful) { "GitHub ${r.code}" }
                Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["login"]?.jsonPrimitive?.contentOrNull ?: error("no login")
            }
        }
    }

    private fun post(url: String, body: FormBody): JsonObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
        return http.newCall(request).execute().use { r -> Json.parseToJsonElement(r.body?.string().orEmpty().ifBlank { "{}" }).jsonObject }
    }

    companion object {
        /** Ответ `login/device/code`; null — кода нет (ошибка в ответе). */
        fun parseDeviceCode(json: JsonObject): DeviceCode? {
            fun s(k: String) = json[k]?.jsonPrimitive?.contentOrNull
            fun i(k: String) = json[k]?.jsonPrimitive?.intOrNull
            return DeviceCode(
                deviceCode = s("device_code") ?: return null,
                userCode = s("user_code") ?: return null,
                verificationUri = s("verification_uri")?.takeIf { it.startsWith("https://github.com/") } ?: "https://github.com/login/device",
                expiresInSec = i("expires_in") ?: 900,
                intervalSec = (i("interval") ?: 5).coerceAtLeast(5),
            )
        }

        /** Ответ `login/oauth/access_token` по правилам device flow. */
        fun parsePoll(json: JsonObject): Poll {
            json["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return Poll.Token(it) }
            return when (val e = json["error"]?.jsonPrimitive?.contentOrNull) {
                "authorization_pending" -> Poll.Pending
                "slow_down" -> Poll.SlowDown
                "access_denied" -> Poll.Denied
                "expired_token" -> Poll.Expired
                else -> Poll.Failed(json["error_description"]?.jsonPrimitive?.contentOrNull ?: e ?: "unknown")
            }
        }
    }
}
