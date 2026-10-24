package com.deepseek.dsh

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import com.deepseek.dsh.core.data.DshRepository

/**
 * 下载当前会话日志并保存到系统 Download 目录（官方 `/api/session.export`）。
 *
 * 放在独立文件里，是为了让 `MainAppHost` 只保留一行桥接赋值——它是接近 ART
 * verifier 上限的巨型 composable，所有上下文逻辑都不应该内联进去。
 *
 * @param context 用于 ContentResolver 与 Toast。
 * @param repository 数据层，负责带鉴权地 GET 导出路由。
 * @param hostId 当前 Host。
 * @param sessionId 要导出的会话。
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

/** 通过 MediaStore 写入 Download 目录；返回是否成功拿到 uri。 */
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
