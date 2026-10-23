package com.deepseek.dsh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * Доступ к общей папке `Documents/DSH-Workspace` с учётом версии Android.
 *
 * `Environment.isExternalStorageManager()` и экран «Доступ ко всем файлам» появились
 * только в Android 11 (API 30). Приложение поддерживает Android 8+ (minSdk 26), а
 * прямой вызов на Android 8–10 падал с NoSuchMethodError при каждом запуске.
 * На этих версиях нужен обычный runtime-доступ WRITE_EXTERNAL_STORAGE.
 */
internal object StorageAccess {

    /** Разрешения, которые запрашиваются диалогом на Android 8–10. */
    val legacyPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /** Нужен ли системный экран «Доступ ко всем файлам» (Android 11+). */
    val usesAllFilesSettings: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Только для JVM-тестов (Robolectric не эмулирует проверку «всех файлов»). */
    @androidx.annotation.VisibleForTesting
    @Volatile
    var overrideForTests: Boolean? = null

    fun hasAccess(context: Context): Boolean = overrideForTests ?: runCatching {
        if (usesAllFilesSettings) {
            Environment.isExternalStorageManager()
        } else {
            legacyPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }.getOrDefault(false)
}
