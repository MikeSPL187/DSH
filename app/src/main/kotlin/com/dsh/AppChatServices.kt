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
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    // ── Варианты ответа: один и тот же запрос с одной точки, у каждого — своя развилка разговора ──

    /** Группы вариантов открытого проекта; состояние Compose, чтобы панель «Вариант N из M» обновлялась сама. */
    private var groups by androidx.compose.runtime.mutableStateOf<List<com.dsh.runtime.host.FileCheckpoints.Group>>(emptyList())
    private var groupsOf: com.dsh.runtime.host.FileCheckpoints? = null

    private fun reloadGroups() { val cp = checkpoints(); groupsOf = cp; groups = cp?.groups().orEmpty() }

    private fun draftOf(message: ChatMessage) = message.content.removePrefix(context.getString(R.string.plan_prompt_prefix)).trim()

    /** Все файлы, которые меняет [p]. */
    private fun filesOf(p: com.dsh.runtime.host.FileCheckpoints.Preview) = p.modified + p.restored + p.removed

    override fun variantsInfo(): com.dsh.feature.chat.VariantsInfo? {
        if (groupsOf !== checkpoints()) reloadGroups()
        val sid = ui.selectedSessionId ?: return null
        val g = groups.lastOrNull { g -> g.variants.any { it.session == sid } }?.takeIf { it.variants.size >= 2 } ?: return null
        return com.dsh.feature.chat.VariantsInfo(g.variants.indexOfFirst { it.session == sid }, g.variants.size)
    }

    override suspend fun variantPlan(message: ChatMessage): com.dsh.feature.chat.VariantPlan? = withContext(Dispatchers.IO) {
        val cp = checkpoints() ?: return@withContext null
        val group = cp.groupOf(message.sessionId)?.takeIf { it.textHash == message.content.trim().hashCode() }
        val origin = group?.let { cp.pointById(it.origin) } ?: pointOf(message)
        val number = group?.variants?.indexOfFirst { it.session == message.sessionId }?.plus(1) ?: 1
        com.dsh.feature.chat.VariantPlan(number, origin?.let { cp.preview(it) }?.let(::filesOf))
    }

    override fun startVariant(message: ChatMessage) {
        val sid = message.sessionId
        val draft = draftOf(message)
        scope.launch {
            val cp = checkpoints() ?: return@launch
            val hash = message.content.trim().hashCode()
            // Результат этого варианта — снимок; файлы — к точке, с которой начаты все варианты.
            val origin = withContext(Dispatchers.IO) {
                val existing = cp.groupOf(sid)?.takeIf { it.textHash == hash }
                val origin = existing?.let { cp.pointById(it.origin) } ?: pointOf(message) ?: return@withContext null
                val result = cp.snapshot(sid, com.dsh.runtime.host.FileCheckpoints.VARIANT_TEXT) ?: return@withContext null
                val base = existing ?: com.dsh.runtime.host.FileCheckpoints.Group(origin.id, hash, listOf(com.dsh.runtime.host.FileCheckpoints.Variant(sid)))
                cp.putGroup(base.copy(variants = base.variants.map { if (it.session == sid) it.copy(result = result.id) else it }))
                origin.takeIf { cp.restore(it, sid).isSuccess }
            }
            if (origin == null) {
                android.widget.Toast.makeText(context, context.getString(com.dsh.feature.chat.R.string.vr_failed), android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            fun register(newSid: String) {
                cp.groupOf(sid)?.let { g -> cp.putGroup(g.copy(variants = g.variants + com.dsh.runtime.host.FileCheckpoints.Variant(newSid))) }
                reloadGroups()
            }
            // Разговор — развилка до сообщения; первое сообщение — новая сессия, её id появится после отправки.
            val forked = if (message.seq > 1) repository.forkSession(host, sid, (message.seq - 1).toInt()) else null
            ui.selectedSessionId = forked
            if (forked != null) {
                runCatching { repository.watchSession(host, forked) }
                register(forked)
            } else {
                scope.launch {
                    kotlinx.coroutines.withTimeoutOrNull(120_000) {
                        androidx.compose.runtime.snapshotFlow { ui.selectedSessionId }.first { it != null && it != sid }
                    }?.let(::register)
                }
            }
            ui.pendingAutoSend = draft
        }
    }

    override suspend fun variantRows(): List<com.dsh.feature.chat.VariantRow> = withContext(Dispatchers.IO) {
        val cp = checkpoints() ?: return@withContext emptyList()
        val sid = ui.selectedSessionId ?: return@withContext emptyList()
        val g = cp.groupOf(sid) ?: return@withContext emptyList()
        val origin = cp.pointById(g.origin) ?: return@withContext emptyList()
        g.variants.mapIndexed { i, v ->
            val change = if (v.session == sid) cp.preview(origin) else v.result?.let(cp::pointById)?.let { cp.changes(origin, it) }
            com.dsh.feature.chat.VariantRow(i + 1, change?.let(::filesOf).orEmpty(), v.session == sid)
        }
    }

    override fun keepVariant(index: Int) {
        val sid = ui.selectedSessionId ?: return
        scope.launch {
            val cp = checkpoints() ?: return@launch
            val target = withContext(Dispatchers.IO) {
                val g = cp.groupOf(sid) ?: return@withContext null
                val target = g.variants.getOrNull(index)?.takeIf { it.session != sid } ?: return@withContext null
                val point = target.result?.let(cp::pointById) ?: return@withContext null
                // Текущий вариант не теряется: его файлы — снимком в группу.
                val result = cp.snapshot(sid, com.dsh.runtime.host.FileCheckpoints.VARIANT_TEXT) ?: return@withContext null
                cp.putGroup(g.copy(variants = g.variants.map { if (it.session == sid) it.copy(result = result.id) else it }))
                target.takeIf { cp.restore(point, sid).isSuccess }
            }
            reloadGroups()
            if (target == null) {
                android.widget.Toast.makeText(context, context.getString(com.dsh.feature.chat.R.string.vr_failed), android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            ui.selectedSessionId = target.session
            runCatching { repository.watchSession(host, target.session) }
            android.widget.Toast.makeText(context, context.getString(com.dsh.feature.chat.R.string.vr_kept, index + 1), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Новая ветка до [message]: развилка на событии перед ним, текст — в поле ввода. Сообщение первое (развилке
     * не на чем стоять) — просто новая сессия с тем же текстом.
     */
    override fun rewindConversation(message: ChatMessage) {
        val draft = draftOf(message)
        scope.launch {
            val forked = if (message.seq > 1) repository.forkSession(host, message.sessionId, (message.seq - 1).toInt()) else null
            ui.selectedSessionId = forked
            if (forked != null) runCatching { repository.watchSession(host, forked) }
            onDraft(draft)
        }
    }
}
