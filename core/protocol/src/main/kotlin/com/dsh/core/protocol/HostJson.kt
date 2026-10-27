package com.dsh.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Глубже ответ Host не разбирается; настоящие ответы — единицы и десятки уровней. */
const val MAX_HOST_JSON_DEPTH = 256

/**
 * Разобрать JSON от Host с пределом вложенности.
 *
 * kotlinx.serialization разбирает массивы рекурсивно: `[[[[…` в несколько тысяч уровней (десяток КБ
 * от плагина Host, MCP или аргументов инструмента) переполняет стек потока. StackOverflowError — не
 * Exception: `catch (e: Exception)` в клиентах его не ловил, и процесс падал (в потоке OkHttp — сразу).
 * Теперь слишком глубокий ответ — обычная ошибка разбора ([IllegalArgumentException]).
 */
fun Json.parseHostJson(text: String): JsonElement {
    require(jsonDepthExceeds(text, MAX_HOST_JSON_DEPTH).not()) { "Host JSON is nested deeper than $MAX_HOST_JSON_DEPTH" }
    return parseToJsonElement(text)
}

/** Вложенность скобок вне строк больше [limit] (см. [com.dsh.core.model.jsonTooDeep]). */
internal fun jsonDepthExceeds(text: String, limit: Int): Boolean = com.dsh.core.model.jsonTooDeep(text, limit)
