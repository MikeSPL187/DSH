package com.deepseek.dsh.feature.settings.logic

import android.content.Context
import android.os.StatFs
import com.deepseek.dsh.feature.settings.ui.StorageUsage
import java.io.File

/**
 * Сколько места занимает DSH: Linux (rootfs), проекты (рабочая папка), кэш и прочее.
 * Журналы — файлы `*.log` старше 7 дней, их и кэш можно очистить.
 */
object StorageScanner {
    private const val WEEK = 7 * 86_400_000L

    fun scan(context: Context, rootfs: File?, workspace: File?): StorageUsage {
        val files = context.filesDir.parentFile ?: context.filesDir
        val linux = rootfs?.let(::size) ?: 0L
        val projects = workspace?.takeIf { it.exists() }?.let(::size) ?: 0L
        val cache = size(context.cacheDir) + (context.externalCacheDir?.let(::size) ?: 0L)
        val all = size(files)
        val inside = { f: File? -> f != null && f.absolutePath.startsWith(files.absolutePath) }
        val other = (all - (if (inside(rootfs)) linux else 0) - (if (inside(workspace)) projects else 0) - size(context.cacheDir)).coerceAtLeast(0)
        val logs = oldLogs(files).sumOf { it.length() }
        val device = runCatching { StatFs(context.filesDir.absolutePath).totalBytes }.getOrDefault(0L)
        return StorageUsage(linux, projects, cache, other, logs, device)
    }

    /** Очистить: `cache` — кэш приложения, `logs` — старые журналы. Возвращает освобождённые байты. */
    fun clean(context: Context, kind: String): Long = when (kind) {
        "cache" -> listOfNotNull(context.cacheDir, context.externalCacheDir).sumOf { dir ->
            val before = size(dir)
            dir.listFiles()?.forEach { it.deleteRecursively() }
            before - size(dir)
        }
        "logs" -> oldLogs(context.filesDir.parentFile ?: context.filesDir).sumOf { f -> val l = f.length(); if (f.delete()) l else 0L }
        else -> 0L
    }

    private fun oldLogs(root: File): List<File> {
        val cut = System.currentTimeMillis() - WEEK
        return root.walkTopDown().onEnter { !it.name.startsWith("rootfs") }
            .filter { it.isFile && (it.name.endsWith(".log") || it.parentFile?.name == "logs") && it.lastModified() < cut }.toList()
    }

    /** Размер папки без перехода по символьным ссылкам (rootfs полон ими). */
    fun size(f: File): Long {
        if (!f.exists()) return 0
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(f) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(cur.toPath()) }.getOrDefault(false)
            if (isLink) continue
            if (cur.isDirectory) cur.listFiles()?.let { stack.addAll(it) } else total += cur.length()
        }
        return total
    }
}
