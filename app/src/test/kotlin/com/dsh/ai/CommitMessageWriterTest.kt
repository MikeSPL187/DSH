package com.dsh.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitMessageWriterTest {
    @Test
    fun keepsPlainMessage() {
        assertEquals("Обновить Kotlin до 2.0.21", CommitMessageWriter.clean("  Обновить Kotlin до 2.0.21 \n"))
    }

    @Test
    fun stripsFenceWithLanguage() {
        assertEquals("Fix build\n\n- bump kotlin", CommitMessageWriter.clean("```text\nFix build\n\n- bump kotlin\n```"))
    }

    @Test
    fun stripsQuotes() {
        assertEquals("Add nightly backup", CommitMessageWriter.clean("“Add nightly backup”"))
    }

    @Test
    fun emptyIsNull() {
        assertNull(CommitMessageWriter.clean("```\n```"))
    }
}
