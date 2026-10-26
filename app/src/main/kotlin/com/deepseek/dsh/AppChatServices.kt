package com.deepseek.dsh

import android.content.Context
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.model.MessageFeedbackInfo
import com.deepseek.dsh.core.model.WorkflowRunInfo
import com.deepseek.dsh.core.protocol.AttachmentImageRef
import com.deepseek.dsh.core.protocol.SessionRawFrame
import com.deepseek.dsh.feature.chat.ChatServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * [ChatServices] поверх [DshRepository] для открытой сессии ([AppUiState.selectedSessionId])
 * текущего Host. Новая сессия (ещё без id) ничего не делает.
 */
class AppChatServices(
    private val context: Context,
    private val repository: DshRepository,
    private val ui: AppUiState,
    private val scope: CoroutineScope,
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
}
