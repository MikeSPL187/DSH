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
        // 斜杠命令走官方 commands/execute；Host 不认识的
        // 回退成普通 prompt。
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
                    // Host 不认识这条命令：按普通消息发送。
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
        // 图片附件走官方 prompt 的内联 image part（base64），
        // 这样视觉模型能直接看到内容；普通文件仍用 @ 引用。
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
            // 相对路径可能相对工作区根，也可能相对会话目录
            // （附件实际复制进 <root>/<workspaceName>/attachments）。
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
            // 官方语义：当前会话存在就一直复用它。liveSessionIds 只表示
            // “正在运行”，上一轮结束后就为空；以前用它判断会每发第二条
            // 消息就新建会话（于是看起来自动开新会话、没有回复）。
            if (sid == null) {
                suspend fun createOnce(): String? = repository.createSession(
                    ui.activeHostId,
                    text.take(24),
                    "/workspace/${runtimeUi.activeWorkspaceName}",
                    (ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView).takeIf { it.isNotBlank() }
                ).getOrNull()?.sessionId
                sid = createOnce()
                if (sid == null) {
                    // Host 可能刚崩溃/尚未就绪：重启一次再重试。
                    runtimeManager.start(workspaceRoot().absolutePath)
                    kotlinx.coroutines.delay(10000)
                    sid = createOnce()
                }
                if (sid != null) {
                    // proot 下 Host 的 mkdir 可能返回 ENOENT；先在 Android 侧建好会话目录。
                    // Host 会把工作区目录名中的非 ASCII 码点转义为 ~XXXX。
                    java.io.File(
                        context.filesDir,
                        "rootfs/root/.dsh/sessions/${workspaceSessionDirName(runtimeUi.activeWorkspaceName)}/$sid"
                    ).mkdirs()
                    ui.selectedSessionId = sid
                    createdSession = true
                }
            }
            if (sid != null) {
                // 只有刚创建的会话需要把当前选择写入 modelSelection；
                // 已有会话不能再覆盖（之前每次都按账号 provider 重选，
                // Kimi/订阅模型的 provider 被改错，回复变空白）。
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
