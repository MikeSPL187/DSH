package com.dsh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File


/**
 * Имя папки сессии в Host: `--workspace-<escaped>--`, не-ASCII символы — `~XXXX`.
 * Совпадает с правилом Host: по нему приложение заранее создаёт папку сессии.
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
 * Самовосстановление папок плагинов: первый mkdir плагина под proot может упасть
 * (ENOENT/ENOSYS) — берём нужную папку из текста ошибки, создаём её и повторяем один раз.
 *
 * Без привязки к плагину: принимаются только пути `.dsh/` внутри rootfs.
 */
internal fun ensureMissingPluginDir(context: android.content.Context, message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val guestPath = Regex("mkdir '([^']+)'").find(message)?.groupValues?.getOrNull(1) ?: return false
    if (!guestPath.startsWith("/root/.dsh/")) return false
    val relative = guestPath.removePrefix("/")
    val target = File(context.filesDir, "rootfs/$relative")
    return runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
}


/** Выбор экспериментальных bundle на стороне приложения (Host сбрасывает профиль при запуске). */

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
