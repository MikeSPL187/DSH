package com.dsh

import com.dsh.feature.chat.DangerInfo
import com.dsh.feature.chat.DangerTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Что удалит команда агента на этом телефоне: размер каждого пути и свободное место (G-14).
 * Пути — это пути Linux: `/workspace/…` — рабочая папка телефона ([workspaceRoot], как её монтирует Host),
 * `~` и прочие абсолютные — в rootfs, относительные — в папке проекта сессии. Маски (`*.log`) раскрываются.
 * «Внутри проекта» — по компонентам пути (`/Proj-backup` не внутри `/Proj`).
 *
 * @param rootfs корень Linux-окружения.
 * @param workspaceRoot рабочая папка телефона (`/workspace` в Linux).
 * @param workspace папка проекта сессии (путь внутри Linux или путь телефона).
 */
internal suspend fun inspectDeletion(rootfs: File, workspaceRoot: File, workspace: String?, paths: List<String>): DangerInfo = withContext(Dispatchers.IO) {
    fun linuxPath(path: String): File = when {
        path == "~" -> File(rootfs, "root")
        path.startsWith("~/") -> File(rootfs, "root/" + path.drop(2))
        path == "/workspace" -> workspaceRoot
        path.startsWith("/workspace/") -> File(workspaceRoot, path.removePrefix("/workspace/"))
        // Путь телефона (папка проекта из списка проектов) — как есть.
        path.startsWith(workspaceRoot.absolutePath) -> File(path)
        path.startsWith("/") -> File(rootfs, path.removePrefix("/"))
        else -> File(path)
    }
    val ws = workspace?.let(::linuxPath)?.takeIf { it.isDirectory }
    val wsPath = ws?.let { runCatching { it.canonicalFile.toPath() }.getOrNull() }
    val targets = paths.map { p ->
        val base = if (p.startsWith("/") || p.startsWith("~")) linuxPath(p) else ws?.let { File(it, p) }
        val files = base?.let(::expandMask).orEmpty()
        val outside = wsPath == null || files.isEmpty() || files.any { f ->
            val real = runCatching { f.canonicalFile.toPath() }.getOrNull()
            real == null || !real.startsWith(wsPath) || real == wsPath
        }
        val size = if (files.isEmpty()) null else files.map { sizeOf(it) }.let { sizes -> if (sizes.any { it == null }) null else sizes.sumOf { it!! } }
        DangerTarget(p, size, outside)
    }
    val free = runCatching { android.os.StatFs((ws ?: rootfs).path).availableBytes }.getOrNull()
    DangerInfo(targets, free)
}

/** Файлы по пути; маска в последней части (звёздочка, ?, [..]) раскрывается в папке. Нет — пусто. */
private fun expandMask(file: File): List<File> {
    val name = file.name
    if (!name.contains('*') && !name.contains('?') && !name.contains('[')) return listOfNotNull(file.takeIf { it.exists() })
    val dir = file.parentFile ?: return emptyList()
    val matcher = runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$name") }.getOrNull() ?: return emptyList()
    return dir.listFiles()?.filter { matcher.matches(java.nio.file.Paths.get(it.name)) }.orEmpty()
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
