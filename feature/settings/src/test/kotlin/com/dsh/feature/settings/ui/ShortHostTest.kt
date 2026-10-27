package com.dsh.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Адрес в «Сети агента»: длинный — с конца, целыми частями домена, без переноса по буквам. */
class ShortHostTest {
    @Test
    fun longHostKeepsWholeTrailingLabels() {
        assertEquals("openrouter.ai", shortHost("openrouter.ai"))
        assertEquals("…\u2060example.com", shortHost("artifacts.very-long-internal-registry-name.corp.example.com"))
        assertEquals(17, shortHost("x." + "a".repeat(40) + ".com").length)
    }
}
