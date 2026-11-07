package com.dsh.feature.settings.logic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class ModelCostsTest {
    private val at = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
    private fun data(model: String = "deepseek-flash", attempt: Long = 0) = Json.parseToJsonElement("""
        {"turn":1,"step":1,"_attempt":$attempt,
         "message":{"source":{"provider":"deepseek-official","model":"$model"}},
         "usage":{"inputTokens":1000000,"outputTokens":1000000,"cacheReadTokens":1000000,"cacheWriteTokens":0,"totalTokens":3000000}}
    """).jsonObject

    @Test fun officialTariffsCountCacheSeparatelyAndKeepRetryCharges() {
        val first = UsageLedger.parse("h", "s", at, data())!!
        assertEquals(0, BigDecimal("5.02").compareTo(first.estimate!!.minimum))
        assertEquals(0, BigDecimal("10.04").compareTo(first.estimate!!.maximum))
        val retry = UsageLedger.parse("h", "s", at, data(attempt = 10))!!
        val summary = UsageLedger.summarize(listOf(first, first, retry), at, ZoneId.of("UTC"))
        assertEquals(6000000L, summary.today)
        assertEquals("≈ 10.04–20.08 CNY", summary.todayCost.display())
        assertEquals(0, summary.todayCost.unknown)
    }
    @Test fun unsupportedOrHistoricalUsageStaysUnknown() {
        assertNull(UsageLedger.parse("h", "s", at, data("unlisted"))!!.estimate)
        assertNull(UsageLedger.parse("h", "s", at - 86_400_000, data())!!.estimate)
        val custom = JsonObject(data() + ("message" to buildJsonObject {
            put("source", buildJsonObject { put("provider", "openai-compatible"); put("model", "deepseek-flash") })
        }))
        assertNull(UsageLedger.parse("h", "s", at, custom)!!.estimate)
    }
    @Test fun oldLedgerMigratesWithoutRepricingAndTariffSnapshotSurvives() {
        val old = UsageLedger.decode("""[{"key":"old","at":$at,"tokens":42}]""").single()
        assertNull(old.estimate)
        val current = UsageLedger.parse("h", "s", at, data())!!
        val restored = UsageLedger.decode(UsageLedger.encode(listOf(old, current)))
        assertEquals(listOf(old, current), restored)
        val summary = UsageLedger.summarize(restored, at)
        assertEquals(1, summary.monthCost.unknown)
        assertEquals("≈ 5.02–10.04 CNY", summary.monthCost.display())
    }
    @Test fun unknownCacheBucketsAndContradictoryUsageNeverBecomeCheapOrFree() {
        fun parse(usage: String) = UsageLedger.parse("h", "s", at, JsonObject(data() + ("usage" to Json.parseToJsonElement(usage))))
        assertNull(parse("""{"inputTokens":1,"outputTokens":1}"""))
        assertNull(parse("""{"inputTokens":5,"outputTokens":1,"totalTokens":2}"""))
        assertNull(parse("""{"inputTokens":1,"outputTokens":1,"totalTokens":9}""")!!.estimate)
        assertNull(parse("""{"inputTokens":9223372036854775807,"outputTokens":1}"""))
    }
    @Test fun localMonthBoundaryExcludesFutureAndKeepsCurrenciesSeparate() {
        val now = Instant.parse("2026-12-01T00:30:00Z").toEpochMilli()
        val e = UsageLedger.parse("h", "s", at, data())!!
        val before = e.copy(key = "before", at = now - 7_200_000)
        val current = e.copy(key = "now", at = now)
        val future = e.copy(key = "future", at = now + 1)
        val usd = current.copy(key = "usd", estimate = ModelTariffs.Estimate("USD", BigDecimal.ONE, BigDecimal.ONE, "fixture"))
        val s = UsageLedger.summarize(listOf(before, current, future, usd), now, ZoneId.of("Europe/Stockholm"))
        assertEquals(6000000L, s.month)
        assertEquals("≈ 5.02–10.04 CNY · ≈ 1 USD", s.monthCost.display())
    }
}
