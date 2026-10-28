package com.dsh.core.protocol

import com.dsh.core.model.ComposerStats
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * [ComposerStats] из значений `session/projections`: `contextPressure` (заполненность), `contextBreakdown` (состав),
 * `tokenUsage` (расход), `sessionStats` (скорость ответа). Как `contextOccupancy` официального клиента: занятое —
 * `projectedTokens`, иначе `pressureTokens`; без окна модели заполненность неизвестна (0), а не считается от догадки.
 */
fun parseComposerStats(values: JsonObject): ComposerStats {
    fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    fun JsonObject?.long(key: String): Long = num(key)?.takeIf { it >= 0 }?.toLong() ?: 0L

    val stats = values["sessionStats"] as? JsonObject
    val decodeMs = stats.num("decodeMs") ?: 0.0
    val decodeTokens = stats.num("decodeTokens") ?: 0.0
    val speed = if (decodeMs > 0.0) (decodeTokens / (decodeMs / 1000.0) * 10.0).roundToInt() / 10.0 else 0.0

    val usage = values["tokenUsage"] as? JsonObject
    val uncached = usage.long("uncachedInputTokens")
    val cacheRead = usage.long("cacheReadTokens")
    val cacheWrite = usage.long("cacheWriteTokens")
    val input = uncached + cacheRead + cacheWrite
    val cacheHit = if (input > 0) (cacheRead * 100.0 / input).roundToInt().coerceIn(0, 100) else 0

    val pressure = values["contextPressure"] as? JsonObject
    val used = (pressure.num("projectedTokens") ?: pressure.num("pressureTokens"))?.toLong()?.coerceAtLeast(0) ?: 0L
    val window = pressure.long("contextWindow")
    val percent = if (window > 0 && used > 0) (used * 100.0 / window).roundToInt().coerceIn(1, 100) else 0

    val breakdown = values["contextBreakdown"] as? JsonObject
    return ComposerStats(
        outputSpeedTokensPerSec = speed,
        contextPercent = percent,
        cacheHitPercent = cacheHit,
        contextUsed = used,
        contextWindow = window,
        systemTokens = breakdown.long("systemTokens"),
        toolsTokens = breakdown.long("toolsTokens"),
        messageTokens = breakdown.long("messageTokens"),
        uncachedInputTokens = uncached,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        outputTokens = usage.long("outputTokens"),
    )
}
