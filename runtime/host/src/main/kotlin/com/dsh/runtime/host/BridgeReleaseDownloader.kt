package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Загрузчик архива bridge-плагина (zip с одной верхней папкой, вложение нашего релиза).
 *
 * Через собственный HTTP приложения, а не `git`/`pnpm` в Host:
 * Host работает в proot, где DNS Android недоступен (`getent` возвращает
 * EAI_AGAIN), и ни GitHub, ни реестр npm не разрешаются; процесс приложения использует системный разборщик,
 * и в той же сети всё скачивается.
 */
class BridgeReleaseDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    /**
     * Скачать архив исходников тега.
     * @param repository адрес репозитория GitHub, например `https://github.com/owner/repo`.
     * @param version выпущенная версия плагина (без префикса `v`).
     * @param onProgress ход загрузки (0..1) и подпись шага.
     * @return байты архива; превышение размера, ошибка HTTP или неверный номер версии — исключение.
     */
    suspend fun download(
        repository: String,
        version: String,
        onProgress: (Float, String) -> Unit,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = archiveUrl(repository, version)
            ?: throw IllegalArgumentException(com.dsh.core.model.Tr.t("Install source is not a recognised GitHub repository: $repository", "Источник установки — не распознанный репозиторий GitHub: $repository"))
        onProgress(0f, com.dsh.core.model.Tr.t("Connecting to GitHub…", "Подключение к GitHub…"))
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "dsh-android")
            .header("Accept", "application/zip")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(com.dsh.core.model.Tr.t("Plugin archive download failed: HTTP ${response.code}", "Не удалось скачать архив плагина: HTTP ${response.code}"))
            }
            val body = response.body ?: throw IllegalStateException(com.dsh.core.model.Tr.t("Plugin archive response is empty", "Пустой ответ с архивом плагина"))
            val contentLength = body.contentLength()
            if (contentLength > BridgePluginArchive.MAX_ARCHIVE_BYTES) {
                throw IllegalStateException(com.dsh.core.model.Tr.t("Plugin archive is too large: ${contentLength} bytes", "Архив плагина слишком большой: ${contentLength} байт"))
            }
            val output = ByteArrayOutputStream(
                contentLength.takeIf { it in 1..BridgePluginArchive.MAX_ARCHIVE_BYTES }?.toInt() ?: 64 * 1024
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var received = 0L
            var lastReported = -1
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    received += read
                    if (received > BridgePluginArchive.MAX_ARCHIVE_BYTES) {
                        throw IllegalStateException(com.dsh.core.model.Tr.t("Plugin archive exceeds the 32 MB limit", "Архив плагина больше лимита 32 МБ"))
                    }
                    output.write(buffer, 0, read)
                    if (contentLength > 0) {
                        val percent = ((received * 100) / contentLength).toInt().coerceIn(0, 100)
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent / 100f, com.dsh.core.model.Tr.t("Downloading plugin archive $percent%", "Загрузка архива плагина $percent%"))
                        }
                    }
                }
            }
            onProgress(1f, com.dsh.core.model.Tr.t("Plugin archive downloaded (${output.size()} bytes)", "Архив плагина загружен (${output.size()} байт)"))
            output.toByteArray()
        }
    }

    companion object {
        /** Выпущенная версия — только три числа, чтобы произвольная строка не попала в путь URL. */
        private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+$""")

        /** `owner/repo` из адреса репозитория; не GitHub — null. */
        fun repositorySlug(repository: String): String? {
            val trimmed = repository.trim().removeSuffix("/").removeSuffix(".git")
            val match = GITHUB_REPOSITORY.find(trimmed) ?: return null
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            if (owner.isBlank() || repo.isBlank()) return null
            return "$owner/$repo"
        }

        /**
         * Адрес архива плагина: вложение релиза `bridge-plugin-v<версия>` репозитория из `bridge-plugin.json`
         * (наш репозиторий; архив собирает workflow «Bridge plugin» из `third_party/bridge-plugin`).
         * Раньше — исходники тега чужого репозитория через codeload. Содержимое в любом случае сверяется
         * с `contentSha256` из APK.
         * @param repository адрес репозитория GitHub.
         * @param version выпущенная версия.
         * @return URL архива; репозиторий или версия недопустимы — null.
         */
        fun archiveUrl(repository: String, version: String): String? {
            if (!VERSION_PATTERN.matches(version)) return null
            val slug = repositorySlug(repository) ?: return null
            return "https://github.com/$slug/releases/download/bridge-plugin-v$version/${BridgePluginManifest.DEFAULT_PACKAGE}-$version.zip"
        }

        private val GITHUB_REPOSITORY = Regex("""github\.com[/:]([^/]+)/([^/]+)$""")
    }
}
