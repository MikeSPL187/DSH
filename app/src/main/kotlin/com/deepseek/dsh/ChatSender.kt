package com.deepseek.dsh

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.feature.chat.ChatAttachment
import com.deepseek.dsh.runtime.host.HostRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Модель для новой сессии: id в каталоге Host и провайдер. */
data class ModelChoice(val modelId: String, val providerId: String)

/**
 * Отправка сообщения из чата: «/команда» — Host (`commands/execute`, неизвестная команда
 * уходит обычным сообщением), иначе prompt в текущую сессию или новую (создаётся с первым
 * сообщением, при сбое Host перезапускается один раз). Картинки-вложения уходят частью
 * prompt (base64), файлы — ссылками «@путь». Пока ответ не пришёл, сообщение видно в
 * [pendingMessages]; при ошибке текст не теряется.
 */
class ChatSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: DshRepository,
    private val ui: AppUiState,
    private val runtimeUi: AppRuntimeUiState,
    private val runtimeManager: HostRuntimeManager,
    private val pendingMessages: SnapshotStateList<ChatMessage>,
    private val attachments: SnapshotStateList<ChatAttachment>,
    private val workspaceRoot: () -> File,
) {
    fun send(text: String, model: ModelChoice) {
        // Слэш-команда идёт в commands/execute; незнакомую Host
        // отправляем обычным prompt.
        val commandLine = text.trim()
        val commandSession = ui.selectedSessionId
        val handledAsCommand = commandLine.startsWith("/") &&
            commandSession != null
        if (handledAsCommand) {
            val sid = commandSession!!
            scope.launch {
                val result = repository.commands.executeHostCommand(
                    ui.activeHostId, sid, commandLine,
                )
                if (result != null) {
                    val message = result.text ?: result.kind
                    android.widget.Toast.makeText(
                        context,
                        message,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    repository.refreshSessionStats(
                        ui.activeHostId, sid,
                    )
                } else {
                    // Host не знает команды — отправить как сообщение.
                    runCatching {
                        repository.sendMessage(
                            ui.activeHostId, sid, commandLine,
                        )
                    }
                }
            }
        }
        if (!handledAsCommand) {
        val localId = "local_${System.nanoTime()}"
        val isPlanPrompt = runtimeUi.chatPlanMode
        val outgoingText = if (isPlanPrompt) {
            context.getString(R.string.plan_prompt_prefix) + text
        } else {
            text
        }
        // Картинки уходят частью image внутри prompt (base64) — модель с
        // поддержкой изображений видит их; остальные файлы — ссылкой @.
        val outgoingAttachments = attachments.toList()
        val outgoingImages = outgoingAttachments.mapNotNull { attachment ->
            val name = attachment.displayName
            val extension = name.substringAfterLast('.', "").lowercase()
            val mediaType = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> return@mapNotNull null
            }
            // Путь может быть от корня рабочих папок или от папки сессии
            // (вложения копируются в <root>/<workspaceName>/attachments).
            val relative = attachment.workspaceRelativePath
            val file = listOf(
                java.io.File(relative),
                java.io.File(workspaceRoot(), relative),
                java.io.File(java.io.File(workspaceRoot(), runtimeUi.activeWorkspaceName), relative),
            ).firstOrNull { it.isFile }
            if (file == null) return@mapNotNull null
            // Кодирование (и уменьшение) — в фоне, перед отправкой.
            Triple(file, mediaType, name)
        }
        attachments.clear()
        pendingMessages.add(
            ChatMessage(
                hostId = ui.activeHostId,
                sessionId = ui.selectedSessionId ?: "pending",
                messageId = localId,
                role = MessageRole.USER,
                content = text,
                createdAt = System.currentTimeMillis()
            )
        )
        runtimeUi.isSending = true
        scope.launch {
            try {
            var sid = ui.selectedSessionId
            var createdSession = false
            // Открытая сессия используется всегда. liveSessionIds значит только
            // «идёт ход» и пустеет после хода: раньше по нему каждое второе сообщение
            // создавало новую сессию (казалось, что сессия меняется, а ответа нет).
            if (sid == null) {
                suspend fun createOnce(): String? = repository.createSession(
                    ui.activeHostId,
                    text.take(24),
                    "/workspace/${runtimeUi.activeWorkspaceName}",
                    (ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView).takeIf { it.isNotBlank() }
                ).getOrNull()?.sessionId
                sid = createOnce()
                if (sid == null) {
                    // Host мог упасть или ещё не готов: один перезапуск и повтор.
                    runtimeManager.start(workspaceRoot().absolutePath)
                    kotlinx.coroutines.delay(10000)
                    sid = createOnce()
                }
                if (sid != null) {
                    // mkdir Host под proot может вернуть ENOENT — папку сессии создаёт приложение.
                    // Не-ASCII символы имени рабочей папки Host заменяет на ~XXXX.
                    java.io.File(
                        context.filesDir,
                        "rootfs/root/.dsh/sessions/${workspaceSessionDirName(runtimeUi.activeWorkspaceName)}/$sid"
                    ).mkdirs()
                    ui.selectedSessionId = sid
                    createdSession = true
                }
            }
            if (sid != null) {
                // Выбор модели в modelSelection пишется только для новой сессии;
                // у существующей не перезаписывается (раньше провайдер каждый раз брался
                // из аккаунта, у Kimi и моделей подписки он ломался, и ответ был пустым).
                if (createdSession) {
                    repository.selectModel(
                        ui.activeHostId,
                        sid,
                        model.modelId,
                        model.providerId,
                    )
                }
                val encodedImages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    outgoingImages.mapNotNull { (file, mediaType, name) ->
                        runCatching { PromptImageEncoder.encode(file, mediaType, name) }.getOrNull()
                    }
                }
                repository.sendMessage(ui.activeHostId, sid, outgoingText, encodedImages)
            } else {
                android.widget.Toast.makeText(
                    context, context.getString(R.string.session_create_failed), android.widget.Toast.LENGTH_LONG
                ).show()
                // Keep what the user typed visible: the composer is
                // already cleared, so dropping the bubble loses the text.
                pendingMessages.add(
                    ChatMessage(
                        hostId = ui.activeHostId,
                        sessionId = "pending",
                        messageId = "local_failed_${System.nanoTime()}",
                        role = MessageRole.USER,
                        content = text,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                pendingMessages.add(
                    ChatMessage(
                        hostId = ui.activeHostId,
                        sessionId = "pending",
                        messageId = "local_error_${System.nanoTime()}",
                        role = MessageRole.ASSISTANT,
                        content = "⚠️ " + context.getString(R.string.session_create_failed),
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            } finally {
                pendingMessages.removeAll { it.messageId == localId }
                runtimeUi.isSending = false
            }
        }
        }
    }
}
