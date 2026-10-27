package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.protocol.parseHostJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val nameTokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val destructive = when {
        DESTRUCTIVE_TOOL_WORDS.any { it in name } || nameTokens.any { it in DESTRUCTIVE_TOOL_TOKENS } -> true
        // Проверяются все строки аргументов (команда под незнакомым ключом, `script`, `commands: […]`),
        // раскрытые JSON-парсером: `\t`, `\/`, `\u0072m` и аргументы строкой JSON — так же, как их увидит оболочка.
        // Не разобрать то, что похоже на JSON, — спросить: пропуск хуже лишней тревоги.
        isShellTool(name) -> {
            val strings = argStrings(args)
            strings == null || isDestructiveCommand(strings.joinToString("\n"))
        }
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
    var root = parseArgs(args) ?: return args.trim().takeIf { it.length in 1..200 && !looksJson(it) }
    // Аргументы строкой JSON: `"{\"command\":…}"`.
    if (root is JsonPrimitive && root.isString && looksJson(root.content)) parseArgs(root.content)?.let { root = it }
    if (root is JsonPrimitive) return if (root.isString) root.content.replace('\n', ' ').trim().ifBlank { null } else null
    // Первый известный ключ (в порядке TARGET_KEYS) на любой глубине: `{"input":{"command":…}}`.
    for (key in TARGET_KEYS) {
        findString(root, key)?.let { return it.replace('\n', ' ').trim().ifBlank { null } }
    }
    return null
}

/**
 * Все строки аргументов, раскрытые JSON-парсером. Аргументы бывают строкой JSON внутри строки
 * (`"{\"command\":…}"`) — такая строка разбирается ещё раз. Не JSON — сам текст;
 * похоже на JSON, но не разбирается, — null (риск не оценить).
 */
private fun argStrings(args: String?): List<String>? {
    if (args.isNullOrBlank()) return emptyList()
    val root = parseArgs(args) ?: return if (looksJson(args)) null else listOf(args)
    val out = mutableListOf<String>()
    fun walk(e: JsonElement, depth: Int) {
        when (e) {
            is JsonObject -> e.values.forEach { walk(it, depth) }
            is JsonArray -> e.forEach { walk(it, depth) }
            is JsonPrimitive -> if (e.isString) {
                val nested = if (depth < 3 && looksJson(e.content)) parseArgs(e.content) else null
                if (nested != null && nested !is JsonPrimitive) walk(nested, depth + 1) else out += e.content
            }
        }
    }
    walk(root, 0)
    return out
}

private fun looksJson(text: String): Boolean = text.trimStart().let { it.startsWith("{") || it.startsWith("[") || it.startsWith("\"") }

/** JSON аргументов с пределом вложенности (E-21); null — не JSON. */
private fun parseArgs(args: String): JsonElement? = runCatching { Json.parseHostJson(args) }.getOrNull()

private fun findString(e: JsonElement, key: String): String? = when (e) {
    is JsonObject -> (e[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: e.values.firstNotNullOfOrNull { findString(it, key) }
    is JsonArray -> e.firstNotNullOfOrNull { findString(it, key) }
    else -> null
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
            ("find" in names && "-delete" in words) ||
            ("dd" in names && words.any { it.startsWith("of=") }) ||
            ("rsync" in names && words.any { it.startsWith("--delete") })
    }
}

/** Части команды между `&&`, `||`, `;`, `|` и переводами строки — каждая как список слов. */
private fun commandSegments(command: String): List<List<String>> =
    command.split(SEGMENT_SEPARATORS)
        .map { part -> part.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }

/**
 * Имя программы без пути, кавычек и экранирования: `/bin/rm` → `rm`, `"rm"` → `rm`, `\\rm` → `rm`,
 * `r'm'` → `rm` — оболочка понимает их одинаково, и обойти проверку такой записью нельзя.
 */
private fun commandName(word: String): String = word.filterNot { it in QUOTING_CHARS }.substringAfterLast('/')

private const val QUOTING_CHARS = "\"'\\()`{}"

private val SEGMENT_SEPARATORS = Regex("&&|\\|\\||;|\\||\\n|\\$\\(|`")
private val TARGET_KEYS = listOf("command", "cmd", "path", "file_path", "filePath", "target", "url")
private val SHELL_TOOL_WORDS = listOf("bash", "shell", "exec", "command")
private val DESTRUCTIVE_TOOL_WORDS = listOf("delete", "remove", "unlink", "truncate", "rmdir", "erase", "wipe", "purge", "destroy")

/** Короткие слова в имени инструмента — только целым словом (`fs_rm`, но не `format`). */
private val DESTRUCTIVE_TOOL_TOKENS = setOf("rm", "del", "rmrf")
private val DELETE_COMMANDS = setOf("rm", "rmdir", "unlink", "shred", "truncate")
private val DESTRUCTIVE_PHRASES = listOf(
    "git reset --hard", "git clean", "git checkout --", "git restore", "git push --force", "git push -f",
    "git branch -d", "git stash drop", "git stash clear", "git push --delete", "git filter-branch",
    "drop table", "drop database", "drop schema",
    // Удаление из однострочника интерпретатора: python -c, node -e, perl -e.
    "rmtree", "rmsync", "rmdirsync", "unlinksync", "os.remove(", "os.unlink(", "os.rmdir(", "fs.rm(", "fs.unlink(",
    "rimraf", "deleterecursively",
)
