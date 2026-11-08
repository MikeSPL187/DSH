package com.dsh.core.data

import com.dsh.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CancelRunJobsTest {
    @Test fun cancelsOnlyTheSelectedSessionsCurrentRunAndObservesExit() = runBlocking {
        fun job(id: String, owner: String?, at: Long) = HostJobInfo(id, "bash", id, owner, "running", null, null, at, null)
        val rows = MutableStateFlow(listOf(job("old-server", "s1", 50), job("current-server", "s1", 101), job("other-session", "s2", 102), job("global", null, 103)))
        val killed = mutableListOf<String>()
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, args ->
            when (method.name) {
                "jobList" -> rows
                "killJob" -> {
                    val id = args[2] as String; killed += id
                    rows.value = rows.value.filterNot { it.id == id }
                    RemoteResult.Success("requested")
                }
                else -> error("Unexpected ${method.name}")
            }
        } as DshClientProtocol
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val tasks = TasksRepository(client, HostCalls(client), scope)
            assertTrue(tasks.cancelRunJobs("local", "s1", 100))
            assertEquals(listOf("current-server"), killed)
            assertEquals(3, rows.value.size)
            assertTrue(jobsForCancelledRun(rows.value, "s1", 0).isEmpty())
        } finally { scope.cancel() }
    }
}
