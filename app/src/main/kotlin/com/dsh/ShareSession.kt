package com.dsh

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.dsh.core.data.DshRepository
import com.dsh.core.model.ChatMessage
import com.dsh.feature.chat.ShareSessionSheetBody
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Сколько страниц ранней истории догружать перед «Поделиться». */
private const val MAX_PAGES = 50

/** Длиннее — уходит файлом `.md`: не каждое приложение примет столько текста. */
private const val MAX_SHARE_TEXT = 60_000

/**
 * «Поделиться разговором» сессии [sessionId]: лист с Markdown без секретов; «Скопировать» — в буфер,
 * «Поделиться» — системный выбор приложения (текстом или файлом из личного кэша по FileProvider).
 */
@Composable
internal fun ColumnScope.ShareSessionSheet(
    context: Context,
    repository: DshRepository,
    hostId: String,
    sessionId: String,
    title: String,
    project: String,
    onClose: () -> Unit,
) {
    // null — ещё загружается. Ранняя история догружается страницами: делиться половиной разговора нельзя.
    val messages by produceState<List<ChatMessage>?>(null, hostId, sessionId) {
        runCatching { repeat(MAX_PAGES) { if (!repository.loadOlderMessages(hostId, sessionId)) return@runCatching } }
        value = runCatching { repository.getMessages(hostId, sessionId).first() }.getOrDefault(emptyList())
    }
    val date = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    ShareSessionSheetBody(
        title = title,
        header = listOf("DSH", date, project).filter { it.isNotBlank() }.joinToString(" · "),
        messages = messages,
        onCopy = { text ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(title, text))
            android.widget.Toast.makeText(context, context.getString(com.dsh.feature.chat.R.string.sh_copied), android.widget.Toast.LENGTH_SHORT).show()
            onClose()
        },
        onShare = { text, name -> onClose(); shareMarkdown(context, title, text, name) },
    )
}

internal fun shareMarkdown(context: Context, title: String, text: String, name: String) {
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title)
    if (text.length <= MAX_SHARE_TEXT) {
        send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    } else {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Прошлые выгрузки не копятся: в папке только последняя.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name).apply { writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        send.setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { android.widget.Toast.makeText(context, context.getString(R.string.share_failed), android.widget.Toast.LENGTH_LONG).show() }
}
