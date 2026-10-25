package com.deepseek.dsh.feature.chat

/**
 * Вложение, выбранное в системном выборе файлов.
 *
 * Файл уже скопирован в `attachments/` текущей рабочей папки; сообщение ссылается на него
 * как `@<путь в рабочей папке>`, и агент читает настоящий файл.
 */
data class ChatAttachment(
    /** Исходное имя файла для показа (display name из выбора файлов). */
    val displayName: String,
    /** Путь внутри рабочей папки после копирования (например, `attachments/photo.png`). */
    val workspaceRelativePath: String,
    /** Размер в байтах. */
    val sizeBytes: Long,
    /** Путь к файлу на телефоне — для миниатюры фото в поле ввода. */
    val localPath: String? = null,
)

/**
 * Ссылка на вложение для агента: `@attachments/photo.png` —
 * как «@ файл или диалог» в веб-клиенте: модель читает путь после @
 * как файл рабочей папки.
 */
fun ChatAttachment.asMessageReference(): String = "@$workspaceRelativePath"

/**
 * Уходит ли картинка частью image внутри prompt.
 *
 * Часть image передаёт байты картинки модели напрямую; дополнительная ссылка `@путь`
 * только добавила бы лишнюю строку в пузырь, поэтому картинок среди @-ссылок нет.
 */
fun ChatAttachment.isImageAttachment(): Boolean = when (
    displayName.substringAfterLast('.', "").lowercase()
) {
    "png", "jpg", "jpeg", "webp", "gif" -> true
    else -> false
}

/** Размер для строки вложений в поле ввода. */
fun ChatAttachment.formattedSize(): String {
    if (sizeBytes <= 0) return ""
    val kb = sizeBytes / 1024.0
    return when {
        kb < 1024 -> "%.0f KB".format(kb)
        else -> "%.1f MB".format(kb / 1024.0)
    }
}
