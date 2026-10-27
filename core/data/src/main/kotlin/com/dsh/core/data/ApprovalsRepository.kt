package com.dsh.core.data

import com.dsh.core.protocol.ANSWER_TIMEOUT_MS
import com.dsh.core.protocol.DshClientProtocol
import com.dsh.core.protocol.ForwardedWaterfall
import com.dsh.core.protocol.QUESTION_TIMEOUT_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Запросы агента к пользователю: одобрение действия (`approval/request`) и вопросы
 * (`user-questions/request`). Остальные запросы waterfall Host решает сам.
 */
class ApprovalsRepository internal constructor(
    private val client: DshClientProtocol,
    private val scope: CoroutineScope,
) {
    private val _pendingApprovalsFlow = MutableStateFlow<List<ForwardedWaterfall>>(emptyList())
    val pendingWaterfallApprovalsFlow: StateFlow<List<ForwardedWaterfall>> = _pendingApprovalsFlow.asStateFlow()

    private val _pendingQuestionsFlow = MutableStateFlow<List<ForwardedWaterfall>>(emptyList())
    val pendingQuestionsFlow: StateFlow<List<ForwardedWaterfall>> = _pendingQuestionsFlow.asStateFlow()

    /**
     * Ответ на запрос одобрения без участия пользователя: true — разрешить, false — отклонить,
     * null — спросить, как обычно. Так работают режимы инструментов «Сразу» и «Выкл.».
     */
    @Volatile
    var approvalPolicy: ((ForwardedWaterfall) -> Boolean?)? = null

    /** Итог каждого запроса одобрения: (запрос, разрешён, ответил ли пользователь сам). */
    private val _approvalOutcomes = MutableSharedFlow<Triple<ForwardedWaterfall, Boolean, Boolean>>(extraBufferCapacity = 64)
    val approvalOutcomes: SharedFlow<Triple<ForwardedWaterfall, Boolean, Boolean>> = _approvalOutcomes.asSharedFlow()

    /** Ответ пользователя на запрос одобрения: `allowed-once` или `rejected`. */
    suspend fun respondApprovalWaterfall(hostId: String, eventId: String, approved: Boolean) {
        _pendingApprovalsFlow.value.firstOrNull { it.eventId == eventId }
            ?.let { _approvalOutcomes.tryEmit(Triple(it, approved, true)) }
        _pendingApprovalsFlow.update { list -> list.filter { it.eventId != eventId } }
        client.answerWaterfall(hostId, eventId, approvalAnswer(approved))
    }

    /** Ответ на вопрос агента: `{answers:[{id,selected:[label],custom?}]}`. */
    suspend fun respondUserQuestion(hostId: String, eventId: String, answersJson: String) {
        _pendingQuestionsFlow.update { list -> list.filter { it.eventId != eventId } }
        client.answerWaterfall(hostId, eventId, answersJson)
    }

    /** Новый запрос от Host. */
    internal suspend fun onWaterfall(hostId: String, waterfall: ForwardedWaterfall) {
        when (waterfall.event) {
            "approval/request" -> {
                val auto = runCatching { approvalPolicy?.invoke(waterfall) }.getOrNull()
                if (auto != null) {
                    _approvalOutcomes.tryEmit(Triple(waterfall, auto, false))
                    client.answerWaterfall(hostId, waterfall.eventId, approvalAnswer(auto))
                } else {
                    // К сроку без ответа клиент уже отправил Host `next` (запрос отклонён):
                    // окно убирается, иначе «Разрешить» на просроченном запросе ничего не сделает.
                    hold(_pendingApprovalsFlow, waterfall, ANSWER_TIMEOUT_MS)
                }
            }
            "user-questions/request" -> hold(_pendingQuestionsFlow, waterfall, QUESTION_TIMEOUT_MS)
            else -> client.answerWaterfall(hostId, waterfall.eventId, null)
        }
    }

    private fun hold(flow: MutableStateFlow<List<ForwardedWaterfall>>, waterfall: ForwardedWaterfall, timeoutMs: Long) {
        flow.update { list -> list.filter { it.eventId != waterfall.eventId } + waterfall }
        scope.launch {
            delay(timeoutMs)
            flow.update { list -> list.filter { it.eventId != waterfall.eventId } }
        }
    }

    private fun approvalAnswer(approved: Boolean) = if (approved) "\"allowed-once\"" else "\"rejected\""
}
