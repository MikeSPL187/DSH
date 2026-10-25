package com.deepseek.dsh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File


/**
 * Host 会话目录名：`--workspace-<escaped>--`，非 ASCII 码点转义为 `~XXXX`。
 * 与 Host 实际落盘规则一致，用于在 Android 侧预建会话目录。
 */
internal fun workspaceSessionDirName(workspaceName: String): String {
    val escaped = buildString {
        workspaceName.codePoints().forEach { codePoint ->
            if (codePoint in 0x21..0x7E && codePoint != 0x2F) {
                appendCodePoint(codePoint)
            } else {
                append('~')
                append(codePoint.toString(16).uppercase().padStart(4, '0'))
            }
        }
    }
    return "--workspace-$escaped--"
}

/**
 * 通用插件目录自愈：插件在 proot 里第一次 mkdir 可能失败（ENOENT/ENOSYS），
 * 从错误里取出它要创建的目录，用 App 进程创建后重试一次。
 *
 * 不按插件名适配：只接受 rootfs 内的 `.dsh/` 路径，任何插件都适用。
 */
internal fun ensureMissingPluginDir(context: android.content.Context, message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val guestPath = Regex("mkdir '([^']+)'").find(message)?.groupValues?.getOrNull(1) ?: return false
    if (!guestPath.startsWith("/root/.dsh/")) return false
    val relative = guestPath.removePrefix("/")
    val target = File(context.filesDir, "rootfs/$relative")
    return runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
}


/** App 侧记住的实验性 bundle 选择（profile 清单会被 Host 启动重置）。 */

/** Открыть ссылку в браузере. Ссылки могут прийти от Host: только http(s), и без падения, если браузера нет. */
internal fun openWebLink(context: android.content.Context, url: String) {
    val uri = android.net.Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Следить за папкой [path], пока [enabled]: создание, удаление, перемещение и запись файлов
 * вызывают [onChange] (из фонового потока FileObserver — менять только снимковое состояние).
 */
@androidx.compose.runtime.Composable
internal fun WatchDirectory(path: String, enabled: Boolean, onChange: () -> Unit) {
    val latest = androidx.compose.runtime.rememberUpdatedState(onChange)
    androidx.compose.runtime.DisposableEffect(path, enabled) {
        val mask = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
            android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.MOVE_SELF or android.os.FileObserver.ATTRIB or android.os.FileObserver.CLOSE_WRITE
        val observer = if (!enabled) null else directoryObserver(java.io.File(path), mask) { latest.value() }.apply { startWatching() }
        onDispose { observer?.stopWatching() }
    }
}

private fun directoryObserver(dir: java.io.File, mask: Int, onEvent: () -> Unit): android.os.FileObserver =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        object : android.os.FileObserver(dir, mask) { override fun onEvent(event: Int, path: String?) = onEvent() }
    } else {
        @Suppress("DEPRECATION")
        object : android.os.FileObserver(dir.absolutePath, mask) { override fun onEvent(event: Int, path: String?) = onEvent() }
    }
