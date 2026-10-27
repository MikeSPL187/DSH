package com.dsh.feature.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.dsh.core.model.MessageFeedbackInfo
import com.dsh.core.model.WorkflowRunInfo
import com.dsh.core.protocol.AttachmentImageRef
import com.dsh.core.protocol.SessionRawFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Возможности Host, которые нужны чату для открытой сессии: оценки ответов, картинки
 * в сообщениях, запуски workflow, кадры для траектории, ветвление и выгрузка.
 * Данные — потоками: чат обновляется сам, когда Host что-то прислал.
 * Реализация — в приложении; [None] — ничего нет (превью и снимки экранов).
 */
interface ChatServices {
    /** Оценки ответов по id сообщения; null — оценки недоступны (кнопок нет). */
    val feedback: StateFlow<Map<String, MessageFeedbackInfo>>? get() = null

    /** Перечитать оценки (после новых сообщений). */
    fun refreshFeedback() {}

    /** Поставить оценку; [rating] null — снять. */
    fun rate(messageId: String, rating: String?, note: String?) {}

    /** Картинки в сообщениях пользователя: id сообщения → ссылки на вложения. */
    val images: StateFlow<Map<String, List<AttachmentImageRef>>> get() = NoImages

    /** Байты картинки вложения; null — не удалось загрузить. */
    suspend fun loadImage(ref: AttachmentImageRef): ByteArray? = null

    /** Запуски workflow в открытой сессии. */
    val workflowRuns: Flow<List<WorkflowRunInfo>> get() = flowOf(emptyList())

    /** Сырые кадры сессии для траектории (снимок на момент открытия). */
    fun sessionFrames(): List<SessionRawFrame> = emptyList()

    /** Продолжить в новой сессии с этого места. */
    fun forkAt(seq: Int) {}

    /** Выгрузить журнал сессии. */
    fun exportSession() {}

    object None : ChatServices
}

private val NoImages = MutableStateFlow<Map<String, List<AttachmentImageRef>>>(emptyMap())

/** Сервисы чата для экрана; приложение подставляет свою реализацию. */
val LocalChatServices = staticCompositionLocalOf<ChatServices> { ChatServices.None }
