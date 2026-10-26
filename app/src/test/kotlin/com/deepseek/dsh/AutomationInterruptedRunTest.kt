package com.deepseek.dsh

import androidx.test.core.app.ApplicationProvider
import com.deepseek.dsh.feature.settings.logic.AutoRun
import com.deepseek.dsh.feature.settings.logic.Automation
import com.deepseek.dsh.feature.settings.logic.AutomationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Запуск, прерванный смертью процесса, не блокирует автоматизацию навсегда. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AutomationInterruptedRunTest {
    @Test
    fun staleRunFromKilledProcessIsMarkedAndDoesNotBlock() {
        AutomationStore.init(ApplicationProvider.getApplicationContext())
        // Процесс убили во время хода: в истории осталась запись «run».
        AutomationStore.upsert(Automation("night", "Ночной архив", history = listOf(AutoRun(100, "run", null, "Агент работает в фоне"))))

        assertTrue("прерванный запуск не должен считаться идущим", AutomationRunner.claim("night", "Прервано"))
        val head = AutomationStore.get("night")!!.history.first()
        assertEquals("err", head.status)
        assertEquals(100L, head.at)
        assertEquals("Прервано", head.result)

        // Пока запуск идёт в этом процессе, второй не начинается.
        assertFalse(AutomationRunner.claim("night", "Прервано"))
        AutomationRunner.release("night")
        assertTrue(AutomationRunner.claim("night", "Прервано"))
        AutomationRunner.release("night")
        AutomationStore.delete("night")
    }
}
