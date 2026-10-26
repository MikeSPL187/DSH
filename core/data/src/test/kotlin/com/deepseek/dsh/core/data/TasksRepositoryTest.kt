package com.deepseek.dsh.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksRepositoryTest {
    private val catalog = hostOk(
        """[
            {"id":"a","title":"Daily review","kind":"weekly","time":"09:00:00.000","weekdays":[1,3,5],"sessionId":"s1","status":"scheduled"},
            {"id":"b","kind":"every","everySeconds":"3600"},
            {"title":"no id"}
        ]""",
    )

    private fun tasks(host: FakeHost) = TasksRepository(host.client, HostCalls(host.client), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `schedule catalog keeps entries with an id`() = runBlocking {
        val tasks = tasks(FakeHost { method, _ -> if (method == "schedule/catalog") catalog else null })
        tasks.refreshScheduleCatalog("local")
        val (weekly, every) = tasks.scheduleCatalogFlow.value
        assertEquals(listOf(1, 3, 5), weekly.weekdays)
        assertEquals("Daily review", weekly.title)
        assertEquals(3600L, every.everySeconds)
        assertEquals(2, tasks.scheduleCatalogFlow.value.size)
    }

    @Test
    fun `delete schedule refreshes the catalog only when host deleted it`() = runBlocking {
        var deleted = true
        val host = FakeHost { method, _ ->
            when (method) {
                "schedule/delete" -> hostOk("""{"deleted":$deleted}""")
                "schedule/catalog" -> catalog
                else -> null
            }
        }
        val tasks = tasks(host)
        assertTrue(tasks.deleteSchedule("local", "s1", "a"))
        assertEquals(listOf("schedule/delete", "schedule/catalog"), host.calls.map { it.first })

        deleted = false
        host.calls.clear()
        assertFalse(tasks.deleteSchedule("local", "s1", "a"))
        assertEquals(listOf("schedule/delete"), host.calls.map { it.first })
    }
}
