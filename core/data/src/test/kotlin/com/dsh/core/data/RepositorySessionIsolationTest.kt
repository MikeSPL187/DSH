package com.dsh.core.data

import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositorySessionIsolationTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = Channel<RemoteEvent>(Channel.UNLIMITED)
        val processed = Channel<RemoteEvent>(Channel.UNLIMITED)
        private val secondaryInput = Channel<RemoteEvent>(Channel.UNLIMITED)
        private val secondaryProcessed = Channel<RemoteEvent>(Channel.UNLIMITED)
        private fun events(input: Channel<RemoteEvent>, processed: Channel<RemoteEvent>) = flow {
            for (event in input) {
                emit(event)
                processed.send(event)
            }
        }
        val repo = RepositoryFakes { method, args ->
            if (method == "subscribeEvents") {
                if (args.firstOrNull() == "second") events(secondaryInput, secondaryProcessed)
                else events(input, processed)
            } else emptyFlow<Nothing>()
        }.repository(scope)

        suspend fun send(event: RemoteEvent) {
            input.send(event)
            withTimeout(5000) { assertEquals(event, processed.receive()) }
        }

        suspend fun sendSecondary(event: RemoteEvent) {
            secondaryInput.send(event)
            withTimeout(5000) { assertEquals(event, secondaryProcessed.receive()) }
        }

        fun start() = repo.subscribeRemoteEvents("host")
        fun close() {
            scope.cancel()
            input.close(); processed.close()
            secondaryInput.close(); secondaryProcessed.close()
        }
    }

    @Test fun oldTextCannotReachAViewSelectedDuringEventDelivery() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            fixture.repo.markSessionViewed("old")
            fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.repo.sessionEvents.collect { (_, event) ->
                    if (event is RemoteEvent.MessageDelta) fixture.repo.markSessionViewed("new")
                }
            }
            fixture.start()
            fixture.send(RemoteEvent.MessageDelta("old", "stale answer"))
            assertNull(withTimeoutOrNull(100) { fixture.repo.streamingTextFlow.first { it.isNotEmpty() } })
        } finally { fixture.close() }
    }

    @Test fun oldReasoningCannotReachAViewSelectedDuringEventDelivery() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            fixture.repo.markSessionViewed("old")
            fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.repo.sessionEvents.collect { (_, event) ->
                    if (event is RemoteEvent.ReasoningDelta) fixture.repo.markSessionViewed("new")
                }
            }
            fixture.start()
            fixture.send(RemoteEvent.ReasoningDelta("old", "stale reasoning"))
            assertNull(withTimeoutOrNull(100) { fixture.repo.streamingReasoningFlow.first { it.isNotEmpty() } })
        } finally { fixture.close() }
    }

    @Test fun selectingTheSameSessionKeepsItsStreamAndOldStopCannotClearNewText() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            fixture.repo.markSessionViewed("old")
            fixture.start()
            fixture.send(RemoteEvent.MessageDelta("old", "first"))
            withTimeout(5000) { fixture.repo.streamingTextFlow.first { it == "first" } }
            fixture.repo.markSessionViewed("old")
            assertEquals("first", fixture.repo.streamingTextFlow.value)
            fixture.repo.markSessionViewed("new")
            fixture.send(RemoteEvent.MessageDelta("new", "current", baseline = true))
            withTimeout(5000) { fixture.repo.streamingTextFlow.first { it == "current" } }
            fixture.send(RemoteEvent.ReasoningDelta("new", "current reasoning", baseline = true))
            withTimeout(5000) { fixture.repo.streamingReasoningFlow.first { it == "current reasoning" } }
            fixture.send(RemoteEvent.SessionRunning("old", false))
            assertEquals("current", fixture.repo.streamingTextFlow.value)
            assertEquals("current reasoning", fixture.repo.streamingReasoningFlow.value)
        } finally { fixture.close() }
    }
    @Test fun oldStopDeliveredAcrossAViewSwitchCannotClearAConcurrentNewStream() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            fixture.repo.markSessionViewed("old")
            fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.repo.sessionEvents.collect { (_, event) ->
                    if (event is RemoteEvent.SessionRunning && event.sessionId == "old" && !event.running) {
                        fixture.repo.markSessionViewed("new")
                        // Hold the old stream's delivery while another Host publishes the new view.
                        runBlocking {
                            fixture.sendSecondary(RemoteEvent.MessageDelta("new", "concurrent answer"))
                            withTimeout(5000) { fixture.repo.streamingTextFlow.first { it == "concurrent answer" } }
                        }
                    }
                }
            }
            fixture.start()
            fixture.repo.subscribeRemoteEvents("second")
            fixture.send(RemoteEvent.SessionRunning("old", false))
            assertEquals("concurrent answer", fixture.repo.streamingTextFlow.value)
        } finally { fixture.close() }
    }

}
