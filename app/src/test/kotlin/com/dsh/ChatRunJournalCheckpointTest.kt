package com.dsh

import com.dsh.core.model.RunState
import com.dsh.core.model.RunStatus
import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class ChatRunJournalCheckpointTest {
    private fun read(file: File): List<SavedPrompt> = Json.decodeFromString(file.readText())
    private fun request(id: String = "request", session: String? = "session") =
        SavedPrompt("prompt", "prompt", emptyList(), ModelChoice("model", "provider"),
            id = id, sessionId = session, createdAt = 100)

    @Test fun aPausedStreamStillPersistsItsTrailingText() = runBlocking<Unit> {
        JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request())
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " trailing🙂"))
            withTimeout(5000) { journal.records.first { it.single().partial == "first trailing🙂" } }
            assertEquals(journal.records.value, read(fixture.file))
        }
    }

    @Test fun observableRecordsNeverAcknowledgeAVolatilePartial() = JournalTestFixture().use { fixture ->
        val journal = fixture.open()
        journal.put(request())
        journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
        journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " trailing🙂"))
        synchronized(journal) { assertEquals(read(fixture.file), journal.records.value) }
    }

    @Test fun tenThousandDeltasDoNotRebuildMessageHistoryAndFailurePublishesTheFullTail() = runBlocking<Unit> {
        JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request())
            journal.onEvent("local-host", RemoteEvent.SessionRunning("session", true, 2, 101))
            val updates = AtomicInteger()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                journal.messageRecords.collect { updates.incrementAndGet() }
            }
            try {
                repeat(10_000) { journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "я🙂")) }
                assertEquals(1, updates.get())
                journal.onEvent("local-host", RemoteEvent.TurnCompleted("session",
                    RunState(RunStatus.FAILED, 3, 110, "TRANSPORT", "offline")))
                withTimeout(5000) { journal.messageRecords.first { it.single().status == RunStatus.FAILED } }
                assertEquals("я🙂".repeat(10_000), journal.records.value.single().partial)
                assertEquals(journal.records.value, read(fixture.file))
                assertEquals("offline", journal.messageRecords.value.single().error)
                assertEquals(journal.records.value, journal.messageRecords.value)
            } finally { observer.cancel() }
        }
    }

    @Test fun structuralChangesFlushPendingTextOfEverySession() {
        val changes: List<(ChatRunJournal) -> Unit> = listOf(
            { it.put(request("third", "third-session")) },
            { it.update("request") { record -> record.copy(status = RunStatus.FAILED, error = "offline") } },
            { it.bindConversation("local-host", "conversation", "bound-session") },
            { it.modelSelected("local-host", "session") },
        )
        for (change in changes) JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request().copy(requiresModelSelection = true))
            journal.put(request("second", "second-session"))
            journal.put(request("unbound", null).copy(conversationId = "conversation"))
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " tail"))
            journal.onEvent("local-host", RemoteEvent.MessageDelta("second-session", "second tail"))
            change(journal)
            val saved = read(fixture.file).associateBy { it.id }
            assertEquals("first tail", saved.getValue("request").partial)
            assertEquals("second tail", saved.getValue("second").partial)
            assertEquals(read(fixture.file), journal.records.value)
        }
    }

    @Test fun removingAndReplacingARequestCannotResurrectItsPendingTail() = runBlocking<Unit> {
        JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request())
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "old"))
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " pending"))
            journal.remove("request")
            journal.put(request().copy(text = "retry"))
            delay(700)
            assertEquals("retry", read(fixture.file).single().text)
            assertEquals("", read(fixture.file).single().partial)
            assertEquals(read(fixture.file), journal.records.value)
        }
    }

    @Test fun aDeltaReceivedDuringCheckpointPublicationGetsItsOwnCheckpoint() = runBlocking<Unit> {
        JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request())
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                journal.records.collect { records ->
                    if (records.single().partial == "first trailing")
                        journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "!"))
                }
            }
            try {
                journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
                journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " trailing"))
                withTimeout(5000) { journal.records.first { it.single().partial == "first trailing!" } }
                assertEquals("first trailing!", read(fixture.file).single().partial)
            } finally { observer.cancel() }
        }
    }

    @Test fun failedStorageKeepsTheLastCommitAndRetriesThePendingTail() = runBlocking<Unit> {
        JournalTestFixture().use { fixture ->
            val journal = fixture.open()
            journal.put(request())
            journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
            val temporary = File(fixture.file.parentFile, fixture.file.name + ".tmp")
            assertTrue(temporary.mkdir())
            val failed = CompletableDeferred<Unit>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.thrown is IOException &&
                        record.thrown.message.orEmpty().contains(temporary.absolutePath)) failed.complete(Unit)
                }
                override fun close() = Unit
                override fun flush() = Unit
            }
            val logger = Logger.getLogger(ChatRunJournal::class.java.name)
            logger.addHandler(handler)
            try {
                journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " trailing"))
                withTimeout(5000) { failed.await() }
                assertEquals("first", journal.records.value.single().partial)
                assertEquals(journal.records.value, read(fixture.file))
                assertTrue(temporary.delete())
                withTimeout(5000) { journal.records.first { it.single().partial == "first trailing" } }
                assertEquals(journal.records.value, read(fixture.file))
            } finally { logger.removeHandler(handler) }
        }
    }

    @Test fun failedConversationBindingDoesNotPublishItsIndexOrLoseBufferedText() = JournalTestFixture().use { fixture ->
        val journal = fixture.open()
        journal.put(request())
        journal.put(request("unbound", null).copy(conversationId = "conversation"))
        journal.onEvent("local-host", RemoteEvent.MessageDelta("session", "first"))
        journal.onEvent("local-host", RemoteEvent.MessageDelta("session", " tail"))
        val temporary = File(fixture.file.parentFile, fixture.file.name + ".tmp")
        assertTrue(temporary.mkdir())
        assertTrue(runCatching {
            journal.bindConversation("local-host", "conversation", "new-session")
        }.exceptionOrNull() is IOException)
        assertNull(journal.sessionForConversation("local-host", "conversation"))
        assertNull(journal.records.value.first { it.id == "unbound" }.sessionId)
        assertTrue(temporary.delete())
        journal.bindConversation("local-host", "conversation", "new-session")
        assertEquals("new-session", journal.sessionForConversation("local-host", "conversation"))
        assertEquals("first tail", read(fixture.file).first { it.id == "request" }.partial)
    }
}
