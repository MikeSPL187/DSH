package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {

    @Test
    fun caretOnZeroMajorDoesNotCrossMinor() {
        // Audit A-01/N-10: ^0.2.0 over [0.2.1, 0.9.0] used to pick 0.9.0.
        assertEquals("0.2.1", Semver.maxSatisfying(listOf("0.2.1", "0.9.0"), "^0.2.0"))
        assertEquals("0.0.3", Semver.maxSatisfying(listOf("0.0.3", "0.0.4"), "^0.0.3"))
        assertEquals("0.9.9", Semver.maxSatisfying(listOf("0.9.9", "1.0.0"), "^0"))
    }

    @Test
    fun noMatchFailsClosed() {
        // Audit A-01/N-10: exact 1.2.3 over [1.2.2, 2.0.0] used to pick 2.0.0.
        assertNull(Semver.maxSatisfying(listOf("1.2.2", "2.0.0"), "1.2.3"))
        assertNull(Semver.maxSatisfying(listOf("1.0.0"), "^2.0.0"))
    }

    @Test
    fun unparseableRangeFailsClosed() {
        assertNull(Semver.maxSatisfying(listOf("1.0.0"), "github:foo/bar"))
        assertNull(Semver.maxSatisfying(listOf("1.0.0"), "file:../x"))
        assertNull(Semver.maxSatisfying(listOf("1.0.0"), "workspace:*"))
        assertFalse(Semver.satisfies("1.0.0", "not a range"))
    }

    @Test
    fun caretTildeAndComparators() {
        val versions = listOf("1.2.3", "1.2.9", "1.3.0", "1.9.0", "2.0.0", "2.1.0")
        assertEquals("1.9.0", Semver.maxSatisfying(versions, "^1.2.3"))
        assertEquals("1.2.9", Semver.maxSatisfying(versions, "~1.2.3"))
        assertEquals("1.9.0", Semver.maxSatisfying(versions, "~1"))
        assertEquals("1.3.0", Semver.maxSatisfying(versions, ">=1.2.5 <1.9.0"))
        assertEquals("1.3.0", Semver.maxSatisfying(versions, ">= 1.2.5 < 1.9.0"))
        assertEquals("2.1.0", Semver.maxSatisfying(versions, ">1.9.0"))
        assertEquals("1.2.3", Semver.maxSatisfying(versions, "<=1.2.3"))
        assertEquals("1.9.0", Semver.maxSatisfying(versions, "<2"))
    }

    @Test
    fun xRangesHyphenAndUnion() {
        val versions = listOf("1.0.0", "1.4.2", "2.0.0", "3.1.0")
        assertEquals("1.4.2", Semver.maxSatisfying(versions, "1.x"))
        assertEquals("1.4.2", Semver.maxSatisfying(versions, "1"))
        assertEquals("1.4.2", Semver.maxSatisfying(versions, "1.4.*"))
        assertEquals("3.1.0", Semver.maxSatisfying(versions, "*"))
        assertEquals("3.1.0", Semver.maxSatisfying(versions, ""))
        assertEquals("2.0.0", Semver.maxSatisfying(versions, "1.0.0 - 2"))
        assertEquals("1.4.2", Semver.maxSatisfying(versions, "1.0.0 - 1.4.2"))
        assertEquals("3.1.0", Semver.maxSatisfying(versions, "^1.0.0 || ^3.0.0"))
    }

    @Test
    fun prereleaseOnlyWhenExplicitlyRequestedOnSameTuple() {
        val versions = listOf("1.2.3", "1.3.0-beta.1", "2.0.0-rc.1")
        assertEquals("1.2.3", Semver.maxSatisfying(versions, "^1.2.0"))
        assertEquals("1.3.0-beta.1", Semver.maxSatisfying(versions, "^1.3.0-beta.0"))
        assertNull(Semver.maxSatisfying(listOf("2.0.0-rc.1"), "*"))
        assertTrue(Semver.satisfies("2.0.0-rc.3", ">=2.0.0-rc.1"))
        assertFalse(Semver.satisfies("2.1.0-rc.1", ">=2.0.0-rc.1"))
    }

    @Test
    fun prereleaseOrdering() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        )
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue("$a < $b", Semver.compare(a, b) < 0)
        }
        assertEquals(0, Semver.compare("1.0.0+build.1", "1.0.0"))
    }

    @Test
    fun specParsingRejectsTraversalNames() {
        assertNull(PluginInstallSpec.parse("../../evil"))
        assertNull(PluginInstallSpec.parse("@scope/../x"))
        assertEquals(
            PluginInstallSpec.Registry("@scope/pkg", "^1.0.0"),
            PluginInstallSpec.parse("@scope/pkg@^1.0.0"),
        )
    }
}
