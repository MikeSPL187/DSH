package com.dsh.feature.settings.logic

import java.io.File

/** Где лежит файл инструкций: общий для всех проектов, проектный или личный (вне git). */
enum class InstructionScope { GLOBAL, PROJECT, LOCAL }

/** Файл инструкций на диске: что редактировать для [scope] и сколько он сейчас весит (null — файла нет). */
data class InstructionFile(val scope: InstructionScope, val file: File, val bytes: Long?)

/**
 * Файлы, которые читает `dsh-agent-instructions`: `$DSH_HOME/AGENTS.md`, затем в проекте `AGENTS.md`/`CLAUDE.md`
 * и личные `AGENTS.local.md`/`CLAUDE.local.md`. Бюджет Host — 64 КБ на всё вместе; один файл — не больше 1 МБ.
 */
object AgentInstructions {
    /** `maxBytes` строки `dsh-agent-instructions` в `dsh-base`. */
    const val BUDGET = 65_536L

    /** `maxSourceBytes` по умолчанию: больший файл Host не читает — и редактор его не открывает. */
    const val MAX_SOURCE = 1_048_576L

    private val NAMES = mapOf(
        InstructionScope.GLOBAL to listOf("AGENTS.md"),
        InstructionScope.PROJECT to listOf("AGENTS.md", "CLAUDE.md"),
        InstructionScope.LOCAL to listOf("AGENTS.local.md", "CLAUDE.local.md"),
    )

    /**
     * Файлы для трёх строк. Если в проекте уже есть только `CLAUDE.md` (или `CLAUDE.local.md`), правится он —
     * Host читает оба имени; иначе — `AGENTS.md`, его понимают и другие агенты.
     */
    fun files(dshHome: File, workspace: File): List<InstructionFile> = InstructionScope.entries.map { scope ->
        val dir = if (scope == InstructionScope.GLOBAL) dshHome else workspace
        val names = NAMES.getValue(scope)
        val file = names.map { File(dir, it) }.firstOrNull { it.isFile } ?: File(dir, names.first())
        InstructionFile(scope, file, file.takeIf { it.isFile }?.length())
    }

    /** Сумма весов существующих файлов — для «12 КБ из 64 КБ». */
    fun total(files: List<InstructionFile>): Long = files.sumOf { it.bytes ?: 0L }

    /** Текст файла; null — файла нет. Слишком большой файл — ошибка: Host его всё равно не прочитает. */
    fun read(file: File): Result<String?> = runCatching {
        if (!file.isFile) return@runCatching null
        require(file.length() <= MAX_SOURCE) { "too large" }
        // Блок памяти проекта ведёт приложение («Настройки → Память») — в редакторе его нет.
        com.dsh.core.data.AgentMemory.withoutBlock(file.readText())
    }

    /** Записать целиком через временный файл рядом: агент не увидит половину файла. */
    fun write(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        // Блок памяти, если он был в файле, остаётся на месте.
        val memory = com.dsh.core.data.AgentMemory.blockOf(file.takeIf { it.isFile }?.readText())
        @Suppress("NAME_SHADOWING")
        val text = if (memory.isEmpty()) text else com.dsh.core.data.AgentMemory.merge(text, memory).orEmpty()
        val tmp = File(file.parentFile, ".${file.name}.dsh-tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        true
    }.getOrDefault(false)

    /** Удалить файл; блок памяти проекта, если был, остаётся в файле один. */
    fun delete(file: File): Boolean {
        val memory = com.dsh.core.data.AgentMemory.blockOf(file.takeIf { it.isFile }?.readText())
        return if (memory.isNotEmpty()) write(file, "") else !file.exists() || file.delete()
    }
}
