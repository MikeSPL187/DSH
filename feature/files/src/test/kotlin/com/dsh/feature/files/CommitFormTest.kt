package com.dsh.feature.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitFormTest {
    @Test
    fun `author email must look like an address`() {
        assertTrue(plausibleEmail("mike@example.com"))
        assertTrue(plausibleEmail(" 123+bot@users.noreply.github.com "))
        assertFalse(plausibleEmail("mike"))
        assertFalse(plausibleEmail("mike@"))
        assertFalse(plausibleEmail("mi ke@example.com"))
        assertFalse(plausibleEmail("a@b@c"))
    }
}
