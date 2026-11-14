package com.dsh

import com.dsh.core.model.RunState
import com.dsh.core.model.RunStatus
import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory

private val meter = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).apply {
    check(isThreadAllocatedMemorySupported)
    isThreadAllocatedMemoryEnabled = true
}

private fun trial(): JsonObject {
    val file = kotlin.io.path.createTempDirectory("dsh-journal-probe-").resolve("outbox.json").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        val constructor = ChatRunJournal::class.java.constructors.single()
        val journal = (if (constructor.parameterCount == 1) constructor.newInstance(file)
            else constructor.newInstance(file, scope)) as ChatRunJournal
        journal.put(SavedPrompt("probe", "probe", emptyList(), ModelChoice("model", "provider"),
            id = "request", sessionId = "session", createdAt = 100))
        journal.onEvent("local-host", RemoteEvent.SessionRunning("session", true, 2, 101))
        val event = RemoteEvent.MessageDelta("session", "abcdefghijklmnop")
        val terminal = RemoteEvent.TurnCompleted("session", RunState(RunStatus.FAILED, 3, 110, "PROBE", "probe"))
        val expected = event.delta.repeat(10_000).takeLast(128 * 1024)
        val thread = Thread.currentThread().threadId()
        val threadStart = meter.getThreadAllocatedBytes(thread)
        val totalStart = meter.totalThreadAllocatedBytes
        val start = System.nanoTime()
        repeat(10_000) { journal.onEvent("local-host", event) }
        journal.onEvent("local-host", terminal)
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]!!.join() }
        val elapsed = System.nanoTime() - start
        val threadBytes = meter.getThreadAllocatedBytes(thread) - threadStart
        val totalBytes = meter.totalThreadAllocatedBytes - totalStart
        check(threadBytes >= 0 && totalBytes >= 0)
        check(journal.records.value.single().partial == expected)
        val disk = Json.decodeFromString<List<SavedPrompt>>(file.readText()).single()
        check(disk.partial == expected && disk.status == RunStatus.FAILED)
        return buildJsonObject {
            put("elapsedNs", elapsed)
            put("callingThreadAllocatedBytes", threadBytes)
            put("totalJvmAllocatedBytes", totalBytes)
            put("finalTailChars", disk.partial.length)
            put("finalTailMatches", true)
        }
    } finally {
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]!!.join() }
        file.parentFile.deleteRecursively()
    }
}

fun main() {
    repeat(3) { trial() }
    repeat(5) { println(trial()) }
}
