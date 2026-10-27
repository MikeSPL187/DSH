package com.dsh

import java.io.File

/**
 * Адреса встроенного браузера. Политика та же, что в `network_security_config`: открытый HTTP — только
 * для сервера на телефоне (`localhost`, `127.0.0.1`, `[::1]`), остальное — HTTPS. Файлы рабочей папки
 * открываются по виртуальному адресу [WORKSPACE_ORIGIN] и отдаются только изнутри неё.
 */
object BrowserUrls {
    const val WORKSPACE_HOST = "dsh.workspace"
    const val WORKSPACE_ORIGIN = "https://$WORKSPACE_HOST/"

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0")

    /** Сервер на этом телефоне (в Linux DSH): ему можно открытый HTTP. */
    fun isLoopback(host: String?): Boolean = host != null && host.lowercase() in LOOPBACK

    /**
     * Адрес из строки ввода: без схемы — `http://` для localhost и `IP:порт`, `https://` для остального.
     * null — пусто, не http(s) (`file:`, `intent:`, `javascript:`…) или открытый HTTP не на localhost.
     */
    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val withScheme = when {
            raw.contains("://") -> raw
            raw.startsWith("localhost", ignoreCase = true) || Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(raw) || raw.startsWith("[::1]") -> "http://$raw"
            else -> "https://$raw"
        }
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        return when {
            scheme == "https" -> uri.toString()
            scheme == "http" && isLoopback(host) -> uri.toString()
            else -> null
        }
    }

    /** Можно ли открыть [url] внутри: https, http на localhost или файл рабочей папки. */
    fun allowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.host != null
            "http" -> isLoopback(uri.host)
            else -> false
        }
    }

    /** Адрес файла рабочей папки: путь от корня, каждая часть закодирована. */
    fun workspaceUrl(root: File, file: File): String? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!target.path.startsWith(base.path + File.separator)) return null
        val rel = target.path.removePrefix(base.path + File.separator).split(File.separatorChar)
        return WORKSPACE_ORIGIN + rel.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    /**
     * Файл для запроса к [WORKSPACE_ORIGIN]: только существующий обычный файл внутри [root]
     * (`..`, ссылки наружу и папки — null). Путь `/` → `index.html`.
     */
    fun resolveWorkspace(root: File, path: String): File? {
        val decoded = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        val rel = decoded.trimStart('/').ifEmpty { "index.html" }
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(base, rel).canonicalFile }.getOrNull() ?: return null
        val inside = target.path.startsWith(base.path + File.separator)
        val file = if (inside && target.isDirectory) File(target, "index.html") else target
        return file.takeIf { inside && it.isFile }
    }

    /** MIME по расширению для ответов рабочей папки; неизвестное — как двоичные данные. */
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "wasm" -> "application/wasm"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}
