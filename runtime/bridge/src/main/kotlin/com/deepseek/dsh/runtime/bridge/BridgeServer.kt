package com.deepseek.dsh.runtime.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Локальный BridgeServer на loopback (127.0.0.1, случайный порт): принимает JSON-RPC
 * от плагина dsh-android-bridge в контейнере Node.
 *
 * Авторизация: каждый запрос несёт [BridgeRequest.auth]; значение [BridgeAuth] меняет при каждом запуске
 * Host и пишет в `/root/.dsh/.bridge_token` гостя. Без заданного токена отклоняются все
 * запросы (fail closed), режима без авторизации нет.
 *
 * Пределы ресурсов (аудит F-06/N-04): к порту loopback может подключиться любое приложение, токен защищает только RPC,
 * но не сами accept/readLine. Поэтому: число одновременных соединений ограничено (сверх — сразу закрываются), первая строка
 * должна прийти быстро, длина строки ограничена, неавторизованное соединение с неразборным содержимым сразу рвётся,
 * вывод процессов ограничен (поток stdout не раздует память приложения).
 *
 * @param workspacePath папка, к которой в Host привязан `/workspace`; совпадает с Host.
 */
class BridgeServer(
    private val context: Context,
    private val tokenFile: File? = null,
    private val port: Int = 0, // 0 — случайный свободный порт
    private val workspacePath: String? = null,
    private val limits: Limits = Limits(),
) {
    /** Пределы соединений и вывода; тесты могут уменьшить. */
    data class Limits(
        val maxClients: Int = 32,
        val firstLineTimeoutMs: Int = 15_000,
        val idleTimeoutMs: Int = 60_000,
        val maxLineBytes: Int = 4 * 1024 * 1024,
        val maxOutputChars: Int = 8 * 1024 * 1024,
        val maxProcessTimeoutMs: Long = 30L * 60 * 1000,
    )

    private var serverSocket: ServerSocket? = null
    private val clientPermits = Semaphore(limits.maxClients)
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = ThreadPoolExecutor(
        0, limits.maxClients, 30, TimeUnit.SECONDS, SynchronousQueue(),
    ) { runnable -> Thread(runnable, "dsh-bridge-client").apply { isDaemon = true } }
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var isRunning = false

    /** Токен этого запуска Host; до [start] — null, и все запросы отклоняются. */
    @Volatile
    private var expectedToken: String? = null

    val localPort: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (isRunning) return localPort

        // При каждом запуске Host токен меняется и пишется в DSH_HOME гостя. Патчи Host передают его
        // в запросах; нет файла токена — отказ: «забыли передать токен» не должно превращаться в режим без авторизации.
        expectedToken = tokenFile?.let { file ->
            BridgeAuth.newToken().also { BridgeAuth.writeTokenFile(file, it) }
        }

        // Явная привязка к 127.0.0.1: ServerSocket(port) по умолчанию слушает все интерфейсы, и одного комментария
        // «loopback» мало; возможности моста — команды уровня устройства, в локальную сеть их открывать нельзя.
        val ss = ServerSocket()
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        serverSocket = ss
        isRunning = true

        Thread({
            while (isRunning && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                // Соединения сверх предела закрываются сразу, а не ждут в очереди, занимая память и потоки.
                if (!clientPermits.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                try {
                    executor.execute {
                        try {
                            handleClient(client)
                        } finally {
                            clientPermits.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    clientPermits.release()
                    runCatching { client.close() }
                }
            }
        }, "dsh-bridge-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        activeClients.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        activeClients.add(socket)
        try {
            socket.use { s ->
                s.soTimeout = limits.firstLineTimeoutMs
                val input = s.getInputStream().buffered()
                val writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                fun send(response: BridgeResponse) {
                    writer.write(json.encodeToString(BridgeResponse.serializer(), response))
                    writer.write("\n")
                    writer.flush()
                }
                var authenticated = false
                while (isRunning) {
                    // Таймаут, слишком длинная строка и EOF завершают соединение.
                    val line = readBoundedLine(input, limits.maxLineBytes) ?: return
                    if (line.isBlank()) continue
                    val request = runCatching { json.decodeFromString<BridgeRequest>(line) }.getOrNull()
                    if (request == null) {
                        // Неавторизованному соединению ошибка разбора не возвращается — сразу разрыв.
                        if (!authenticated) return
                        send(BridgeResponse(id = "error", error = BridgeError(code = -32700, message = "Parse error")))
                        continue
                    }
                    val response = dispatchRequest(request)
                    send(response)
                    // При ошибке авторизации — разрыв, перебирать токены в том же соединении нельзя.
                    if (response.error?.code == BridgeAuth.UNAUTHORIZED_CODE) return
                    authenticated = true
                    s.soTimeout = limits.idleTimeoutMs
                }
            }
        } catch (_: IOException) {
            // Таймаут, разрыв с той стороны или слишком длинная строка: соединение уже закрыто.
        } finally {
            activeClients.remove(socket)
        }
    }

    /** Прочитать строку (до `\n`, без `\r`); длиннее [maxBytes] — исключение, EOF без данных — null. */
    private fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            }
            if (next == '\n'.code) {
                return buffer.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            }
            buffer.write(next)
            if (buffer.size() > maxBytes) throw IOException("bridge request line too long")
        }
    }

    fun dispatchRequest(request: BridgeRequest): BridgeResponse {
        if (!BridgeAuth.verify(expectedToken, request.auth)) {
            return BridgeResponse(
                id = request.id,
                error = BridgeError(
                    code = BridgeAuth.UNAUTHORIZED_CODE,
                    message = "bridge token missing or invalid",
                ),
            )
        }
        return try {
            when (request.method) {
                BridgeMethod.GET_DEVICE_INFO.methodName -> {
                    val info = buildJsonObject {
                        put("brand", Build.BRAND)
                        put("model", Build.MODEL)
                        put("sdkInt", Build.VERSION.SDK_INT)
                        put("release", Build.VERSION.RELEASE)
                    }
                    BridgeResponse(id = request.id, result = info)
                }

                BridgeMethod.GET_CLIPBOARD.methodName -> {
                    if (!BridgePolicy.isClipboardReadAllowed(context)) {
                        return BridgeResponse(
                            id = request.id,
                            error = BridgeError(
                                code = CLIPBOARD_DENIED_CODE,
                                message = "Clipboard access is disabled in DSH settings (Mobile runtime)",
                            ),
                        )
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    val res = buildJsonObject { put("text", text) }
                    BridgeResponse(id = request.id, result = res)
                }

                BridgeMethod.SET_CLIPBOARD.methodName -> {
                    val text = (request.params as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: ""
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("DSH", text))
                    val res = buildJsonObject { put("success", true) }
                    BridgeResponse(id = request.id, result = res)
                }

                BridgeMethod.SHOW_NOTIFICATION.methodName -> {
                    val title = (request.params as? JsonObject)?.get("title")?.jsonPrimitive?.content ?: com.deepseek.dsh.core.model.Tr.t("notice", "уведомление")
                    val content = (request.params as? JsonObject)?.get("content")?.jsonPrimitive?.content ?: ""
                    showNotification(title, content)
                    val res = buildJsonObject { put("success", true) }
                    BridgeResponse(id = request.id, result = res)
                }

                BridgeMethod.SHARE_TEXT.methodName -> {
                    val text = (request.params as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: ""
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(Intent.createChooser(intent, com.deepseek.dsh.core.model.Tr.t("Share", "Поделиться")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    val res = buildJsonObject { put("success", true) }
                    BridgeResponse(id = request.id, result = res)
                }

                BridgeMethod.EXEC_PROOT.methodName -> {
                    val params = request.params as? JsonObject
                    val argv = (params?.get("argv") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
                    val cwd = params?.get("cwd")?.jsonPrimitive?.content ?: "/"
                    val timeoutMs = params?.get("timeoutMs")?.jsonPrimitive?.content?.toLongOrNull() ?: 120_000L
                    BridgeResponse(id = request.id, result = execProot(argv, cwd, timeoutMs))
                }
                BridgeMethod.EXEC_SHELL.methodName -> {
                    val params = request.params as? JsonObject
                    val command = params?.get("command")?.jsonPrimitive?.content ?: ""
                    val cwd = params?.get("cwd")?.jsonPrimitive?.content ?: ""
                    val timeoutMs = params?.get("timeoutMs")?.jsonPrimitive?.content?.toLongOrNull() ?: 120_000L
                    val result = execShell(command, cwd, timeoutMs)
                    BridgeResponse(id = request.id, result = result)
                }

                else -> {
                    BridgeResponse(
                        id = request.id,
                        error = BridgeError(code = -32601, message = "Method not found: ${request.method}")
                    )
                }
            }
        } catch (e: Exception) {
            BridgeResponse(
                id = request.id,
                error = BridgeError(code = -32000, message = e.message ?: "Execution error")
            )
        }
    }


    /**
     * Выполнить команду инструмента bash.
     *
     * Только через `/bin/sh` в proot: инструмент bash агента работает с рабочей папкой (`/workspace`)
     * и нуждается в git/diff/grep из rootfs; раньше использовался `/system/bin/sh` Android —
     * не было git, пути рабочей папки не совпадали, и workspace/changes не видел изменений.
     */
    private fun execShell(command: String, cwd: String, timeoutMs: Long): JsonObject {
        val nativeLib = context.applicationInfo.nativeLibraryDir
        val rootfs = java.io.File(context.filesDir, "rootfs").absolutePath
        val visibleWorkspace = resolvedWorkspacePath()
        val passwd = java.io.File(context.filesDir, "runtime/etc/passwd").absolutePath
        val group = java.io.File(context.filesDir, "runtime/etc/group").absolutePath
        val env = mutableListOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "DSH_HOME=/root/.dsh",
            "PATH=/opt/dsh/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "TMPDIR=/root/.dsh/tmp",
            "/bin/sh",
            "-lc",
            command,
        )
        val proot = mutableListOf(
            "$nativeLib/libproot.so", "-r", rootfs,
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", "$visibleWorkspace:/workspace",
            "--link2symlink",
        )
        if (java.io.File(passwd).isFile) {
            proot.addAll(listOf("-b", "$passwd:/etc/passwd"))
        }
        if (java.io.File(group).isFile) {
            proot.addAll(listOf("-b", "$group:/etc/group"))
        }
        proot.addAll(listOf("-w", cwd.ifBlank { "/workspace" }))
        proot.addAll(env)
        val builder = ProcessBuilder(proot)
        builder.redirectErrorStream(false)
        builder.environment()["PROOT_LOADER"] = "$nativeLib/libproot-loader.so"
        // libproot.so зависит от libtalloc и других библиотек в nativeLibraryDir; при прямом exec нужно явно задать
        // LD_LIBRARY_PATH, иначе «CANNOT LINK EXECUTABLE ... libtalloc.so not found».
        builder.environment()["LD_LIBRARY_PATH"] = nativeLib
        builder.environment()["PROOT_TMP_DIR"] =
            java.io.File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        return runProcess(builder, timeoutMs)
    }
    private fun execProot(argv: List<String>, cwd: String, timeoutMs: Long): JsonObject {
        val nativeLib = context.applicationInfo.nativeLibraryDir
        val rootfs = java.io.File(context.filesDir, "rootfs").absolutePath
        val visible = resolvedWorkspacePath()
        val command = mutableListOf(
            "$nativeLib/libproot.so", "-r", rootfs,
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", "$visible:/workspace", "--link2symlink", "-w", cwd
        )
        command.addAll(argv)
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(false)
        builder.environment()["PROOT_LOADER"] = "$nativeLib/libproot-loader.so"
        builder.environment()["LD_LIBRARY_PATH"] = nativeLib
        builder.environment()["PROOT_TMP_DIR"] = java.io.File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        return runProcess(builder, timeoutMs)
    }

    /** Совпадает с привязкой `/workspace` Host; не передано — общая рабочая папка по умолчанию. */
    private fun resolvedWorkspacePath(): String = (
        workspacePath?.takeIf { it.isNotBlank() }
            ?: java.io.File(android.os.Environment.getExternalStorageDirectory(), "Documents/DSH-Workspace").absolutePath
        ).also {
            // `-b путь:/workspace`: двоеточие в пути proot принял бы за разделитель и смонтировал папку в чужое место.
            require(':' !in it) { "workspace path must not contain ':'" }
        }

    /**
     * Запустить процесс и собрать вывод. Вывод обрезается по пределу символов, но канал читается до конца (иначе процесс
     * встанет на заполненном канале); по таймауту — принудительное завершение; результат читается после окончания потоков чтения, без гонок.
     */
    internal fun runProcess(builder: ProcessBuilder, timeoutMs: Long): JsonObject {
        val effectiveTimeout = timeoutMs.coerceIn(1_000, limits.maxProcessTimeoutMs)
        val process = builder.start()
        val stdout = BoundedText(limits.maxOutputChars)
        val stderr = BoundedText(limits.maxOutputChars)
        val outThread = Thread { stdout.drain(process.inputStream) }.apply { isDaemon = true }
        val errThread = Thread { stderr.drain(process.errorStream) }.apply { isDaemon = true }
        outThread.start(); errThread.start()
        val finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(2_000); errThread.join(2_000)
        return buildJsonObject {
            put("exitCode", if (finished) process.exitValue() else -1)
            put("stdout", stdout.text())
            put("stderr", stderr.text())
            put("timedOut", !finished)
            if (stdout.truncated || stderr.truncated) put("truncated", true)
        }
    }

    /** Потокобезопасный ограниченный сборщик вывода. */
    internal class BoundedText(private val limit: Int) {
        private val builder = StringBuilder()
        @Volatile
        var truncated = false
            private set

        fun drain(stream: InputStream) {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(8 * 1024)
                    while (true) {
                        val read = reader.read(chunk)
                        if (read < 0) break
                        synchronized(this) {
                            val room = limit - builder.length
                            if (room > 0) builder.append(chunk, 0, minOf(room, read))
                            if (read > room) truncated = true
                        }
                    }
                }
            }
        }

        @Synchronized
        fun text(): String =
            if (truncated) builder.toString() + "\n[output truncated at $limit chars]" else builder.toString()
    }    private fun showNotification(title: String, content: String) {
        val channelId = "dsh_bridge_notify"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, com.deepseek.dsh.core.model.Tr.t("DSH device notifications", "Уведомления DSH"), NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            // Пометка «Плагин:» (решение владельца): иначе плагин Host выдавал себя за DSH («Агент ждёт одобрения»).
            .setContentTitle(com.deepseek.dsh.core.model.Tr.t("Plugin: ", "Плагин: ") + title.take(MAX_NOTIFICATION_TITLE))
            .setContentText(content.take(MAX_NOTIFICATION_TEXT))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        // Свой тег и несколько мест по кругу. Раньше id = время % 10000 совпадал с уведомлением службы Host (1001)
        // и уведомлениями DSH (2000+) и заменял их, а поток вызовов исчерпывал предел системы (~50 уведомлений
        // на приложение) — и запрос одобрения агента в фоне уже не показывался.
        nm.notify(NOTIFICATION_TAG, notificationSlot.getAndIncrement().mod(MAX_NOTIFICATIONS), notification)
    }

    private val notificationSlot = java.util.concurrent.atomic.AtomicInteger()
}





/** Код ошибки моста: чтение буфера обмена запрещено настройкой пользователя. */
internal const val CLIPBOARD_DENIED_CODE = -32010

/** Тег уведомлений от плагинов Host: их id не пересекаются с уведомлениями самого DSH. */
const val NOTIFICATION_TAG = "dsh_bridge"

/** Сколько уведомлений плагинов видно одновременно; следующее заменяет самое старое. */
const val MAX_NOTIFICATIONS = 5

private const val MAX_NOTIFICATION_TITLE = 120
private const val MAX_NOTIFICATION_TEXT = 1_000
