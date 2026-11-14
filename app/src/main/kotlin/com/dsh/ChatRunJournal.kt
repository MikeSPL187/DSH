package com.dsh

import com.dsh.core.model.RunStatus
import com.dsh.core.model.Tr
import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

@Serializable
data class SavedImage(val path: String, val mediaType: String, val name: String)

@Serializable
data class SavedPrompt(
    val text: String,
    val prompt: String,
    val images: List<SavedImage>,
    val model: ModelChoice,
    val permission: String? = null,
    val id: String = "",
    val hostId: String = "local-host",
    val sessionId: String? = null,
    val workspace: String = "",
    val workspaceName: String = "",
    val createdAt: Long = 0,
    val status: RunStatus = RunStatus.QUEUED,
    val error: String? = null,
    val partial: String = "",
    val startedSeq: Long = 0,
    val conversationId: String = "",
    val submissionOrder: Long = 0,
    val requiresModelSelection: Boolean = false,
)

/**
 * Atomic private journal. [records] contains committed snapshots, never unpersisted text.
 * The application owns [checkpointScope]; Activity recreation must not cancel pending writes.
 */
class ChatRunJournal(private val file: File, private val checkpointScope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<List<SavedPrompt>>(file.readText())
    }.getOrDefault(emptyList()))
    val records = mutable.asStateFlow()
    /** Live replies use repository streams; this projection only changes chat history/navigation. */
    val messageRecords = records.map(::forMessages).distinctUntilChanged()
        .stateIn(checkpointScope, SharingStarted.Eagerly, forMessages(mutable.value))
    private val recovering = mutable.value.filter { it.status in ACTIVE }.mapTo(mutableSetOf()) { it.id }
    private val snapshots = mutableSetOf<Pair<String, String>>()
    private val catalogs = mutableMapOf<String, Set<String>>()
    private val partials = mutableMapOf<String, JournalPartialBuffer>()
    private var lastPartialCheckpoint: Long? = null
    private var checkpointJob: Job? = null
    private var checkpointGeneration = 0L
    private val conversationSessions = mutable.value.filter { it.sessionId != null && it.conversationId.isNotBlank() }
        .associate { (it.hostId to it.conversationId) to it.sessionId!! }.toMutableMap()
    private val submissionTails = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    data class Submission(val previous: kotlinx.coroutines.Deferred<Unit>?, val completion: kotlinx.coroutines.CompletableDeferred<Unit>, val order: Long)
    private var nextSubmission = mutable.value.maxOfOrNull { it.submissionOrder } ?: 0L

    @Synchronized fun reserveSubmission(host: String): Submission {
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        return Submission(submissionTails.put(host, completion), completion, ++nextSubmission)
    }

    @Synchronized fun sessionForConversation(host: String, conversation: String): String? = conversationSessions[host to conversation]

    @Synchronized fun bindConversation(host: String, conversation: String, session: String) {
        commit(currentRecords().map {
            if (it.hostId == host && it.conversationId == conversation && it.sessionId == null)
                it.copy(sessionId = session) else it
        }) { conversationSessions[host to conversation] = session }
    }

    @Synchronized fun put(request: SavedPrompt) {
        commit((currentRecords().filterNot { it.id == request.id } + request).sortedBy { it.submissionOrder })
    }

    @Synchronized fun update(id: String, change: (SavedPrompt) -> SavedPrompt) {
        commit(currentRecords().map { if (it.id == id) change(it) else it })
    }

    /** A later queued request may finish model setup after the first request failed. */
    @Synchronized fun modelSelected(host: String, session: String) {
        commit(currentRecords().map {
            if (it.hostId == host && it.sessionId == session && it.requiresModelSelection)
                it.copy(requiresModelSelection = false) else it
        })
    }

    @Synchronized fun remove(id: String) { commit(currentRecords().filterNot { it.id == id }) }

    @Synchronized fun onEvent(host: String, event: RemoteEvent) {
        when (event) {
            is RemoteEvent.SessionCatalog -> catalogs[host] = event.running
            is RemoteEvent.SessionSnapshot -> snapshots += host to event.sessionId
            is RemoteEvent.SessionRunning -> if (event.running && event.seq > 0) {
                val first = mutable.value.firstOrNull { it.hostId == host && it.sessionId == event.sessionId && it.status in ACTIVE && event.at >= it.createdAt && it.startedSeq == 0L }
                if (first != null) update(first.id) { it.copy(status = RunStatus.RUNNING, startedSeq = event.seq) }
            }
            is RemoteEvent.TurnCompleted -> {
                val first = mutable.value.firstOrNull {
                    it.hostId == host && it.sessionId == event.sessionId && it.status in ACTIVE &&
                        event.state.at >= it.createdAt && it.startedSeq > 0 && event.state.seq > it.startedSeq
                }
                if (first != null) {
                    if (event.state.status == RunStatus.SUCCEEDED) remove(first.id)
                    else update(first.id) { it.copy(status = event.state.status, error = event.state.message ?: interruptedText()) }
                }
            }
            is RemoteEvent.MessageDelta -> {
                val request = mutable.value.firstOrNull { it.hostId == host && it.sessionId == event.sessionId && it.status in ACTIVE }
                if (request != null) {
                    // A restarted Host may replay an active attempt with no in-memory stream.
                    // That empty baseline cannot erase the checkpoint saved before process death.
                    val hasPartial = partials[request.id]?.isNotEmpty ?: request.partial.isNotEmpty()
                    val emptyRecoverySnapshot = request.id in recovering && event.baseline &&
                        event.delta.isEmpty() && hasPartial
                    if (!emptyRecoverySnapshot && (event.baseline || event.delta.isNotEmpty())) {
                        partials.getOrPut(request.id) { JournalPartialBuffer(request.partial) }
                            .append(event.delta, event.baseline)
                        checkpointPartial()
                    }
                }
            }
            else -> Unit
        }
        reconcileRecovery()
    }

    private fun reconcileRecovery() {
        if (recovering.isEmpty()) return
        // A replay can stream many deltas before its catalog/snapshot arrives.
        // Do not materialize a growing tail until metadata can actually resolve a request.
        val canReconcile = mutable.value.any { request ->
            request.id in recovering && (request.sessionId == null ||
                (request.hostId to request.sessionId) in snapshots && request.hostId in catalogs)
        }
        if (!canReconcile) return
        // Historical events have now replayed; only a current Host catalog can establish interruption.
        val current = currentRecords()
        val resolved = mutableSetOf<String>()
        val replacements = current.map { request ->
            if (request.id !in recovering) request
            else if (request.sessionId == null) {
                resolved += request.id
                request.copy(status = RunStatus.INTERRUPTED, error = interruptedText())
            } else if ((request.hostId to request.sessionId) in snapshots && request.hostId in catalogs) {
                resolved += request.id
                if (request.sessionId in catalogs.getValue(request.hostId)) request.copy(status = RunStatus.RUNNING)
                else request.copy(status = RunStatus.INTERRUPTED, error = interruptedText())
            } else request
        }
        if (resolved.isNotEmpty()) {
            commit(replacements) { recovering.removeAll(resolved) }
        }
    }

    /** Materialize text only for a checkpoint or a structural/status change. Caller holds this monitor. */
    private fun currentRecords(): List<SavedPrompt> = mutable.value.map { request ->
        partials[request.id]?.let { request.copy(partial = it.snapshot()) } ?: request
    }

    private fun checkpointPartial() {
        // One writer also owns retries. A later delta must not bypass a failed pending write.
        if (checkpointJob != null) return
        val elapsed = lastPartialCheckpoint?.let { elapsedMillis() - it }
        if (elapsed == null || elapsed >= CHECKPOINT_MILLIS) {
            commit(currentRecords())
        } else {
            val generation = checkpointGeneration
            checkpointJob = checkpointScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                delay((CHECKPOINT_MILLIS - elapsed).coerceAtLeast(1))
                var failureReported = false
                while (isActive) {
                    val finished = synchronized(this@ChatRunJournal) {
                        if (!isActive || generation != checkpointGeneration || partials.isEmpty()) true
                        else try {
                            commit(currentRecords())
                            true
                        } catch (error: IOException) {
                            // Keep the pending tail and the last committed snapshot until storage recovers.
                            if (!failureReported) {
                                LOGGER.log(Level.WARNING, "Chat checkpoint failed; retaining pending text for retry", error)
                                failureReported = true
                            }
                            false
                        }
                    }
                    if (finished) break
                    delay(CHECKPOINT_MILLIS)
                }
            }
            checkpointJob?.start()
        }
    }

    private fun commit(value: List<SavedPrompt>, beforePublish: () -> Unit = {}) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (partials.isNotEmpty()) lastPartialCheckpoint = elapsedMillis()
        checkpointGeneration++
        checkpointJob?.cancel()
        checkpointJob = null
        partials.clear()
        recovering.retainAll(value.mapTo(mutableSetOf()) { it.id })
        beforePublish()
        // Last operation: a synchronous observer may enqueue another request or delta here.
        mutable.value = value
    }

    companion object {
        private const val CHECKPOINT_MILLIS = 500L
        private val LOGGER = Logger.getLogger(ChatRunJournal::class.java.name)
        private fun elapsedMillis() = System.nanoTime() / 1_000_000
        private fun forMessages(records: List<SavedPrompt>) = records.map {
            if (it.status in ACTIVE && it.partial.isNotEmpty()) it.copy(partial = "") else it
        }
        val ACTIVE = setOf(RunStatus.QUEUED, RunStatus.RUNNING)
        fun interruptedText() = Tr.t("Interrupted when the app stopped. You can retry this request.", "Запрос прерван при остановке приложения. Его можно повторить.")
    }
}
