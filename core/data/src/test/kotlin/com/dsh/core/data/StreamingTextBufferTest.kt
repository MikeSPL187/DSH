package com.dsh.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTextBufferTest {
    @Test fun deltaReceivedDuringPublicationGetsItsOwnSnapshot() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val stream = StreamingTextBuffer(scope)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                stream.snapshots.collect { snapshot ->
                    if (snapshot == "first") stream.append(" second", baseline = false)
                }
            }
            stream.append("first", baseline = false)
            withTimeout(5000) { stream.snapshots.first { it == "first second" } }
        } finally { scope.cancel() }
    }

    @Test fun burstRetainsEveryUnicodeDelta() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stream = StreamingTextBuffer(scope)
            repeat(10_000) { stream.append("я🙂", baseline = false) }
            val expected = "я🙂".repeat(10_000)
            withTimeout(5000) { stream.snapshots.first { it == expected } }
        } finally { scope.cancel() }
    }

    @Test fun baselineReplacesAccumulatedStreamAndClearInvalidatesOldPublication() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stream = StreamingTextBuffer(scope)
            stream.append("old", false)
            stream.clear()
            stream.append("replayed", true)
            stream.append(" tail", false)
            withTimeout(5000) { stream.snapshots.first { it == "replayed tail" } }
            stream.append(" pending", false)
            stream.clear()
            stream.append("new session", true)
            withTimeout(5000) { stream.snapshots.first { it == "new session" } }
            assertEquals("new session", stream.snapshots.value)
        } finally { scope.cancel() }
    }
}
