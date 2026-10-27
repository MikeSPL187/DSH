package com.dsh.core.protocol

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Вход в Host:
 * локальный — запуск даёт адрес с token → `GET /?token=` → подписанный cookie;
 * удалённый — токен устройства шлюза, отдельный CookieJar.
 */
class HostCookieJar : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostKey = url.host + ":" + url.port
        val existing = cookieStore.getOrPut(hostKey) { mutableListOf() }
        synchronized(existing) {
            cookies.forEach { newCookie ->
                existing.removeAll { it.name == newCookie.name }
                existing.add(newCookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostKey = url.host + ":" + url.port
        val list = cookieStore[hostKey] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
            // Отбросить просроченные cookie.
            list.removeAll { it.expiresAt < now }
            return list.toList()
        }
    }

    fun clearHost(host: String, port: Int) {
        cookieStore.remove("$host:$port")
    }

}

/**
 * Обменять token запуска на cookie сессии Host.
 *
 * Cookie должен попасть в CookieJar **того клиента, который шлёт запросы**: раньше создавался
 * новый [HostCookieJar], а запросы шли через общий клиент, и отчёт всегда был
 * «0 cookies» (аудит A-05/N-17). Теперь читается jar самого [client].
 */
class HostAuthenticator(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(HostCookieJar())
        .build(),
) {
    suspend fun exchangeTokenForCookie(
        baseUrl: String,
        token: String?
    ): RemoteResult<List<Cookie>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val parsedUrl = baseUrl.toHttpUrlOrNull()
            ?: return@withContext RemoteResult.Failure("dsh/auth/failed", "Invalid baseUrl")

        // token кодируется как параметр запроса, а не склейкой строк (& и # не обрежут и не подменят его).
        val urlWithToken = if (token.isNullOrEmpty()) {
            parsedUrl
        } else {
            parsedUrl.newBuilder().setQueryParameter("token", token).build()
        }

        val request = Request.Builder()
            .url(urlWithToken)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 200..399) {
                    val cookies = client.cookieJar.loadForRequest(parsedUrl)
                    if (!token.isNullOrEmpty() && cookies.isEmpty()) {
                        RemoteResult.Failure("dsh/auth/failed", "Host did not issue a session cookie")
                    } else {
                        RemoteResult.Success(cookies)
                    }
                } else if (response.code == 401 || response.code == 403) {
                    RemoteResult.Failure("dsh/auth/failed", "Authentication rejected (HTTP ${response.code})")
                } else {
                    RemoteResult.Failure("dsh/net/error", "Server returned HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            // В тексте исключения может быть полный адрес с token — наружу его не отдаём.
            RemoteResult.Failure("dsh/net/timeout", "Network error during auth exchange: ${e.javaClass.simpleName}")
        }
    }
}
