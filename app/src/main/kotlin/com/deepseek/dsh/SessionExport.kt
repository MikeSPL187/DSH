package com.deepseek.dsh

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import com.deepseek.dsh.core.data.DshRepository

/**
 * Скачать журнал текущей сессии и сохранить в Download (`/api/session.export`).
 *
 * Отдельный файл — чтобы в `MainAppHost` осталась одна строка: логике
 * с context не место в корневой функции интерфейса.
 *
 * @param context для ContentResolver и тоста.
 * @param repository слой данных: скачивание с авторизацией.
 * @param hostId текущий Host.
 * @param sessionId сессия для выгрузки.
 */
suspend fun performSessionExport(
    context: Context,
    repository: DshRepository,
    hostId: String,
    sessionId: String,
) {
    val bytes = runCatching { repository.exportSessionLog(hostId, sessionId) }.getOrNull()
    if (bytes == null || bytes.isEmpty()) {
        Toast.makeText(
            context,
            context.getString(R.string.session_export_failed),
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val saved = runCatching { saveZip(context, sessionId, bytes) }.getOrDefault(false)
    Toast.makeText(
        context,
        context.getString(
            if (saved) R.string.session_export_saved else R.string.session_export_failed,
        ),
        Toast.LENGTH_LONG,
    ).show()
}

/** Запись в Download через MediaStore; true — uri получен. */
private fun saveZip(context: Context, sessionId: String, bytes: ByteArray): Boolean {
    val name = "dsh-session-" + sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".zip"
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
        // MediaStore.Downloads есть только с Android 10; раньше экспорт на Android 8–9 падал.
        @Suppress("DEPRECATION")
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        return runCatching {
            dir.mkdirs()
            java.io.File(dir, name).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return false
    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

/**
 * Отдаёт созданный архив (резервная копия, диагностика) через системное «Поделиться».
 *
 * Раньше результат кнопок игнорировался: ни сообщения, ни доступа к файлу — архив лежал
 * во внутренней папке приложения, куда пользователь попасть не может. В общие «Загрузки»
 * резервную копию класть нельзя: в ней учётные данные `$DSH_HOME`, а оттуда её прочитает
 * любое приложение с доступом к хранилищу. Поэтому — FileProvider с временным правом
 * чтения только для выбранного пользователем получателя.
 */
fun shareArchive(context: Context, result: Result<java.io.File>, chooserTitle: String, failedMessage: String) {
    val file = result.getOrNull()
    if (file == null || !file.isFile) {
        val reason = result.exceptionOrNull()?.message
        Toast.makeText(
            context,
            if (reason.isNullOrBlank()) failedMessage else "$failedMessage: $reason",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    pruneOldArchives(file.parentFile, keep = 3)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, chooserTitle)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
    }
}

/** Старые архивы копятся во внутренней памяти; оставляем несколько последних. */
private fun pruneOldArchives(dir: java.io.File?, keep: Int) {
    dir?.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(keep)
        ?.forEach { runCatching { it.delete() } }
}
