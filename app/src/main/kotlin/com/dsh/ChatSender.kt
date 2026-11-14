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

/** Сессии ещё нет: пузырь первого сообщения новой сессии. */
internal const val PENDING_SESSION = "pending"

/**
 * Отправка сообщения из чата: «/команда» — Host (`commands/execute`, неизвестная команда
 * уходит обычным сообщением), иначе prompt в текущую сессию или новую (создаётся с первым
 * сообщением, при сбое Host перезапускается один раз). Картинки-вложения уходят частью
 * prompt (base64), файлы — ссылками «@путь». Пока ответ не пришёл, сообщение видно в
 * private journal. Отказ Host или сети — не потеря: пузырь остаётся с пометкой
 * «Не отправлено» и кнопкой «Повторить», которая отправляет тот же текст и те же картинки.
 */
class ChatSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: DshRepository,
    private val ui: AppUiState,
    private val runtimeUi: AppRuntimeUiState,
    private val runtimeManager: HostRuntimeManager,
    private val attachments: SnapshotStateList<ChatAttachment>,
    private val workspaceRoot: () -> File,
    /** Точки возврата файлов открытого проекта; null — недоступны. */
    private val checkpoints: () -> com.dsh.runtime.host.FileCheckpoints? = { null },
) {
    private val journal = (context.applicationContext as DshApplication).chatRunJournal

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
                    dispatch(SavedPrompt(commandLine, commandLine, emptyList(), model))
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
            ).firstOrNull { it.isFile } ?: File(workspaceRoot(), relative)
            // Кодирование (и уменьшение) — в фоне, перед отправкой.
            SavedImage(file.absolutePath, mediaType, attachment.displayName)
        }
        attachments.clear()
        dispatch(SavedPrompt(text, prompt, images, model, ui.pendingPermissionPreset.takeIf { ui.selectedSessionId == null }))
    }

    /** «Повторить» у неотправленного сообщения. */
    fun retry(messageId: String) {
        val outgoing = journal.records.value.firstOrNull { it.id == messageId } ?: return
        if (outgoing.status in ChatRunJournal.ACTIVE) return
        ui.activeHostId = outgoing.hostId
        ui.selectedSessionId = outgoing.sessionId
        dispatch(outgoing.copy(id = "", status = com.dsh.core.model.RunStatus.QUEUED, error = null, partial = "", startedSeq = 0, conversationId = if (outgoing.sessionId == null) ui.pendingChatId else outgoing.conversationId), replacing = messageId)
    }

    private fun dispatch(outgoing: SavedPrompt, replacing: String? = null) {
        val submission = journal.reserveSubmission(ui.activeHostId)
        val request = outgoing.copy(
            id = java.util.UUID.randomUUID().toString(),
            hostId = ui.activeHostId,
            sessionId = ui.selectedSessionId,
            workspace = outgoing.workspace.ifBlank { workspaceRoot().absolutePath },
            workspaceName = outgoing.workspaceName.ifBlank { runtimeUi.activeWorkspaceName },
            createdAt = System.currentTimeMillis(),
            submissionOrder = submission.order,
            conversationId = outgoing.conversationId.ifBlank { ui.pendingChatId },
        )
        (context.applicationContext as DshApplication).applicationScope.launch {
            runtimeUi.isSending = true
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    journal.put(request)
                    if (replacing != null) journal.remove(replacing)
                }
                submission.previous?.await()
                val error = deliver(request, request.id)
                if (error != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        journal.update(request.id) { it.copy(status = com.dsh.core.model.RunStatus.FAILED, error = error) }
                    }
                }
            } catch (cancelled: CancellationException) {
                // The application-owned journal survives Activity recreation and process death.
                throw cancelled
            } catch (error: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    journal.update(request.id) { it.copy(status = com.dsh.core.model.RunStatus.FAILED, error = friendlySendError(error.message.orEmpty())) }
                }
            } finally { runtimeUi.isSending = false; submission.completion.complete(Unit) }
        }
    }

    /** Отправить; null — Host принял prompt, иначе причина отказа. */
    private suspend fun deliver(outgoing: SavedPrompt, localId: String): String? {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        if (network == null || connectivity.getNetworkCapabilities(network)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return com.dsh.core.model.Tr.t("No network connection. Connect and retry.", "Нет подключения к сети. Подключитесь и повторите.")
        }
        var sid = outgoing.sessionId ?: journal.sessionForConversation(outgoing.hostId, outgoing.conversationId)
        var createdSession = false
        // Открытая сессия используется всегда. liveSessionIds значит только
        // «идёт ход» и пустеет после хода: раньше по нему каждое второе сообщение
        // создавало новую сессию (казалось, что сессия меняется, а ответа нет).
        if (sid == null) {
            suspend fun createOnce(): String? = repository.createSession(
                outgoing.hostId,
                sessionTitle(outgoing.text),
                "/workspace/${outgoing.workspaceName}",
                (ui.pendingAgentPreset ?: ui.currentAppSettings.transcriptView).takeIf { it.isNotBlank() },
            ).getOrNull()?.sessionId
            sid = createOnce()
            if (sid == null) {
                // Host мог упасть или ещё не готов: один перезапуск и повтор.
                runtimeManager.start(outgoing.workspace)
                kotlinx.coroutines.delay(10000)
                sid = createOnce()
            }
            if (sid == null) return context.getString(R.string.session_create_failed)
            // mkdir Host под proot может вернуть ENOENT — папку сессии создаёт приложение.
            // Не-ASCII символы имени рабочей папки Host заменяет на ~XXXX.
            File(
                context.filesDir,
                "$HOST_SESSIONS_DIR/${workspaceSessionDirName(outgoing.workspaceName)}/$sid",
            ).mkdirs()
            if (ui.pendingChatId == outgoing.conversationId) ui.selectedSessionId = sid
            createdSession = true
            val created = sid
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { journal.bindConversation(outgoing.hostId, outgoing.conversationId, created) }
        }
        val targetSession = sid
        // The previous queued request may have created the session but failed model setup.
        // Sharing its session ID is not evidence that the selected model reached the Host.
        val needsModelSelection = createdSession || outgoing.requiresModelSelection || journal.records.value.any {
            it.hostId == outgoing.hostId && it.sessionId == targetSession && it.requiresModelSelection
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            journal.update(localId) { it.copy(sessionId = targetSession, requiresModelSelection = needsModelSelection) }
        }
        // Выбор модели в modelSelection пишется только для новой сессии;
        // у существующей не перезаписывается (раньше провайдер каждый раз брался
        // из аккаунта, у Kimi и моделей подписки он ломался, и ответ был пустым).
        if (needsModelSelection) {
            // Модель, уровень рассуждений и права, выбранные до первого сообщения (G-23): раньше выбор без
            // сессии молча пропадал, и новая сессия шла с правами по умолчанию. Отказ — видимое сообщение.
            val selected = repository.selectModel(outgoing.hostId, sid, outgoing.model.modelId, outgoing.model.providerId, outgoing.model.reasoningEffort)
            if (selected.isFailure) return context.getString(R.string.model_switch_failed, selected.exceptionOrNull()?.message.orEmpty())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                journal.modelSelected(outgoing.hostId, targetSession)
            }
        }
        // Права не применились — prompt не уходит: иначе агент работал бы с правами по умолчанию.
        outgoing.permission?.let { preset ->
            if (!repository.selectPermissionPreset(outgoing.hostId, sid, preset)) return context.getString(R.string.perm_switch_failed)
            if (ui.pendingPermissionPreset == preset) ui.pendingPermissionPreset = null
        }
        val encodedImages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            outgoing.images.map { image ->
                require(File(image.path).isFile) { context.getString(R.string.send_attachment_missing) }
                requireNotNull(PromptImageEncoder.encode(File(image.path), image.mediaType, image.name)) { context.getString(R.string.send_attachment_missing) }
            }
        }
        snapshotBefore(sid, outgoing.prompt)
        return repository.sendMessage(outgoing.hostId, sid, outgoing.prompt, encodedImages)
            .exceptionOrNull()?.let { friendlySendError(it.message.orEmpty(), (it as? com.dsh.core.data.HostRequestException)?.code) }
    }

    /**
     * Точка возврата файлов перед сообщением. Ждём её не дольше [SNAPSHOT_WAIT_MS]: обычно снимок после прогрева
     * занимает доли секунды, а первая правка агента случается не раньше ответа модели. Не успела — снимок
     * доделается в фоне, отправка не задерживается; не снялась — отправка от этого не зависит.
     */
    private suspend fun snapshotBefore(sid: String, prompt: String) {
        val cp = checkpoints() ?: return
        val job = scope.launch(kotlinx.coroutines.Dispatchers.IO) { cp.snapshot(sid, prompt) }
        kotlinx.coroutines.withTimeoutOrNull(SNAPSHOT_WAIT_MS) { job.join() }
    }

    private fun toast(text: String, long: Boolean = false) = android.widget.Toast.makeText(
        context, text, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
    ).show()
}

private const val SNAPSHOT_WAIT_MS = 2_000L

/** Тип картинки, которая уходит частью image внутри prompt; null — файл уходит ссылкой @. */
private fun imageTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}

/** Keep complete words in generated session titles; the UI handles visual ellipsis. */
internal fun sessionTitle(text: String): String {
    return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

internal fun friendlySendError(raw: String, code: String? = null): String = if (code?.startsWith("dsh/auth/") == true) raw
else if (Regex("transport|network|connect|timeout|socket", RegexOption.IGNORE_CASE).containsMatchIn(raw))
    com.dsh.core.model.Tr.t("Could not reach the Host or model. Check the connection and retry.", "Нет связи с Host или моделью. Проверьте соединение и повторите.")
else raw.ifBlank { com.dsh.core.model.Tr.t("The request was not sent. You can retry it.", "Запрос не отправлен. Его можно повторить.") }
