package com.dsh

import com.dsh.feature.runtime.KeyCheck
import com.dsh.feature.runtime.OnboardingProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** QA F003: «ключ не подошёл» — только когда провайдер его действительно отклонил. */
class KeyCheckFailureTest {
    @Test
    fun `rate limit and timeouts are not a bad key`() {
        assertEquals(KeyCheck.Offline, keyCheckFailure(OnboardingProvider.DEEPSEEK, 429))
        assertEquals(KeyCheck.Offline, keyCheckFailure(OnboardingProvider.DEEPSEEK, 408))
        assertEquals(KeyCheck.Offline, keyCheckFailure(OnboardingProvider.DEEPSEEK, 503))
        assertEquals(KeyCheck.Offline, keyCheckFailure(OnboardingProvider.CUSTOM, 404))
        assertEquals(KeyCheck.Bad, keyCheckFailure(OnboardingProvider.DEEPSEEK, 404))
        assertEquals(KeyCheck.Bad, keyCheckFailure(OnboardingProvider.DEEPSEEK, 400))
    }
}
