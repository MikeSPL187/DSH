package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {
    @Test fun titlesPreserveLongWordsAndUnicodeAndSkipBlankLines() {
        val line = "ОченьДлинноеСлово".repeat(12) + " 👩‍💻 café́"
        assertEquals(line, sessionTitle("\n  $line  \nFollowing line"))
    }
}
