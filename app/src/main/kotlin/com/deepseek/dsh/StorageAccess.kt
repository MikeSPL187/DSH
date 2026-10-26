package com.deepseek.dsh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * Доступ к рабочей папке: общей `Documents/DSH-Workspace` (с учётом версии Android)
 * или приватной папке приложения.
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

    private const val PREFS = "dsh_storage"
    private const val KEY_PRIVATE_WORKSPACE = "private_workspace"

    /**
     * Аудит F-07: приватная рабочая папка в каталоге приложения
     * (`Android/data/<пакет>/files/DSH-Workspace`). Для неё не нужен доступ ко всем
     * файлам: пользователь может отказаться от MANAGE_EXTERNAL_STORAGE, Host монтирует
     * эту папку так же, как общую, а файлы извне по-прежнему импортируются копированием.
     */
    fun usesPrivateWorkspace(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PRIVATE_WORKSPACE, false)
    }.getOrDefault(false)

    fun choosePrivateWorkspace(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRIVATE_WORKSPACE, true).commit()
    }

    /** Вернуть рабочую папку в общую память телефона (нужен доступ ко всем файлам). */
    fun chooseSharedWorkspace(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRIVATE_WORKSPACE, false).commit()
    }

    /** Корень рабочей папки — единственный источник пути для UI, Host и моста. */
    fun workspaceRoot(context: Context): java.io.File =
        if (usesPrivateWorkspace(context)) {
            java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "DSH-Workspace")
        } else {
            @Suppress("DEPRECATION")
            java.io.File(Environment.getExternalStorageDirectory(), "Documents/DSH-Workspace")
        }

    fun hasAccess(context: Context): Boolean = overrideForTests ?: (usesPrivateWorkspace(context) || runCatching {
        if (usesAllFilesSettings) {
            Environment.isExternalStorageManager()
        } else {
            legacyPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }.getOrDefault(false))
}
