package com.dsh.feature.settings.logic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class UsageLedgerTest {
    private val data = Json.parseToJsonElement("""{"turn":1,"step":2,"usage":{"inputTokens":10,"outputTokens":20,"cacheReadTokens":30,"cacheWriteTokens":0}}""").jsonObject

    @Test fun providerUsageIncludesCacheTokensAndReplayDoesNotDoubleCount() {
        val entry = UsageLedger.parse("h", "s", 100, data)!!
        assertEquals(60L, entry.tokens)
        assertEquals(60L, UsageLedger.summarize(listOf(entry, entry), 200, ZoneOffset.UTC).today)
        assertEquals(120L, UsageLedger.summarize(listOf(entry, entry.copy(key = "different-attempt")), 200, ZoneOffset.UTC).today)
    }
    @Test fun unknownUsageIsNotRecordedAsFreeAndHostsStaySeparate() {
        assertNull(UsageLedger.parse("h", "s", 100, buildJsonObject { put("turn", 1); put("step", 2) }))
        assertNotEquals(UsageLedger.parse("h1", "s", 100, data)?.key, UsageLedger.parse("h2", "s", 100, data)?.key)
    }
    @Test fun periodsUseRequestTimeAndLocalTimezone() {
        val old = UsageLedger.Entry("old", 100, 500)
        val now = 40L * 86_400_000
        val current = UsageLedger.Entry("new", now - 10, 60)
        val summary = UsageLedger.summarize(listOf(old, current), now + 10, ZoneOffset.UTC)
        assertEquals(0L, summary.today)
        assertEquals(60L, summary.month)
    }
}
