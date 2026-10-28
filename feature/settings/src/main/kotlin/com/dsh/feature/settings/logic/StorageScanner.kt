package com.dsh.feature.settings.logic

import com.dsh.core.designsystem.kit.*
import android.content.Context
import android.os.StatFs
import com.dsh.feature.settings.ui.StorageUsage
import com.dsh.runtime.host.SafeFiles
import java.io.File

/**
 * Сколько места занимает DSH: Linux (rootfs), проекты (рабочая папка), кэш и прочее.
 * Журналы — файлы папки журналов приложения старше 7 дней, их и кэш можно очистить (G-10: только известные
 * папки, без перехода по ссылкам, без временной папки proot, которой пользуется работающий Host).
 */
object StorageScanner {
    private const val WEEK = 7 * 86_400_000L

    fun scan(context: Context, rootfs: File?, workspace: File?, logsDir: File, checkpointsDir: File? = null): StorageUsage {
        val files = context.filesDir.parentFile ?: context.filesDir
        val linux = rootfs?.let(::size) ?: 0L
        val projects = workspace?.takeIf { it.exists() }?.let(::size) ?: 0L
        val cache = size(context.cacheDir) + (context.externalCacheDir?.let(::size) ?: 0L)
        val all = size(files)
        val inside = { f: File? -> f != null && f.absolutePath.startsWith(files.absolutePath) }
        val other = (all - (if (inside(rootfs)) linux else 0) - (if (inside(workspace)) projects else 0) - size(context.cacheDir)).coerceAtLeast(0)
        val logs = oldLogs(logsDir).sumOf { it.length() }
        val device = runCatching { StatFs(context.filesDir.absolutePath).totalBytes }.getOrDefault(0L)
        return StorageUsage(linux, projects, cache, other, logs, device, checkpoints = checkpointsDir?.takeIf { it.exists() }?.let(::size) ?: 0L)
    }

    /** Очистить: `cache` — кэш приложения, `logs` — старые журналы. Возвращает освобождённые байты. */
    fun clean(context: Context, kind: String, logsDir: File, checkpointsDir: File? = null): Long = when (kind) {
        "cache" -> cleanCache(listOfNotNull(context.cacheDir, context.externalCacheDir))
        "checkpoints" -> checkpointsDir?.let { cleanCache(listOf(it)) } ?: 0L
        "logs" -> oldLogs(logsDir).sumOf { f -> val l = f.length(); if (f.delete()) l else 0L }
        else -> 0L
    }

    /**
     * Удалить содержимое папок кэша: [SafeFiles.deleteTree] не идёт по ссылкам (раньше `deleteRecursively`
     * через ссылку мог стереть чужое — D-01), временные папки работающего Host ([KEEP_IN_CACHE]) не трогаются.
     */
    internal fun cleanCache(dirs: List<File>): Long = dirs.sumOf { dir ->
        val before = size(dir)
        dir.listFiles()?.filter { it.name !in KEEP_IN_CACHE }?.forEach { SafeFiles.deleteTree(it) }
        before - size(dir)
    }

    /** Журналы и архивы диагностики приложения старше недели — только в его папке журналов, без вложенных папок. */
    internal fun oldLogs(logsDir: File): List<File> {
        val cut = System.currentTimeMillis() - WEEK
        return logsDir.listFiles()?.filter {
            java.nio.file.Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.lastModified() < cut
        }.orEmpty()
    }

    /** Временные папки proot и моста: их держит работающий Host. */
    private val KEEP_IN_CACHE = setOf("proot-tmp")

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
