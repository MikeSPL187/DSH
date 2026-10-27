package com.dsh.plugin.nativehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativePluginTrustPolicyTest {

    private val fp = "a".repeat(64)
    private val otherFp = "b".repeat(64)

    @Test
    fun firstSeenPluginIsNeverTrusted() {
        assertEquals(
            NativePluginTrustPolicy.Verdict.UNTRUSTED,
            NativePluginTrustPolicy.evaluate("com.example.plugin", fp, pin = null),
        )
    }

    @Test
    fun approvedIdentityIsTrusted() {
        val pin = NativePluginPin("com.example.plugin", fp)
        assertEquals(
            NativePluginTrustPolicy.Verdict.TRUSTED,
            NativePluginTrustPolicy.evaluate("com.example.plugin", fp.uppercase(), pin),
        )
    }

    @Test
    fun samePluginIdFromDifferentSignerIsMismatch() {
        val pin = NativePluginPin("com.example.plugin", fp)
        assertEquals(
            NativePluginTrustPolicy.Verdict.MISMATCH,
            NativePluginTrustPolicy.evaluate("com.example.plugin", otherFp, pin),
        )
    }

    @Test
    fun samePluginIdFromDifferentPackageIsMismatch() {
        val pin = NativePluginPin("com.example.plugin", fp)
        assertEquals(
            NativePluginTrustPolicy.Verdict.MISMATCH,
            NativePluginTrustPolicy.evaluate("com.evil.plugin", fp, pin),
        )
    }

    @Test
    fun pinRoundTripsAndRejectsMalformedValues() {
        val pin = NativePluginPin("com.example.plugin", fp)
        assertEquals(pin, NativePluginTrustPolicy.decode(NativePluginTrustPolicy.encode(pin)))
        assertNull(NativePluginTrustPolicy.decode(null))
        assertNull(NativePluginTrustPolicy.decode("com.example.plugin"))
        assertNull(NativePluginTrustPolicy.decode("com.example.plugin\nnot-a-fingerprint"))
        assertNull(NativePluginTrustPolicy.decode("\n$fp"))
    }
}
