package com.deepseek.dsh

import com.deepseek.dsh.feature.chat.DangerInfo
import com.deepseek.dsh.feature.chat.DangerTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Что удалит команда агента на этом телефоне: размер каждого пути и свободное место.
 * Пути Linux (`~`, `/…`) ищутся в rootfs, относительные — в папке проекта сессии.
 *
 * @param rootfs корень Linux-окружения.
 * @param workspace папка проекта сессии (путь Android или путь внутри Linux).
 */
internal suspend fun inspectDeletion(rootfs: File, workspace: String?, paths: List<String>): DangerInfo = withContext(Dispatchers.IO) {
    fun onDisk(path: String): File? = listOf(File(path), File(rootfs, path.removePrefix("/"))).firstOrNull { it.exists() }
    val ws = workspace?.let { onDisk(it) }
    val targets = paths.map { p ->
        val file = when {
            p == "~" -> File(rootfs, "root")
            p.startsWith("~/") -> File(rootfs, "root/" + p.drop(2))
            p.startsWith("/") -> onDisk(p)
            else -> ws?.let { File(it, p) }
        }?.takeIf { it.exists() }
        val outside = file == null || ws == null || !file.canonicalPath.startsWith(ws.canonicalPath)
        DangerTarget(p, file?.let { sizeOf(it) }, outside)
    }
    val free = runCatching { android.os.StatFs((ws ?: rootfs).path).availableBytes }.getOrNull()
    DangerInfo(targets, free)
}

/** Размер файла или папки; для огромных деревьев — не дольше пары секунд, иначе null. */
private fun sizeOf(f: File): Long? {
    if (f.isFile) return f.length()
    val deadline = System.currentTimeMillis() + 2_500
    var total = 0L
    val stack = ArrayDeque<File>().apply { add(f) }
    while (stack.isNotEmpty()) {
        if (System.currentTimeMillis() > deadline) return null
        val d = stack.removeLast()
        d.listFiles()?.forEach { c ->
            // Ссылки не обходим: rm -rf удаляет саму ссылку, а не то, на что она указывает.
            if (java.nio.file.Files.isSymbolicLink(c.toPath())) return@forEach
            if (c.isDirectory) stack.add(c) else total += c.length()
        }
    }
    return total
}
