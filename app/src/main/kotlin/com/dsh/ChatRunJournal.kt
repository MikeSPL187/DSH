package com.dsh

import com.dsh.core.model.RunStatus
import com.dsh.core.model.Tr
import com.dsh.core.protocol.RemoteEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

/** Atomic private journal: persistence is committed before a prompt can reach the Host. */
class ChatRunJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<List<SavedPrompt>>(file.readText())
    }.getOrDefault(emptyList()))
    val records = mutable.asStateFlow()
    private val recovering = mutable.value.filter { it.status in ACTIVE }.mapTo(mutableSetOf()) { it.id }
    private val snapshots = mutableSetOf<Pair<String, String>>()
    private val catalogs = mutableMapOf<String, Set<String>>()
    private var lastCheckpoint = 0L
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
        conversationSessions[host to conversation] = session
        commit(mutable.value.map { if (it.hostId == host && it.conversationId == conversation && it.sessionId == null) it.copy(sessionId = session) else it })
    }

    @Synchronized fun put(request: SavedPrompt) {
        commit((mutable.value.filterNot { it.id == request.id } + request).sortedBy { it.submissionOrder })
    }

    @Synchronized fun update(id: String, change: (SavedPrompt) -> SavedPrompt) {
        commit(mutable.value.map { if (it.id == id) change(it) else it })
    }

    /** A later queued request may finish model setup after the first request failed. */
    @Synchronized fun modelSelected(host: String, session: String) {
        commit(mutable.value.map {
            if (it.hostId == host && it.sessionId == session && it.requiresModelSelection)
                it.copy(requiresModelSelection = false) else it
        })
    }

    @Synchronized fun remove(id: String) { recovering.remove(id); commit(mutable.value.filterNot { it.id == id }) }

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
                    val emptyRecoverySnapshot = request.id in recovering && event.baseline &&
                        event.delta.isEmpty() && request.partial.isNotEmpty()
                    if (!emptyRecoverySnapshot) {
                        mutable.value = mutable.value.map { if (it.id == request.id) it.copy(partial = (if (event.baseline) event.delta else it.partial + event.delta).takeLast(128 * 1024)) else it }
                        val now = System.currentTimeMillis()
                        if (now - lastCheckpoint >= 500) { commit(mutable.value); lastCheckpoint = now }
                    }
                }
            }
            else -> Unit
        }
        // Historical events have now replayed; only a current Host catalog can establish interruption.
        val replacements = mutable.value.map { request ->
            if (request.id !in recovering) request
            else if (request.sessionId == null) {
                recovering.remove(request.id)
                request.copy(status = RunStatus.INTERRUPTED, error = interruptedText())
            } else if ((request.hostId to request.sessionId) in snapshots && request.hostId in catalogs) {
                recovering.remove(request.id)
                if (request.sessionId in catalogs.getValue(request.hostId)) request.copy(status = RunStatus.RUNNING)
                else request.copy(status = RunStatus.INTERRUPTED, error = interruptedText())
            } else request
        }
        if (replacements != mutable.value) commit(replacements)
    }

    private fun commit(value: List<SavedPrompt>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutable.value = value
    }

    companion object {
        val ACTIVE = setOf(RunStatus.QUEUED, RunStatus.RUNNING)
        fun interruptedText() = Tr.t("Interrupted when the app stopped. You can retry this request.", "Запрос прерван при остановке приложения. Его можно повторить.")
    }
}
