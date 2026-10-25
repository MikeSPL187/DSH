package com.deepseek.dsh.feature.settings

import com.deepseek.dsh.core.designsystem.kit.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal const val DSH_GITHUB_REPO = "EricFL1998/deepseek-harness-mobile"
internal const val DSH_RELEASES_PAGE = "https://github.com/$DSH_GITHUB_REPO/releases"
private const val DSH_RELEASES_API = "https://api.github.com/repos/$DSH_GITHUB_REPO/releases?per_page=20"

data class DshUpdateInfo(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
)

/**
 * Обновления: после запуска тихо проверяется GitHub Release; «Новая версия» видна, только если она есть.
 *
 * Здесь только обновление **самого APK**. Обновления bridge-плагина и rootfs оценивает
 * [com.deepseek.dsh.runtime.host.HostRuntimeManager.assessUpdates]:
 * плагин обязателен, образ необязателен; оба показываются только в настройках и не прерывают запуск.
 */
object UpdateCenter {
    private val _update = MutableStateFlow<DshUpdateInfo?>(null)
    val update: StateFlow<DshUpdateInfo?> = _update.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var checked = false

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "1.0.0" }

    fun hasUpdate(context: Context): Boolean {
        val latest = _update.value ?: return false
        // Отличать от полной версии с -mobile: версия десктопа — не новая версия мобильной.
        return latest.tag.isNotBlank() && latest.tag != currentVersion(context)
    }

    suspend fun check(context: Context) {
        if (_checking.value) return
        _checking.value = true
        _error.value = null
        try {
            val info = withContext(Dispatchers.IO) {
                val connection = (URL(DSH_RELEASES_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "dsh-android")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        error(com.deepseek.dsh.core.model.Tr.t("GitHub returned HTTP ${connection.responseCode}", "GitHub вернул HTTP ${connection.responseCode}"))
                    }
                    val root = org.json.JSONArray(connection.inputStream.bufferedReader().readText())
                    // Источник обновлений — только релизы самого Android-приложения; версии rootfs-* и т. п. пропускаются.
                    val release = (0 until root.length()).mapNotNull { index ->
                        root.optJSONObject(index)?.takeIf { item ->
                            val tag = item.optString("tag_name")
                            tag.startsWith("android-v") || tag.startsWith("app-v")
                        }
                    }.firstOrNull() ?: return@withContext null
                    val tag = release.optString("tag_name")
                        .removePrefix("android-v").removePrefix("app-v")
                    if (tag.isBlank()) error(com.deepseek.dsh.core.model.Tr.t("Release has no version", "В релизе нет номера версии"))
                    DshUpdateInfo(
                        tag = tag,
                        name = release.optString("name").ifBlank { release.optString("tag_name") },
                        body = release.optString("body"),
                        htmlUrl = release.optString("html_url", DSH_RELEASES_PAGE),
                    )
                } finally {
                    connection.disconnect()
                }
            }
            checked = true
            if (info != null) _update.value = info
        } catch (t: Throwable) {
            _error.value = t.message ?: com.deepseek.dsh.core.model.Tr.t("Update check failed", "Не удалось проверить обновления")
        } finally {
            _checking.value = false
        }
    }

    suspend fun ensureChecked(context: Context) {
        if (!checked) check(context)
    }
}




