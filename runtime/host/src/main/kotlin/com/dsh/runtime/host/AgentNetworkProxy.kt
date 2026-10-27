package com.dsh.runtime.host

import com.dsh.runtime.bridge.BridgeAuth
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.Semaphore

/**
 * Сеть агента (G-50, гигиена): HTTP(S)-прокси в процессе приложения на 127.0.0.1. Host получает его в
 * `HTTPS_PROXY`/`HTTP_PROXY` (и `NODE_USE_ENV_PROXY=1` для fetch Node), поэтому модель, npm, pip, git и curl
 * ходят через него; [decide] решает по имени хоста, пускать ли соединение. Имена разрешает Android —
 * внутри proot DNS не работает.
 *
 * Это не граница: программа, открывающая сокет напрямую в обход переменных окружения, прокси не увидит.
 * Настоящая граница — отдельный UID или VM (docs/security/ISOLATION_G50.ru.md).
 */
class AgentNetworkProxy(
    private val decide: (host: String) -> Boolean,
    private val onBlocked: (host: String) -> Unit = {},
    private val connect: (host: String, port: Int) -> Socket = { host, port ->
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    },
) {
    /** Пароль прокси: другие приложения на телефоне через этот порт не ходят. */
    val token: String = BridgeAuth.newToken()

    private var server: ServerSocket? = null
    private val permits = Semaphore(MAX_CLIENTS)

    val port: Int get() = server?.localPort ?: 0

    /** Адрес для переменных окружения Host: `http://dsh:<token>@127.0.0.1:<port>`. */
    val url: String get() = "http://$USER:$token@127.0.0.1:$port"

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                if (!permits.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                Thread({
                    try {
                        handle(client)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                        permits.release()
                    }
                }, "dsh-agent-proxy").apply { isDaemon = true }.start()
            }
        }, "dsh-agent-proxy-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = HEADER_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return reply(output, 400, "Bad Request")
        val (method, target, version) = parts
        val headers = lines.drop(1).filter { ':' in it }
        val auth = headers.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (!authorized(auth)) {
            return reply(output, 407, "Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"dsh\"\r\n")
        }
        if (method.equals("CONNECT", ignoreCase = true)) {
            val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            val port = target.substringAfterLast(':').toIntOrNull() ?: return reply(output, 400, "Bad Request")
            if (!allowed(host)) return blocked(output, host)
            val upstream = runCatching { connect(host, port) }.getOrNull() ?: return reply(output, 502, "Bad Gateway")
            upstream.use {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                pipe(client, upstream)
            }
            return
        }
        val uri = runCatching { URI(target) }.getOrNull()
        if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return reply(output, 400, "Bad Request")
        }
        val host = uri.host
        if (!allowed(host)) return blocked(output, host)
        val upstream = runCatching { connect(host, if (uri.port > 0) uri.port else 80) }.getOrNull()
            ?: return reply(output, 502, "Bad Gateway")
        upstream.use {
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val forwarded = buildString {
                append("$method $path $version\r\n")
                headers.filterNot {
                    it.startsWith("Proxy-", ignoreCase = true) || it.startsWith("Connection:", ignoreCase = true)
                }.forEach { append(it).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            upstream.getOutputStream().write(forwarded.toByteArray(Charsets.ISO_8859_1))
            pipe(client, upstream)
        }
    }

    private fun allowed(host: String): Boolean = runCatching { decide(host.lowercase().trimEnd('.')) }.getOrDefault(false)

    private fun blocked(output: OutputStream, host: String) {
        runCatching { onBlocked(host.lowercase().trimEnd('.')) }
        reply(output, 403, "Forbidden", body = "DSH: network access to $host is not allowed for the agent (Settings → Agent → Agent network)\n")
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return false
        return decoded.substringBefore(':') == USER && BridgeAuth.verify(token, decoded.substringAfter(':'))
    }

    /** Заголовок запроса до пустой строки; длиннее [MAX_HEAD_BYTES] или обрыв — null. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            if (buffer.size() > MAX_HEAD_BYTES) return null
        }
    }

    private fun reply(output: OutputStream, code: Int, reason: String, extra: String = "", body: String = "") {
        val bytes = body.toByteArray()
        runCatching {
            output.write("HTTP/1.1 $code $reason\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
        }
    }

    /** Перекачка в обе стороны до закрытия любой из них. */
    private fun pipe(client: Socket, upstream: Socket) {
        client.soTimeout = 0
        upstream.soTimeout = 0
        val back = Thread({
            runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
            runCatching { client.shutdownOutput() }
        }, "dsh-agent-proxy-down").apply { isDaemon = true; start() }
        runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }
        runCatching { upstream.shutdownOutput() }
        back.join(IDLE_JOIN_MS)
    }

    companion object {
        const val USER = "dsh"
        private const val MAX_CLIENTS = 64
        private const val MAX_HEAD_BYTES = 32 * 1024
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val IDLE_JOIN_MS = 30L * 60 * 1000
    }
}

/**
 * Окружение Host для прокси [url]: Node (`NODE_USE_ENV_PROXY`), npm/pnpm, git, pip и curl берут `*_PROXY`;
 * loopback (мост, сам Host) — напрямую.
 */
fun agentProxyEnv(url: String): Map<String, String> {
    val direct = "127.0.0.1,localhost,::1"
    return mapOf(
        "HTTPS_PROXY" to url, "HTTP_PROXY" to url, "https_proxy" to url, "http_proxy" to url,
        "NO_PROXY" to direct, "no_proxy" to direct,
        "NODE_USE_ENV_PROXY" to "1",
    )
}

/** [host] — это [entry] или его поддомен (`api.github.com` для `github.com`); без учёта регистра. */
fun hostMatches(host: String, entries: Collection<String>): Boolean {
    val h = host.lowercase().trimEnd('.')
    return entries.any { raw ->
        val e = raw.lowercase().trim().trimEnd('.').removePrefix("*.")
        e.isNotEmpty() && (h == e || h.endsWith(".$e"))
    }
}
