package com.dsh

import com.dsh.feature.settings.logic.AutoRun
import com.dsh.feature.settings.logic.Automation
import com.dsh.feature.settings.logic.AutomationStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Автоматизации сохраняются в JSON без потерь (org.json — только под Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationStoreJsonTest {
    @Test
    fun `json round trip keeps everything`() {
        val a = Automation("a", "Ночной архив", charge = true, wifi = true, perm = "edit", notify = "changes", days = listOf(1), createdAt = 5, history = listOf(AutoRun(1, "ok", 38_000, "Архив", "s1")))
        assertEquals(listOf(a), AutomationStore.decode(AutomationStore.encode(listOf(a))))
    }
}
