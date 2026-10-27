package com.dsh.runtime.host

import java.io.File

/**
 * Проекты, в которые DSH клонировал чужой репозиторий (G-50, гигиена; решение владельца): новые чаты в них
 * начинаются с правами «Только чтение» — `postinstall` и скрипты чужого кода не запускаются агентом молча.
 * Проект выходит из списка, когда пользователь сам выбирает в нём другие права.
 */
object ClonedProjects {
    private const val FILE = "cloned-projects.txt"

    private fun file(layout: RuntimeLayout) = File(layout.runtimeMetaDir, FILE)

    fun contains(layout: RuntimeLayout, name: String): Boolean = name in names(layout)

    @Synchronized
    fun add(layout: RuntimeLayout, name: String) {
        if (name.isBlank() || name.contains('\n') || contains(layout, name)) return
        write(layout, names(layout) + name)
    }

    /** Пользователь выбрал в проекте права шире «Только чтения» — доверяет ему. */
    @Synchronized
    fun trust(layout: RuntimeLayout, name: String) {
        if (contains(layout, name)) write(layout, names(layout) - name)
    }

    @Synchronized
    fun rename(layout: RuntimeLayout, from: String, to: String) {
        if (contains(layout, from)) write(layout, names(layout) - from + to)
    }

    private fun write(layout: RuntimeLayout, names: Set<String>) {
        runCatching { SafeFiles.writeAtomically(file(layout), names.sorted().joinToString("\n") + "\n") }
    }

    private fun names(layout: RuntimeLayout): Set<String> =
        runCatching { file(layout).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
}
