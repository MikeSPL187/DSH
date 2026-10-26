package com.deepseek.dsh

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.deepseek.dsh.feature.chat.ChatAttachment
import java.io.File

/**
 * Скопировать Uri из системного выбора файлов в `attachments/` рабочей папки.
 *
 * Агент под proot видит только рабочую папку (/workspace/...), поэтому вложение должно
 * лежать в ней, а сообщение ссылается на `@attachments/<имя>`. Копирование потоковое,
 * файл целиком в память не читается; одинаковые имена получают номер, ничего не перезаписывается.
 */
object AttachmentImporter {

    data class Result(
        val added: List<ChatAttachment>,
        /** Имена файлов, которые не удалось прочитать или скопировать (для тоста). */
        val skipped: List<String>,
    )

    fun import(context: Context, workspaceDir: File, uris: List<Uri>): Result {
        val targetDir = File(workspaceDir, ATTACHMENTS_DIR).apply { mkdirs() }
        val added = mutableListOf<ChatAttachment>()
        val skipped = mutableListOf<String>()

        for (uri in uris) {
            val meta = queryMeta(context, uri)
            val displayName = meta?.first ?: "file-${System.currentTimeMillis()}"
            val sizeBytes = meta?.second ?: 0L
            val target = uniqueFile(targetDir, displayName)
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("openInputStream returned null")
            }
            if (copied.isSuccess) {
                added.add(
                    ChatAttachment(
                        displayName = target.name,
                        workspaceRelativePath = "$ATTACHMENTS_DIR/${target.name}",
                        sizeBytes = target.length(),
                        localPath = target.absolutePath,
                    ),
                )
            } else {
                runCatching { target.delete() }
                skipped.add(displayName)
            }
        }
        return Result(added = added, skipped = skipped)
    }

    /** Импорт: скопировать любой файл из SAF в указанную папку рабочей области (её видит Host). */
    fun importInto(context: Context, targetDir: File, uris: List<Uri>): Result {
        targetDir.mkdirs()
        val added = mutableListOf<ChatAttachment>()
        val skipped = mutableListOf<String>()
        for (uri in uris) {
            val meta = queryMeta(context, uri)
            val displayName = meta?.first ?: "file-${System.currentTimeMillis()}"
            val target = uniqueFile(targetDir, displayName)
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("openInputStream returned null")
            }
            if (copied.isSuccess) {
                added.add(
                    ChatAttachment(
                        displayName = target.name,
                        workspaceRelativePath = target.absolutePath,
                        sizeBytes = target.length(),
                        localPath = target.absolutePath,
                    ),
                )
            } else {
                runCatching { target.delete() }
                skipped.add(displayName)
            }
        }
        return Result(added = added, skipped = skipped)
    }

    /** Имя и размер файла из SAF; null при ошибке (тогда запасное имя). */
    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long>? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            if (name.isNullOrBlank()) null else name to size
        }
    }.getOrNull()

    /** Без перезаписи: name.png → name-1.png → name-2.png. */
    private fun uniqueFile(dir: File, name: String): File {
        // Имя приходит от стороннего провайдера: отрезаем путь, управляющие символы и «.»/«..».
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "attachment"
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$index$ext")
            index += 1
        }
        return candidate
    }

    private const val ATTACHMENTS_DIR = "attachments"
}
