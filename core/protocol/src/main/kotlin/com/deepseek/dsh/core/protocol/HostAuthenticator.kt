package com.deepseek.dsh.core.protocol

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.0 设计文档 §5.5:
 * 本地：Runtime 启动产出 launcher URL（含 token）→ `GET /?token=` → 签名 cookie
 * 远程：网关设备令牌 / CookieJar 隔离
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
            // 过滤过期 cookie
            list.removeAll { it.expiresAt < now }
            return list.toList()
        }
    }

    fun clearHost(host: String, port: Int) {
        cookieStore.remove("$host:$port")
    }

    fun getCookies(host: String, port: Int): List<Cookie> {
        return cookieStore["$host:$port"]?.toList() ?: emptyList()
    }
}

/**
 * 用启动 token 换取 Host 的会话 cookie。
 *
 * cookie 必须落在**实际发请求的那个客户端**的 CookieJar 里：旧实现默认新建一个
 * [HostCookieJar] 再去读它，而请求走的是 App 共享客户端的 jar，于是永远报告
 * 「0 cookies」（审计 A-05/N-17）。现在默认从 [client] 自己的 jar 读取。
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

        // token 作为查询参数编码，而不是字符串拼接（含 & # 等字符时不会被截断/注入）。
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
            // 异常信息可能带完整 URL（含 token），不向上传递原文。
            RemoteResult.Failure("dsh/net/timeout", "Network error during auth exchange: ${e.javaClass.simpleName}")
        }
    }
}
