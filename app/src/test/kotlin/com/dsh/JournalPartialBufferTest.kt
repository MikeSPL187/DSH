package com.dsh

import org.junit.Assert.*
import org.junit.Test

class JournalPartialBufferTest {
    @Test fun unicodeDeltasAndReplayRetainTheirOrder() {
        val buffer = JournalPartialBuffer("prefix")
        repeat(10_000) { buffer.append("я🙂", baseline = false) }
        assertEquals("prefix" + "я🙂".repeat(10_000), buffer.snapshot())
        buffer.append("replayed", baseline = true)
        buffer.append(" tail", baseline = false)
        assertEquals("replayed tail", buffer.snapshot())
    }

    @Test fun longStreamsAndOversizedDeltasKeepOnlyTheRecoveryTail() {
        val buffer = JournalPartialBuffer("")
        repeat(100_000) { buffer.append("abcd🙂", baseline = false) }
        assertEquals("abcd🙂".repeat(100_000).takeLast(JournalPartialBuffer.LIMIT), buffer.snapshot())
        val large = "x".repeat(2 * JournalPartialBuffer.LIMIT) + "🙂"
        buffer.append(large, baseline = false)
        assertEquals(large.takeLast(JournalPartialBuffer.LIMIT), buffer.snapshot())
        assertTrue(buffer.snapshot().length <= JournalPartialBuffer.LIMIT)
    }

    @Test fun truncationDoesNotLeaveAnOrphanedLowSurrogate() {
        val buffer = JournalPartialBuffer("")
        buffer.append("🙂" + "x".repeat(JournalPartialBuffer.LIMIT - 1), baseline = true)
        assertEquals("x".repeat(JournalPartialBuffer.LIMIT - 1), buffer.snapshot())
        assertFalse(buffer.snapshot().first().isLowSurrogate())
    }
}
