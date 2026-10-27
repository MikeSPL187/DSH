package com.deepseek.dsh.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Глубже недоверенный JSON не разбирается; настоящие данные — единицы и десятки уровней. */
const val MAX_JSON_DEPTH = 256

/**
 * Разобрать недоверенный JSON (ответ Host, аргументы инструмента, `package.json` стороннего пакета) с пределом
 * вложенности. И kotlinx.serialization, и org.json разбирают массивы рекурсивно: `[[[[…` в несколько тысяч уровней
 * переполняет стек, а StackOverflowError — не Exception, его не ловят ни `catch (e: Exception)`, ни `runCatching`
 * при разборе в главном потоке. Слишком глубокий — [IllegalArgumentException].
 */
fun Json.parseBoundedJson(text: String): JsonElement {
    require(!jsonTooDeep(text)) { "JSON is nested deeper than $MAX_JSON_DEPTH" }
    return parseToJsonElement(text)
}

/** Вложенность скобок вне строк больше [limit] — один проход, без разбора. */
fun jsonTooDeep(text: String, limit: Int = MAX_JSON_DEPTH): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (ch in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '[', '{' -> if (++depth > limit) return true
            ']', '}' -> depth--
        }
    }
    return false
}
