package com.deepseek.dsh.feature.chat

/** Насколько рискованно действие, о котором спрашивает агент. */
enum class ApprovalTier { WRITE, DESTRUCTIVE }

/**
 * Уровень риска по имени инструмента и его аргументам.
 *
 * DESTRUCTIVE — необратимое: удаление, затирание, сброс истории git. Такие запросы всегда
 * спрашиваются у пользователя, даже в режиме «Сразу», и показываются полноэкранным
 * предупреждением, поэтому пропуск хуже лишней тревоги. Команда оболочки разбирается по словам
 * (`rm файл` — удаление, `perform` — нет); у остальных инструментов решает имя
 * (`delete_file`, `remove_dir`), а не текст аргументов: запись файла со словом «remove» внутри
 * удалением не является.
 */
fun approvalTier(toolName: String, args: String?): ApprovalTier {
    val name = toolName.lowercase()
    val destructive = when {
        DESTRUCTIVE_TOOL_WORDS.any { it in name } -> true
        isShellTool(name) -> isDestructiveCommand(approvalTarget(args) ?: args.orEmpty())
        else -> false
    }
    return if (destructive) ApprovalTier.DESTRUCTIVE else ApprovalTier.WRITE
}

/** Инструмент запускает команду оболочки. */
internal fun isShellTool(name: String): Boolean {
    val n = name.lowercase()
    return SHELL_TOOL_WORDS.any { it in n }
}

/** Команда или путь из JSON-аргументов вызова — то, что агент собирается сделать. */
fun approvalTarget(args: String?): String? {
    if (args.isNullOrBlank()) return null
    for (key in TARGET_KEYS) {
        val match = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(args) ?: continue
        return match.groupValues[1].replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\").trim().ifBlank { null }
    }
    return args.trim().takeIf { it.length in 1..200 && !it.startsWith("{") }
}

/**
 * Что именно будет удалено: пути после `rm`/`rmdir`/`unlink`/`shred` в каждой части команды
 * (`a && rm x; sudo /bin/rm -rf y` → `x`, `y`) или путь инструмента удаления.
 */
fun deletionTargets(toolName: String, args: String?): List<String> {
    val target = approvalTarget(args) ?: return emptyList()
    if (!isShellTool(toolName)) return listOf(target)
    return commandSegments(target).flatMap { words ->
        val i = words.indexOfFirst { commandName(it) in DELETE_COMMANDS }
        if (i < 0) emptyList() else words.drop(i + 1).filterNot { it.startsWith("-") }.map { it.trim('"', '\'') }
    }.filter { it.isNotBlank() }.distinct()
}

/** Необратима ли команда оболочки. */
internal fun isDestructiveCommand(command: String): Boolean {
    val text = command.lowercase()
    if (DESTRUCTIVE_PHRASES.any { it in text }) return true
    return commandSegments(text).any { words ->
        val names = words.map(::commandName)
        names.any { it in DELETE_COMMANDS || it.startsWith("mkfs") } ||
            (names.firstOrNull() == "find" && "-delete" in words) ||
            ("dd" in names && words.any { it.startsWith("of=") })
    }
}

/** Части команды между `&&`, `||`, `;`, `|` и переводами строки — каждая как список слов. */
private fun commandSegments(command: String): List<List<String>> =
    command.split(SEGMENT_SEPARATORS)
        .map { part -> part.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }

/** Имя программы без пути и кавычек: `/bin/rm` → `rm`, `"rm"` → `rm`. */
private fun commandName(word: String): String = word.trim('"', '\'', '(', ')', '`').substringAfterLast('/')

private val SEGMENT_SEPARATORS = Regex("&&|\\|\\||;|\\||\\n|\\$\\(|`")
private val TARGET_KEYS = listOf("command", "cmd", "path", "file_path", "filePath", "target", "url")
private val SHELL_TOOL_WORDS = listOf("bash", "shell", "exec", "command")
private val DESTRUCTIVE_TOOL_WORDS = listOf("delete", "remove", "unlink", "truncate", "rmdir")
private val DELETE_COMMANDS = setOf("rm", "rmdir", "unlink", "shred", "truncate")
private val DESTRUCTIVE_PHRASES = listOf(
    "git reset --hard", "git clean", "git checkout --", "git restore", "git push --force", "git push -f",
    "drop table", "drop database",
)
