package com.dsh.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {
    private fun parse(text: String) = parseComposerStats(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `occupancy uses the model window and the projected tokens`() {
        val s = parse(
            """{"contextPressure":{"pressureTokens":400000,"projectedTokens":412000,"contextWindow":1000000},
                "contextBreakdown":{"systemTokens":11800,"toolsTokens":23400,"messageTokens":376800},
                "tokenUsage":{"uncachedInputTokens":182000,"outputTokens":46000,"cacheReadTokens":640000,"cacheWriteTokens":0},
                "sessionStats":{"decodeMs":2000,"decodeTokens":101}}""",
        )
        assertTrue(s.hasContext)
        assertEquals(41, s.contextPercent)
        assertEquals(412_000L, s.contextUsed)
        assertEquals(1_000_000L, s.contextWindow)
        assertEquals(23_400L, s.toolsTokens)
        assertEquals(78, s.cacheHitPercent)
        assertEquals(868_000L, s.totalTokens)
        assertEquals(50.5, s.outputSpeedTokensPerSec, 0.001)
    }

    @Test
    fun `without projected tokens the last provider sample is used, tiny use shows 1 percent`() {
        assertEquals(1, parse("""{"contextPressure":{"pressureTokens":900,"contextWindow":1000000}}""").contextPercent)
        assertEquals(100, parse("""{"contextPressure":{"pressureTokens":2000000,"contextWindow":1000000}}""").contextPercent)
    }

    @Test
    fun `no window means unknown occupancy, not a guess`() {
        val s = parse("""{"contextPressure":{"pressureTokens":50000},"tokenUsage":{"uncachedInputTokens":50000,"outputTokens":10}}""")
        assertFalse(s.hasContext)
        assertEquals(0, s.contextPercent)
        assertEquals(50_010L, s.totalTokens)
        assertFalse(parse("{}").hasContext)
    }
}
