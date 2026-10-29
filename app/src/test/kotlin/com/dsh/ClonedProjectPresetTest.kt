package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** G-50 (гигиена): новый чат в проекте с клонированным кодом начинается с «Только чтения». */
class ClonedProjectPresetTest {
    @Test
    fun newChatInClonedProjectIsReadOnly() {
        assertEquals(READ_ONLY_PRESET, clonedProjectPreset(cloned = true, sessionId = null, pending = null))
    }

    @Test
    fun choiceOfUserAndExistingChatsAreKept() {
        assertNull(clonedProjectPreset(cloned = true, sessionId = null, pending = "workspace-write"))
        assertNull(clonedProjectPreset(cloned = true, sessionId = "s1", pending = null))
        assertNull(clonedProjectPreset(cloned = false, sessionId = null, pending = null))
    }
}
