package com.dsh.feature.settings.logic

import java.math.BigDecimal
import java.time.Instant

/** Official CNY prices checked on 2026-11-06. No alias matching by display name.
 * https://api-docs.deepseek.com/zh-cn/quick_start/pricing
 * The range covers peak/off-peak billing; event completion time cannot establish the
 * provider's billing time or Chinese public holidays. Search/fetch charges are excluded.
 */
object ModelTariffs {
    const val VERSION = "deepseek-cny-2026-11-06"
    private val verifiedFrom = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
    data class Estimate(val currency: String, val minimum: BigDecimal, val maximum: BigDecimal, val tariff: String)

    fun estimate(provider: String?, model: String?, at: Long, input: Long, output: Long,
                 cacheRead: Long?, cacheWrite: Long?): Estimate? {
        // Custom endpoints and historical usage have no verified tariff.
        if (provider != "deepseek-official" || at < verifiedFrom || input < 0 || output < 0 ||
            cacheRead == null || cacheRead < 0 || cacheWrite == null || cacheWrite != 0L) return null
        val prices = when (model) {
            "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> listOf("1", "4", "0.02")
            "deepseek-v4-pro" -> listOf("4.5", "13.5", "0.15")
            else -> return null
        }.map(::BigDecimal)
        val minimum = (input.toBigDecimal() * prices[0] + output.toBigDecimal() * prices[1] +
            cacheRead.toBigDecimal() * prices[2]).movePointLeft(6)
        return Estimate("CNY", minimum, minimum * BigDecimal(2), VERSION)
    }
}
