package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Запросы на слияние через GitHub REST API из процесса приложения: токен уходит только на `api.github.com`,
 * в Linux не попадает. Владелец и репозиторий берутся из проверенного адреса `origin` ([GitHubPush.Target]).
 */
class GitHubPulls(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val api: String = "https://api.github.com",
) {
    /** Открытый запрос на слияние. */
    data class Pull(val number: Int, val url: String, val title: String, val draft: Boolean)

    sealed interface Failure {
        /** Токен отозван или истёк — нужен новый вход. */
        data object Auth : Failure
        /** Нет доступа к репозиторию (в том числе организация не одобрила приложение). */
        data object NoAccess : Failure
        /** Открытый запрос из этой ветки уже есть. */
        data object Exists : Failure
        /** В ветке нет коммитов относительно базовой. */
        data object NoCommits : Failure
        /** Черновики в этом репозитории недоступны (закрытый репозиторий на бесплатном тарифе). */
        data object DraftUnsupported : Failure
        data class Other(val message: String) : Failure
    }

    class PullException(val failure: Failure) : Exception(failure.toString())

    /** Ветка по умолчанию репозитория — база запроса. */
    suspend fun defaultBranch(token: String, owner: String, repo: String): Result<String> = call {
        val (code, json) = request(token, "/repos/$owner/$repo")
        check(code, json)
        (json as JsonObject)["default_branch"]?.jsonPrimitive?.contentOrNull ?: error("no default_branch")
    }

    /** Открытый запрос из ветки [branch]; null — его нет. */
    suspend fun findOpen(token: String, owner: String, repo: String, branch: String): Result<Pull?> = call {
        val head = URLEncoder.encode("$owner:$branch", "UTF-8")
        val (code, json) = request(token, "/repos/$owner/$repo/pulls?state=open&per_page=1&head=$head")
        check(code, json)
        (json as? JsonArray)?.firstOrNull()?.let { parsePull(it.jsonObject) }
    }

    suspend fun create(token: String, owner: String, repo: String, branch: String, base: String, title: String, body: String, draft: Boolean): Result<Pull> = call {
        val payload = buildJsonObject {
            put("title", title)
            put("head", branch)
            put("base", base)
            put("body", body)
            put("draft", draft)
            put("maintainer_can_modify", true)
        }
        val (code, json) = request(token, "/repos/$owner/$repo/pulls", payload.toString())
        check(code, json)
        parsePull(json.jsonObject) ?: error("no pull in answer")
    }

    private suspend fun <T> call(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    private fun request(token: String, path: String, body: String? = null): Pair<Int, JsonElement> {
        val builder = Request.Builder().url(api + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use { r ->
            r.code to Json.parseToJsonElement(r.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    private fun check(code: Int, json: JsonElement) {
        if (code in 200..299) return
        throw PullException(failureOf(code, json as? JsonObject ?: JsonObject(emptyMap())))
    }

    companion object {
        fun parsePull(json: JsonObject): Pull? {
            val number = json["number"]?.jsonPrimitive?.intOrNull ?: return null
            val url = json["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://github.com/") } ?: return null
            return Pull(number, url, json["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), json["draft"]?.jsonPrimitive?.booleanOrNull ?: false)
        }

        /** Ответ с ошибкой — понятная причина: 422 различается по тексту ошибок GitHub. */
        fun failureOf(code: Int, json: JsonObject): Failure {
            val message = json["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val details = (json["errors"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { it["message"]?.jsonPrimitive?.contentOrNull ?: it["code"]?.jsonPrimitive?.contentOrNull }
            }
            val all = (listOf(message) + details).joinToString(" ").lowercase()
            return when {
                code == 401 -> Failure.Auth
                code == 403 || code == 404 -> Failure.NoAccess
                code == 422 && "already exists" in all -> Failure.Exists
                code == 422 && "no commits between" in all -> Failure.NoCommits
                code == 422 && "draft" in all -> Failure.DraftUnsupported
                else -> Failure.Other(details.firstOrNull() ?: message.ifEmpty { "HTTP $code" })
            }
        }
    }
}
