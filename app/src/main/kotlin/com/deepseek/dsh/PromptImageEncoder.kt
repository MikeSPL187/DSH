package com.deepseek.dsh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deepseek.dsh.core.protocol.PromptImagePart
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Кодирует вложенное изображение для inline image part запроса.
 *
 * Раньше файл до 20 МБ читался целиком и кодировался в base64 прямо в главном потоке
 * (зависание интерфейса и риск OutOfMemory на слабых телефонах), а фото с камеры
 * уходило в исходном разрешении, хотя модели всё равно уменьшают его. Теперь крупные
 * изображения уменьшаются до [MAX_SIDE] по большей стороне и сжимаются в JPEG; вызывать
 * только из фонового потока.
 */
internal object PromptImageEncoder {
    const val MAX_SIDE = 2048
    /** Небольшие изображения отправляются как есть (сохраняются прозрачность и анимация). */
    const val PASS_THROUGH_BYTES = 1L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    fun encode(file: File, mediaType: String, name: String): PromptImagePart? {
        if (!file.isFile || file.length() > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val small = file.length() <= PASS_THROUGH_BYTES && longest in 1..MAX_SIDE
        if (small || mediaType == "image/gif" && file.length() <= PASS_THROUGH_BYTES) {
            return PromptImagePart(mediaType = mediaType, base64 = base64(file.readBytes()), name = name)
        }
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = rotateByExif(file, scaleDown(bitmap))
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        return PromptImagePart(
            mediaType = "image/jpeg",
            base64 = base64(out.toByteArray()),
            name = name.substringBeforeLast('.') + ".jpg",
        )
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Перекодирование теряет EXIF: применяем поворот, иначе фото с камеры уйдёт боком. Платформенный ExifInterface
     * (minSdk 26 — исправленная версия): androidx-версия была бы новой зависимостью ради одного тега (G-40).
     */
    @android.annotation.SuppressLint("ExifInterface")
    private fun rotateByExif(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            android.graphics.Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private const val JPEG_QUALITY = 85
}
