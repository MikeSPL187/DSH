package com.dsh.runtime.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-08: остановка во время запуска ждёт его конца, а запуск, увидев остановку, процесс не поднимает. */
class HostLifecycleLockTest {
    @Test
    fun stopDuringStartWaitsAndStartSeesTheRequest() = runBlocking {
        val lock = HostLifecycleLock()
        val events = mutableListOf<String>()
        val prepared = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val start = async {
            lock.starting {
                events += "prepare"
                prepared.complete(Unit)
                proceed.await()
                // Шаг «поднять процесс»: при пометке остановки — не поднимать.
                if (lock.stopRequested) events += "launch skipped" else events += "launched"
            }
        }
        prepared.await()
        val stop = async { lock.stopping { events += "stopped" } }
        yield()
        assertTrue(lock.stopRequested)
        // Остановка не выполнилась посреди запуска.
        assertFalse("stopped" in events)
        proceed.complete(Unit)
        start.await(); stop.await()
        assertEquals(listOf("prepare", "launch skipped", "stopped"), events)
        assertFalse(lock.stopRequested)
    }
}
