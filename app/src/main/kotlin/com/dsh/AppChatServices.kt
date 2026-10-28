package com.dsh

import android.content.Context
import com.dsh.core.data.DshRepository
import com.dsh.core.model.MessageFeedbackInfo
import com.dsh.core.model.WorkflowRunInfo
import com.dsh.core.protocol.AttachmentImageRef
import com.dsh.core.protocol.SessionRawFrame
import com.dsh.feature.chat.ChatServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dsh.core.model.ChatMessage
import com.dsh.feature.chat.RewindChange
import com.dsh.feature.chat.RewindPlan

/**
 * [ChatServices] поверх [DshRepository] для открытой сессии ([AppUiState.selectedSessionId])
 * текущего Host. Новая сессия (ещё без id) ничего не делает.
 */
class AppChatServices(
    private val context: Context,
    private val repository: DshRepository,
    private val ui: AppUiState,
    private val scope: CoroutineScope,
    /** Точки возврата файлов открытого проекта. */
    private val checkpoints: () -> com.dsh.runtime.host.FileCheckpoints? = { null },
    /** Подставить текст в поле ввода. */
    private val onDraft: (String) -> Unit = {},
) : ChatServices {
    private val host get() = ui.activeHostId
    private inline fun withSession(block: (String) -> Unit) { ui.selectedSessionId?.let(block) }

    override val feedback: StateFlow<Map<String, MessageFeedbackInfo>> = repository.messageFeedbackFlow

    override fun refreshFeedback() = withSession { sid -> scope.launch { repository.refreshMessageFeedback(host, sid) } }

    override fun rate(messageId: String, rating: String?, note: String?) =
        withSession { sid -> scope.launch { repository.rateMessage(host, sid, messageId, rating, note) } }

    override val images: StateFlow<Map<String, List<AttachmentImageRef>>> = repository.messageImagesFlow

    override suspend fun loadImage(ref: AttachmentImageRef): ByteArray? =
        ui.selectedSessionId?.let { repository.loadAttachmentImage(host, it, ref.attachmentId) }

    override val workflowRuns: Flow<List<WorkflowRunInfo>> =
        repository.workflowRunsFlow.map { runs -> ui.selectedSessionId?.let { runs[it] }.orEmpty() }

    override fun sessionFrames(): List<SessionRawFrame> = ui.selectedSessionId?.let(repository::rawSessionFrames).orEmpty()

    override fun forkAt(seq: Int) = withSession { sid ->
        scope.launch {
            val forked = repository.forkSession(host, sid, seq)
            if (forked != null) {
                ui.selectedSessionId = forked
                runCatching { repository.watchSession(host, forked) }
            } else {
                android.widget.Toast.makeText(context, context.getString(R.string.session_fork_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun exportSession() = withSession { sid -> scope.launch { performSessionExport(context, repository, host, sid) } }

    override fun shareText(title: String, text: String, fileName: String) = shareMarkdown(context, title, text, fileName)

    /** Точка «как было» перед последним возвратом файлов — по сессии; состояние Compose, чтобы лист видел отмену. */
    private val undoPoints = androidx.compose.runtime.mutableStateMapOf<String, com.dsh.runtime.host.FileCheckpoints.Point>()

    private fun pointOf(message: ChatMessage): com.dsh.runtime.host.FileCheckpoints.Point? =
        checkpoints()?.pointFor(message.sessionId, message.content, message.createdAt)

    override suspend fun rewindPlan(message: ChatMessage): RewindPlan? = withContext(Dispatchers.IO) {
        val cp = checkpoints() ?: return@withContext null
        val point = pointOf(message) ?: return@withContext RewindPlan(hasPoint = false, files = emptyList())
        val preview = cp.preview(point) ?: return@withContext RewindPlan(hasPoint = false, files = emptyList())
        RewindPlan(
            hasPoint = true,
            files = preview.modified.map { it to RewindChange.MODIFIED } + preview.removed.map { it to RewindChange.REMOVED } + preview.restored.map { it to RewindChange.RESTORED },
        )
    }

    override suspend fun rewindFiles(message: ChatMessage): Result<Int> = withContext(Dispatchers.IO) {
        val cp = checkpoints() ?: return@withContext Result.failure(IllegalStateException("no checkpoints"))
        val point = pointOf(message) ?: return@withContext Result.failure(IllegalStateException("no checkpoint"))
        cp.restore(point, message.sessionId).map { (change, before) ->
            withContext(Dispatchers.Main) { undoPoints[message.sessionId] = before }
            change.total
        }
    }

    override fun canUndoRewind(): Boolean = ui.selectedSessionId?.let { undoPoints.containsKey(it) } == true

    override suspend fun undoRewind(): Result<Int> {
        val sid = ui.selectedSessionId ?: return Result.failure(IllegalStateException("no session"))
        val point = undoPoints[sid] ?: return Result.failure(IllegalStateException("nothing to undo"))
        val cp = checkpoints() ?: return Result.failure(IllegalStateException("no checkpoints"))
        return withContext(Dispatchers.IO) { cp.restore(point, sid) }.map { (change, _) -> undoPoints.remove(sid); change.total }
    }

    /**
     * Новая ветка до [message]: развилка на событии перед ним, текст — в поле ввода. Сообщение первое (развилке
     * не на чем стоять) — просто новая сессия с тем же текстом.
     */
    override fun rewindConversation(message: ChatMessage) {
        val draft = message.content.removePrefix(context.getString(R.string.plan_prompt_prefix)).trim()
        scope.launch {
            val forked = if (message.seq > 1) repository.forkSession(host, message.sessionId, (message.seq - 1).toInt()) else null
            ui.selectedSessionId = forked
            if (forked != null) runCatching { repository.watchSession(host, forked) }
            onDraft(draft)
        }
    }
}
