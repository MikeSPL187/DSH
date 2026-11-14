package com.dsh

import com.dsh.core.model.RunState
import com.dsh.core.model.RunStatus
import com.dsh.core.protocol.RemoteEvent
import org.junit.Assert.*
import org.junit.Test

class ChatRunJournalTest {
    private fun request(id: String = "r1") = SavedPrompt(
        "hello", "hello", listOf(SavedImage("/project/photo.png", "image/png", "photo.png")), ModelChoice("model", "provider"),
        id = id, sessionId = "s1", createdAt = 100,
    )

    @Test fun diskCommitOrderCannotReorderQueuedTurns() = JournalTestFixture().use { fixture ->
        val journal = fixture.open()
        val first = journal.reserveSubmission("local-host")
        val second = journal.reserveSubmission("local-host")
        journal.put(request("second").copy(submissionOrder = second.order))
        journal.put(request("first").copy(submissionOrder = first.order))
        journal.onEvent("local-host", RemoteEvent.SessionRunning("s1", true, 2, 101))
        assertEquals("first", journal.records.value.first { it.status == RunStatus.RUNNING }.id)
    }

    @Test fun killedProcessPreservesPromptAttachmentsAndPartialAndOffersRetry() = JournalTestFixture().use { fixture ->
        fixture.open().apply {
            put(request())
            onEvent("local-host", RemoteEvent.SessionRunning("s1", true, 2, 101))
            onEvent("local-host", RemoteEvent.MessageDelta("s1", "partial answer"))
        }
        fixture.killProcess()
        val recovered = fixture.open()
        recovered.onEvent("local-host", RemoteEvent.SessionCatalog(emptySet()))
        assertEquals(RunStatus.RUNNING, recovered.records.value.single().status)
        recovered.onEvent("local-host", RemoteEvent.SessionSnapshot("s1"))
        val saved = recovered.records.value.single()
        assertEquals(RunStatus.INTERRUPTED, saved.status)
        assertEquals("hello", saved.text)
        assertEquals(request().images, saved.images)
        assertEquals("partial answer", saved.partial)
        assertFalse(saved.error.isNullOrBlank())
    }

    @Test fun survivingRemoteRunIsNotMarkedInterrupted() = JournalTestFixture().use { fixture ->
        fixture.open().put(request().copy(startedSeq = 2, status = RunStatus.RUNNING))
        fixture.killProcess()
        val recovered = fixture.open()
        recovered.onEvent("local-host", RemoteEvent.MessageDelta("s1", "current partial", baseline = true))
        recovered.onEvent("local-host", RemoteEvent.MessageDelta("s1", "current partial extended", baseline = true))
        recovered.onEvent("local-host", RemoteEvent.MessageDelta("s1", "!"))
        recovered.onEvent("local-host", RemoteEvent.SessionSnapshot("s1"))
        recovered.onEvent("local-host", RemoteEvent.SessionCatalog(setOf("s1")))
        // Reconciliation also flushes the tail still waiting for its timed checkpoint.
        assertEquals("current partial extended!", recovered.records.value.single().partial)
        assertEquals(RunStatus.RUNNING, recovered.records.value.single().status)
        recovered.onEvent("local-host", RemoteEvent.TurnCompleted("s1", RunState(RunStatus.SUCCEEDED, 5, 110)))
        assertTrue(recovered.records.value.isEmpty())
    }

    @Test fun emptyReplayedStreamCannotEraseADurablePartialBeforeInterruptionIsReconciled() = JournalTestFixture().use { fixture ->
        fixture.open().put(request().copy(status = RunStatus.RUNNING, startedSeq = 2, partial = "saved partial"))
        fixture.killProcess()
        val recovered = fixture.open()
        recovered.onEvent("local-host", RemoteEvent.SessionCatalog(emptySet()))
        recovered.onEvent("local-host", RemoteEvent.MessageDelta("s1", "", baseline = true))
        recovered.onEvent("local-host", RemoteEvent.SessionSnapshot("s1"))
        assertEquals(RunStatus.INTERRUPTED, recovered.records.value.single().status)
        assertEquals("saved partial", recovered.records.value.single().partial)
        fixture.killProcess()
        assertEquals("saved partial", fixture.open().records.value.single().partial)
    }

    @Test fun replayCannotCompleteANewerRequestAndFastFailureIsRetained() = JournalTestFixture().use { fixture ->
        val journal = fixture.open()
        journal.put(request())
        journal.onEvent("local-host", RemoteEvent.SessionRunning("s1", true, 2, 90))
        journal.onEvent("local-host", RemoteEvent.TurnCompleted("s1", RunState(RunStatus.SUCCEEDED, 3, 95)))
        assertEquals(RunStatus.QUEUED, journal.records.value.single().status)
        journal.onEvent("local-host", RemoteEvent.SessionRunning("s1", true, 4, 101))
        journal.onEvent("local-host", RemoteEvent.TurnCompleted("s1", RunState(RunStatus.FAILED, 5, 102, "TRANSPORT", "offline")))
        assertEquals(RunStatus.FAILED, journal.records.value.single().status)
        assertEquals("offline", journal.records.value.single().error)
    }
}
