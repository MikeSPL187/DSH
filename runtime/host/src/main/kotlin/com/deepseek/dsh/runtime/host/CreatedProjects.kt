package com.deepseek.dsh.runtime.host

import java.io.File

/**
 * Папки проектов, которые создал сам DSH (G-38): только в них при запуске Host делается `git init` для базы
 * «Изменений». Существующие папки пользователя (в т. ч. в общей `Documents/DSH-Workspace`) молча не становятся
 * репозиториями. Папка по умолчанию — всегда DSH.
 */
object CreatedProjects {
    const val DEFAULT = "Default"
    private const val FILE = "created-projects.txt"

    private fun file(layout: RuntimeLayout) = File(layout.runtimeMetaDir, FILE)

    @Synchronized
    fun add(layout: RuntimeLayout, name: String) {
        if (name.isBlank() || name.contains('\n')) return
        val current = names(layout)
        if (name in current) return
        runCatching { SafeFiles.writeAtomically(file(layout), (current + name).sorted().joinToString("\n") + "\n") }
    }

    @Synchronized
    fun rename(layout: RuntimeLayout, from: String, to: String) {
        val current = names(layout)
        if (from !in current) return
        runCatching { SafeFiles.writeAtomically(file(layout), (current - from + to).sorted().joinToString("\n") + "\n") }
    }

    fun names(layout: RuntimeLayout): Set<String> =
        runCatching { file(layout).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet()) + DEFAULT
}
