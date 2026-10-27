package com.dsh.core.data

import com.dsh.core.protocol.ForwardedWaterfall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalsRepositoryTest {
    private val host = FakeHost { _, _ -> null }

    private fun waterfall(id: String, event: String = "approval/request") =
        ForwardedWaterfall(hostId = "local", event = event, eventId = id, agentId = "s1", request = JsonObject(emptyMap()))

    /** Таймеры снятия запросов живут в своей области и отменяются после теста. */
    private fun test(block: suspend (ApprovalsRepository) -> Unit) = runBlocking {
        val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(ApprovalsRepository(host.client, timers))
        } finally {
            timers.cancel()
        }
    }

    @Test
    fun `without a policy the request waits for the user`() = test { approvals ->
        approvals.onWaterfall("local", waterfall("e1"))
        assertEquals(listOf("e1"), approvals.pendingWaterfallApprovalsFlow.value.map { it.eventId })
        assertTrue(host.answers.isEmpty())

        approvals.respondApprovalWaterfall("local", "e1", approved = true)
        assertTrue(approvals.pendingWaterfallApprovalsFlow.value.isEmpty())
        assertEquals(listOf("e1" to "\"allowed-once\""), host.answers)
    }

    @Test
    fun `policy answers at once and is not shown to the user`() = test { approvals ->
        approvals.approvalPolicy = { false }
        approvals.onWaterfall("local", waterfall("e2"))
        assertTrue(approvals.pendingWaterfallApprovalsFlow.value.isEmpty())
        assertEquals(listOf("e2" to "\"rejected\""), host.answers)
    }

    @Test
    fun `policy returning null asks as usual`() = test { approvals ->
        approvals.approvalPolicy = { null }
        approvals.onWaterfall("local", waterfall("e3"))
        assertEquals(1, approvals.pendingWaterfallApprovalsFlow.value.size)
    }

    @Test
    fun `failing policy falls back to asking`() = test { approvals ->
        approvals.approvalPolicy = { error("broken") }
        approvals.onWaterfall("local", waterfall("e4"))
        assertEquals(1, approvals.pendingWaterfallApprovalsFlow.value.size)
        assertTrue(host.answers.isEmpty())
    }

    @Test
    fun `repeated request replaces the old one`() = test { approvals ->
        approvals.onWaterfall("local", waterfall("e5"))
        approvals.onWaterfall("local", waterfall("e5"))
        assertEquals(1, approvals.pendingWaterfallApprovalsFlow.value.size)
    }

    @Test
    fun `questions wait for an answer, other waterfalls go back to the host`() = test { approvals ->
        approvals.onWaterfall("local", waterfall("q1", event = "user-questions/request"))
        approvals.onWaterfall("local", waterfall("x1", event = "something/else"))
        assertEquals(listOf("q1"), approvals.pendingQuestionsFlow.value.map { it.eventId })
        assertEquals(listOf("x1" to null), host.answers)

        approvals.respondUserQuestion("local", "q1", """{"answers":[]}""")
        assertTrue(approvals.pendingQuestionsFlow.value.isEmpty())
    }
}
