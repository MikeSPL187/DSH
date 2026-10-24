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
 * v4.0 设计文档 §10.2:
 * 本地回环 BridgeServer (127.0.0.1 随机端口)，承载 Node 容器侧 dsh-android-bridge 插件发来的 JSON-RPC。
 *
 * 鉴权：每个请求必须携带 [BridgeRequest.auth]，其值由 [BridgeAuth] 在每次 Host
 * 启动时轮换并写入 guest 的 `/root/.dsh/.bridge_token`。未配置 token 时拒绝所有
 * 请求（fail closed），不允许退化成无鉴权模式。
 *
 * 资源边界（审计 F-06/N-04）：任何本机 App 都能连上回环端口，token 只保护 RPC，
 * 不保护 accept/readLine 本身。所以：并发连接数有上限（超出立即关闭），首行必须在
 * 短时间内到达，单行长度有上限，未鉴权连接发来无法解析的内容直接断开，进程输出
 * 有上限（防 stdout 洪水把 App 撑爆）。
 *
 * @param workspacePath Host 里 `/workspace` 实际绑定的目录；与 Host 保持一致。
 */
class BridgeServer(
    private val context: Context,
    private val tokenFile: File? = null,
    private val port: Int = 0, // 0 = 随机可用端口
    private val workspacePath: String? = null,
    private val limits: Limits = Limits(),
) {
    /** 连接与输出上限；测试可以调小。 */
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

    /** 本次 Host 启动使用的 token；[start] 之前为 null，此时拒绝所有请求。 */
    @Volatile
    private var expectedToken: String? = null

    val localPort: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (isRunning) return localPort

        // 每次 Host 启动轮换 token，并写到 guest 的 DSH_HOME。Host 侧补丁按请求
        // 携带；没有 token 文件就拒绝服务，不能因为「忘了传 token」退化成无鉴权。
        expectedToken = tokenFile?.let { file ->
            BridgeAuth.newToken().also { BridgeAuth.writeTokenFile(file, it) }
        }

        // 显式绑定 127.0.0.1：ServerSocket(port) 默认绑定所有网卡，仅靠注释声称
        // loopback 不成立；桥的能力是设备级命令，绝不能对局域网开放。
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
                // 超过并发上限的连接立即关闭，而不是排队占内存/线程。
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
                    // 超时、超长行、EOF 都会结束这条连接。
                    val line = readBoundedLine(input, limits.maxLineBytes) ?: return
                    if (line.isBlank()) continue
                    val request = runCatching { json.decodeFromString<BridgeRequest>(line) }.getOrNull()
                    if (request == null) {
                        // 未鉴权的连接不提供解析错误回显，直接断开。
                        if (!authenticated) return
                        send(BridgeResponse(id = "error", error = BridgeError(code = -32700, message = "Parse error")))
                        continue
                    }
                    val response = dispatchRequest(request)
                    send(response)
                    // 鉴权失败直接断开，不给同一连接继续试 token 的机会。
                    if (response.error?.code == BridgeAuth.UNAUTHORIZED_CODE) return
                    authenticated = true
                    s.soTimeout = limits.idleTimeoutMs
                }
            }
        } catch (_: IOException) {
            // 超时/对端断开/超长行：连接已关闭即可。
        } finally {
            activeClients.remove(socket)
        }
    }

    /** 读一行（`\n` 结尾，去掉 `\r`）；超过 [maxBytes] 抛出，EOF 且无数据时返回 null。 */
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
                    val title = (request.params as? JsonObject)?.get("title")?.jsonPrimitive?.content ?: com.deepseek.dsh.core.model.Tr.t("DSH notice", "Уведомление DSH", "DSH 提示")
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
                    context.startActivity(Intent.createChooser(intent, com.deepseek.dsh.core.model.Tr.t("Share", "Поделиться", "分享")).apply {
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
     * 执行一条 bash 工具命令。
     *
     * 必须走 proot 里的 `/bin/sh`：Agent 的 bash 工具面对的是工作区（`/workspace`），
     * 需要 rootfs 里的 git/diff/grep 等工具链；早期实现用 Android 的 `/system/bin/sh`，
     * 导致 git 不存在、工作区路径也不匹配，还会让 workspace/changes 收不到改动。
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
        // libproot.so 依赖 nativeLibraryDir 里的 libtalloc 等库；直接 exec 时必须显式给
        // LD_LIBRARY_PATH，否则会报 "CANNOT LINK EXECUTABLE ... libtalloc.so not found"。
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

    /** 与 Host 的 `/workspace` 绑定一致；未注入时退回默认的公共工作区。 */
    private fun resolvedWorkspacePath(): String = workspacePath?.takeIf { it.isNotBlank() }
        ?: java.io.File(
            android.os.Environment.getExternalStorageDirectory(), "Documents/DSH-Workspace"
        ).absolutePath

    /**
     * 运行进程并收集输出。输出按字符上限截断但持续排空管道（否则子进程会阻塞在写满的
     * 管道上），超时被强制结束；结果在读线程结束后才读取，没有数据竞争。
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

    /** 线程安全、有上限的输出收集器。 */
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
            val channel = NotificationChannel(channelId, com.deepseek.dsh.core.model.Tr.t("DSH device notifications", "Уведомления DSH", "DSH 设备通知"), NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}





/** Код ошибки моста: чтение буфера обмена запрещено настройкой пользователя. */
internal const val CLIPBOARD_DENIED_CODE = -32010
