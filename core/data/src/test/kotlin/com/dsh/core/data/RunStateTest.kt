package com.dsh.core.data

import com.dsh.core.model.RunState
import com.dsh.core.model.RunStatus
import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RunStateTest {
    @Test fun fastFailureRemainsTerminalWhenTheAgentSaysDoneAndHistoryReplays() = runBlocking {
        val events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 16)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fakes = RepositoryFakes { method, _ -> if (method == "subscribeEvents") events else emptyFlow<Any>() }
            val repository = fakes.repository(scope)
            repository.subscribeRemoteEvents("h")
            withTimeout(5_000) { events.subscriptionCount.first { it > 0 } }
            events.emit(RemoteEvent.SessionRunning("s", true, 2, 100))
            events.emit(RemoteEvent.TurnCompleted("s", RunState(RunStatus.FAILED, 3, 101, "sandbox/read-only-unavailable", "unavailable")))
            val key = SessionKey("h", "s")
            withTimeout(5_000) { repository.runStatesFlow.first { it[key]?.status == RunStatus.FAILED } }
            assertEquals(RunStatus.FAILED, repository.runStatesFlow.value[key]?.status)
            // A later completed closer cannot make a denied automation successful.
            val barrier = async(start = CoroutineStart.UNDISPATCHED) {
                repository.sessionEvents.first { it.second is RemoteEvent.SessionSnapshot }
            }
            events.emit(RemoteEvent.TurnCompleted("s", RunState(RunStatus.SUCCEEDED, 4, 102)))
            events.emit(RemoteEvent.SessionRunning("s", true, 2, 100))
            events.emit(RemoteEvent.SessionSnapshot("barrier"))
            withTimeout(5_000) { barrier.await() }
            assertEquals(RunStatus.FAILED, repository.runStatesFlow.value[key]?.status)
            assertFalse("replay must not restore Running", "s" in repository.runningSessionIdsFlow.value)
            // A genuinely new turn is allowed to begin.
            events.emit(RemoteEvent.SessionRunning("s", true, 5, 103))
            withTimeout(5_000) { repository.runStatesFlow.first { it[key]?.seq == 5L } }
            assertEquals(RunStatus.RUNNING, repository.runStatesFlow.value[key]?.status)
        } finally { scope.cancel() }
    }
}
