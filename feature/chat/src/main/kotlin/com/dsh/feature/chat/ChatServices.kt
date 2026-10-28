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

    /** Отдать текст разговора системному «Поделиться» (длинный — файлом [fileName]). */
    fun shareText(title: String, text: String, fileName: String) {}

    /** Что даст возврат к сообщению пользователя; null — возврат недоступен (пункта «Вернуться сюда» нет). */
    suspend fun rewindPlan(message: com.dsh.core.model.ChatMessage): RewindPlan? = null

    /** Вернуть файлы проекта к моменту перед [message]; успех — сколько файлов изменилось. */
    suspend fun rewindFiles(message: com.dsh.core.model.ChatMessage): Result<Int> = Result.failure(UnsupportedOperationException())

    /** Новая ветка разговора до [message], его текст — в поле ввода. */
    fun rewindConversation(message: com.dsh.core.model.ChatMessage) {}

    /** Можно ли отменить последний возврат файлов в открытой сессии (читается в композиции — состояние Compose). */
    fun canUndoRewind(): Boolean = false

    /** Отменить последний возврат файлов; успех — сколько файлов изменилось. */
    suspend fun undoRewind(): Result<Int> = Result.failure(UnsupportedOperationException())

    /**
     * Что даст «Другой вариант» для [message] (сообщение пользователя, на которое ответ); null — варианты
     * недоступны, «Повторить» просто отправляет текст ещё раз.
     */
    suspend fun variantPlan(message: com.dsh.core.model.ChatMessage): VariantPlan? = null

    /** Сохранить результат, вернуть файлы к точке перед [message] и решить задачу заново в развилке. */
    fun startVariant(message: com.dsh.core.model.ChatMessage) {}

    /** Открытая сессия — один из вариантов: какой по счёту и сколько их (состояние Compose); null — нет. */
    fun variantsInfo(): VariantsInfo? = null

    /** Варианты группы открытой сессии: что изменил каждый. */
    suspend fun variantRows(): List<VariantRow> = emptyList()

    /** Оставить вариант [index]: его файлы и разговор. */
    fun keepVariant(index: Int) {}

    object None : ChatServices
}

/**
 * План «Другого варианта».
 *
 * @param number под каким номером сохранится текущий результат.
 * @param files что этот результат изменил относительно точки; null — точки нет (вариант начнётся поверх файлов).
 */
data class VariantPlan(val number: Int, val files: List<String>?)

data class VariantsInfo(val current: Int, val count: Int)

/** Строка листа «Варианты»: номер (с 1), изменённые файлы, открыт ли сейчас. */
data class VariantRow(val number: Int, val files: List<String>, val current: Boolean)

/** Что станет с файлом при возврате: изменится, удалится (создан после сообщения), вернётся (удалён после). */
enum class RewindChange { MODIFIED, REMOVED, RESTORED }

/**
 * План возврата к сообщению.
 *
 * @param hasPoint есть ли точка возврата файлов (снимается перед каждым сообщением).
 * @param files что изменится в файлах; пусто — с тех пор не менялись.
 */
data class RewindPlan(val hasPoint: Boolean, val files: List<Pair<String, RewindChange>>)

private val NoImages = MutableStateFlow<Map<String, List<AttachmentImageRef>>>(emptyMap())

/** Сервисы чата для экрана; приложение подставляет свою реализацию. */
val LocalChatServices = staticCompositionLocalOf<ChatServices> { ChatServices.None }
