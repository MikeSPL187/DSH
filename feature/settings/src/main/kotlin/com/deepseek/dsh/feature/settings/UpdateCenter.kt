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

/** Свой репозиторий приложения (G-46): обновления, «Что нового» и «Лицензии» — только отсюда. */
const val DSH_GITHUB_REPO = "MikeSPL187/DSH"
const val DSH_RELEASES_PAGE = "https://github.com/$DSH_GITHUB_REPO/releases"
private const val DSH_RELEASES_API = "https://api.github.com/repos/$DSH_GITHUB_REPO/releases?per_page=20"

/** Лицензии сторонних компонентов (proot — GPL-2.0) в нашем репозитории. */
const val DSH_LICENSES_PAGE = "https://github.com/$DSH_GITHUB_REPO/tree/HEAD/third_party"

/** Релиз из ответа GitHub — только то, что нужно для выбора. */
internal data class ReleaseEntry(val tag: String, val draft: Boolean, val prerelease: Boolean)

/** Числовая часть версии: `1.0.6` → [1, 0, 6]; `1.0-debug` → [1, 0]; не версия — null. */
internal fun versionNumbers(version: String): List<Int>? {
    val core = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    return core.split('.').map { it.toIntOrNull() ?: return null }
}

/**
 * [candidate] новее [current] (G-18): сравниваются числа (`1.0.10` > `1.0.9`), недостающие — нули.
 * Суффикс сборки текущей версии (`-debug`) не учитывается; не версия — не новее.
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = versionNumbers(candidate) ?: return false
    val b = versionNumbers(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** Тег релиза приложения без префикса; другие теги (rootfs-*, плагины) — null. */
internal fun appReleaseVersion(tag: String): String? =
    listOf("android-v", "app-v").firstOrNull { tag.startsWith(it) }?.let { tag.removePrefix(it) }?.takeIf { versionNumbers(it) != null }

/** Самый новый выпущенный релиз приложения: без черновиков и предрелизов. */
internal fun pickLatestRelease(releases: List<ReleaseEntry>): ReleaseEntry? =
    releases.filter { !it.draft && !it.prerelease && appReleaseVersion(it.tag) != null }
        .reduceOrNull { best, next -> if (isNewerVersion(appReleaseVersion(next.tag)!!, appReleaseVersion(best.tag)!!)) next else best }

/** Ссылка релиза — только на наш репозиторий, иначе страница релизов. */
internal fun safeReleaseUrl(url: String?): String =
    url?.takeIf { it.startsWith("https://github.com/$DSH_GITHUB_REPO/") } ?: DSH_RELEASES_PAGE

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
        return isNewerVersion(latest.tag, currentVersion(context))
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
                    // Источник обновлений — только выпущенные релизы самого приложения; rootfs-*, черновики
                    // и предрелизы пропускаются, из остальных берётся самая новая версия, а не первая в списке.
                    val items = (0 until root.length()).mapNotNull { root.optJSONObject(it) }
                    val best = pickLatestRelease(items.map { ReleaseEntry(it.optString("tag_name"), it.optBoolean("draft"), it.optBoolean("prerelease")) })
                        ?: return@withContext null
                    val release = items.first { it.optString("tag_name") == best.tag }
                    DshUpdateInfo(
                        tag = appReleaseVersion(best.tag)!!,
                        name = release.optString("name").ifBlank { best.tag },
                        body = release.optString("body"),
                        htmlUrl = safeReleaseUrl(release.optString("html_url")),
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




