package com.dsh

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.dsh.core.data.DshRepository
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.feature.chat.ChatAttachment
import com.dsh.runtime.host.HostRuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Модель для новой сессии: id в каталоге Host, провайдер и уровень рассуждений (G-23). */
data class ModelChoice(val modelId: String, val providerId: String, val reasoningEffort: String? = null)

/** Сессии ещё нет: пузырь первого сообщения новой сессии. */
internal const val PENDING_SESSION = "pending"

/**
 * Отправка сообщения из чата: «/команда» — Host (`commands/execute`, неизвестная команда
 * уходит обычным сообщением), иначе prompt в текущую сессию или новую (создаётся с первым
 * сообщением, при сбое Host перезапускается один раз). Картинки-вложения уходят частью
 * prompt (base64), файлы — ссылками «@путь». Пока ответ не пришёл, сообщение видно в
 * [pendingMessages]. Отказ Host или сети — не потеря: пузырь остаётся с пометкой
 * «Не отправлено» и кнопкой «Повторить», которая отправляет тот же текст и те же картинки.
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
    /** Что отправить: текст пузыря, текст для Host (с префиксом плана), картинки и модель новой сессии. */
    private data class Outgoing(
        val text: String,
        val prompt: String,
        val images: List<Triple<File, String, String>>,
        val model: ModelChoice,
        /** Права, выбранные до первого сообщения: применяются до prompt, и при «Повторить» — тоже. */
        val permission: String? = null,
    )

    /** Неотправленные сообщения: id пузыря → что отправить по «Повторить». */
    private val failed = mutableMapOf<String, Outgoing>()

    /**
     * «Сжать сейчас» из листа «Контекст и расход»: `/compact` прямо в `commands/execute`, мимо поля ввода —
     * черновик и вложения остаются на месте. Итог Host — тостом, затем свежая заполненность.
     */
    fun compactNow() {
        val sid = ui.selectedSessionId ?: return
        toast(context.getString(com.dsh.feature.chat.R.string.ctx_compacting))
        scope.launch {
            val result = repository.commands.executeHostCommand(ui.activeHostId, sid, "/compact")
            toast(result?.let { it.text ?: it.kind } ?: context.getString(com.dsh.feature.chat.R.string.ctx_compact_failed))
            repository.refreshSessionStats(ui.activeHostId, sid)
        }
    }

    fun send(text: String, model: ModelChoice) {
        // Слэш-команда идёт в commands/execute; незнакомую Host отправляем обычным prompt.
        val commandLine = text.trim()
        val commandSession = ui.selectedSessionId
        if (commandLine.startsWith("/") && commandSession != null) {
            // Вложения уже в тексте команды (@путь); картинки команде не передать — сказать, а не
            // оставлять их в поле: иначе они молча ушли бы со следующим сообщением.
            if (attachments.any { imageTypeOf(it.displayName) != null }) toast(context.getString(R.string.cmd_images_skipped))
            attachments.clear()
            scope.launch {
                val result = repository.commands.executeHostCommand(ui.activeHostId, commandSession, commandLine)
                if (result != null) {
                    toast(result.text ?: result.kind)
                    repository.refreshSessionStats(ui.activeHostId, commandSession)
                } else {
                    // Host не знает команды (или не ответил) — отправить как сообщение; сбой виден в пузыре.
                    dispatch(Outgoing(commandLine, commandLine, emptyList(), model))
                }
            }
            return
        }
        val prompt = if (runtimeUi.chatPlanMode) context.getString(R.string.plan_prompt_prefix) + text else text
        // Картинки уходят частью image внутри prompt (base64) — модель с
        // поддержкой изображений видит их; остальные файлы — ссылкой @.
        val images = attachments.mapNotNull { attachment ->
            val mediaType = imageTypeOf(attachment.displayName) ?: return@mapNotNull null
            // Путь может быть от корня рабочих папок или от папки сессии
            // (вложения копируются в <root>/<workspaceName>/attachments).
            val relative = attachment.workspaceRelativePath
            val file = listOf(
                File(relative),
                File(workspaceRoot(), relative),
                File(File(workspaceRoot(), runtimeUi.activeWorkspaceName), relative),
            ).firstOrNull { it.isFile } ?: return@mapNotNull null
            // Кодирование (и уменьшение) — в фоне, перед отправкой.
            Triple(file, mediaType, attachment.displayName)
        }
        attachments.clear()
        dispatch(Outgoing(text, prompt, images, model, ui.pendingPermissionPreset.takeIf { ui.selectedSessionId == null }))
    }

    /** «Повторить» у неотправленного сообщения. */
    fun retry(messageId: String) {
        val outgoing = failed.remove(messageId) ?: return
        pendingMessages.removeAll { it.messageId == messageId }
        dispatch(outgoing)
    }

    private fun dispatch(outgoing: Outgoing) {
        val localId = "local_${System.nanoTime()}"
        pendingMessages.add(
            ChatMessage(
                hostId = ui.activeHostId,
                sessionId = ui.selectedSessionId ?: PENDING_SESSION,
                messageId = localId,
                role = MessageRole.USER,
                content = outgoing.text,
                createdAt = System.currentTimeMillis(),
            ),
        )
        runtimeUi.isSending = true
        scope.launch {
            val error = try {
                deliver(outgoing, localId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message.orEmpty()
            } finally {
                runtimeUi.isSending = false
            }
            val index = pendingMessages.indexOfFirst { it.messageId == localId }
            if (error == null) {
                if (index >= 0) pendingMessages.removeAt(index)
            } else {
                // Пузырь остаётся: текст пользователя не теряется, «Повторить» отправит его снова.
                if (index >= 0) pendingMessages[index] = pendingMessages[index].copy(failed = true)
                failed[localId] = outgoing
                toast(context.getString(R.string.send_failed_toast, error).trimEnd(' ', ':'), long = true)
            }
        }
    }

    /** Отправить; null — Host принял prompt, иначе причина отказа. */
    private suspend fun deliver(outgoing: Outgoing, localId: String): String? {
        var sid = ui.selectedSessionId
        var createdSession = false
        // Открытая сессия используется всегда. liveSessionIds значит только
        // «идёт ход» и пустеет после хода: раньше по нему каждое второе сообщение
        // создавало новую сессию (казалось, что сессия меняется, а ответа нет).
        if (sid == null) {
            suspend fun createOnce(): String? = repository.createSession(
                ui.activeHostId,
                outgoing.text.take(24),
                "/workspace/${runtimeUi.activeWorkspaceName}",
                (ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView).takeIf { it.isNotBlank() },
            ).getOrNull()?.sessionId
            sid = createOnce()
            if (sid == null) {
                // Host мог упасть или ещё не готов: один перезапуск и повтор.
                runtimeManager.start(workspaceRoot().absolutePath)
                kotlinx.coroutines.delay(10000)
                sid = createOnce()
            }
            if (sid == null) return context.getString(R.string.session_create_failed)
            // mkdir Host под proot может вернуть ENOENT — папку сессии создаёт приложение.
            // Не-ASCII символы имени рабочей папки Host заменяет на ~XXXX.
            File(
                context.filesDir,
                "$HOST_SESSIONS_DIR/${workspaceSessionDirName(runtimeUi.activeWorkspaceName)}/$sid",
            ).mkdirs()
            ui.selectedSessionId = sid
            createdSession = true
            // Пузырь переходит в новую сессию, чтобы не пропасть с экрана до эха Host.
            val index = pendingMessages.indexOfFirst { it.messageId == localId }
            if (index >= 0) pendingMessages[index] = pendingMessages[index].copy(sessionId = sid)
            failed.keys.toList().forEach { id ->
                val i = pendingMessages.indexOfFirst { it.messageId == id && it.sessionId == PENDING_SESSION }
                if (i >= 0) pendingMessages[i] = pendingMessages[i].copy(sessionId = sid)
            }
        }
        // Выбор модели в modelSelection пишется только для новой сессии;
        // у существующей не перезаписывается (раньше провайдер каждый раз брался
        // из аккаунта, у Kimi и моделей подписки он ломался, и ответ был пустым).
        if (createdSession) {
            // Модель, уровень рассуждений и права, выбранные до первого сообщения (G-23): раньше выбор без
            // сессии молча пропадал, и новая сессия шла с правами по умолчанию. Отказ — видимое сообщение.
            repository.selectModel(ui.activeHostId, sid, outgoing.model.modelId, outgoing.model.providerId, outgoing.model.reasoningEffort)
                .onFailure { toast(context.getString(R.string.model_switch_failed, it.message.orEmpty()), long = true) }
        }
        // Права не применились — prompt не уходит: иначе агент работал бы с правами по умолчанию.
        outgoing.permission?.let { preset ->
            if (!repository.selectPermissionPreset(ui.activeHostId, sid, preset)) return context.getString(R.string.perm_switch_failed)
            if (ui.pendingPermissionPreset == preset) ui.pendingPermissionPreset = null
        }
        val encodedImages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            outgoing.images.mapNotNull { (file, mediaType, name) ->
                runCatching { PromptImageEncoder.encode(file, mediaType, name) }.getOrNull()
            }
        }
        return repository.sendMessage(ui.activeHostId, sid, outgoing.prompt, encodedImages)
            .exceptionOrNull()?.let { it.message.orEmpty() }
    }

    private fun toast(text: String, long: Boolean = false) = android.widget.Toast.makeText(
        context, text, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
    ).show()
}

/** Тип картинки, которая уходит частью image внутри prompt; null — файл уходит ссылкой @. */
private fun imageTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}
