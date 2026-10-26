package com.deepseek.dsh.runtime.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Свежий источник манифеста rootfs.
 *
 * Манифест нельзя упаковывать в APK: в нём адреса загрузки и SHA-256, они меняются с каждым выпуском rootfs,
 * встроенный тут же устаревает, и новые пользователи шли бы по адресу, которого нет (или с неверной суммой).
 *
 * Поэтому манифест берётся при работе из последнего выпуска репозитория rootfs. В APK — только постоянный адрес,
 * который с выпусками не устаревает.
 *
 * Три источника по убыванию:
 * 1. **Сеть**: `rootfs-manifest.json` последнего выпуска; при успехе пишется в кэш;
 * 2. **Кэш**: последний удачно загруженный манифест, доступен без сети;
 * 3. **Встроенный запасной**: только если ни одной удачной загрузки не было, и он явно помечен как запасной.
 */
class RootfsManifestSource(
    private val context: Context,
    private val layout: RuntimeLayout,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Источник манифеста — чтобы интерфейс и журнал говорили, насколько можно верить цифрам. */
    enum class Origin {
        /** Только что из сети. */
        NETWORK,

        /** Кэш последней удачной загрузки. */
        CACHE,

        /** Встроенный в APK запасной; может быть устаревшим. */
        BUNDLED,
    }

    /** Итог разбора: сам манифест и его источник. */
    data class Resolved(
        val manifest: RootfsManifest,
        val origin: Origin,
    )

    /**
     * Получить действующий манифест.
     *
     * Ошибка сети — не исключение: манифест решает «можно ли ставить», а без сети кэш или запасной обычно
     * позволяют установленным пользователям работать дальше. Насколько верить — по [Resolved.origin].
     *
     * Удалённые и кэшированные манифесты с частью DSH, не совпадающей со встроенным, пропускаются: патчи rootfs APK
     * заменяют файлы целиком по версии DSH встроенного манифеста, и применение к другой версии смешало бы версии в рабочем дереве.
     * @param forceRefresh пропустить кэш и загрузить заново.
     * @return итог; если нет даже запасного — null (APK собран с ошибкой).
     */
    suspend fun resolve(forceRefresh: Boolean = false): Resolved? = withContext(Dispatchers.IO) {
        val expectedDsh = bundledDshVersion()
        if (!forceRefresh) {
            cachedManifest()
                ?.takeIf { compatible(it, expectedDsh) }
                ?.let { return@withContext Resolved(it, Origin.CACHE) }
        }
        fetchRemote()?.let { remote ->
            writeCache(remote)
            if (compatible(remote, expectedDsh)) {
                return@withContext Resolved(remote, Origin.NETWORK)
            }
            android.util.Log.w(
                TAG,
                "Ignoring rootfs manifest for an incompatible DSH: ${remote.runtimeVersion} (expected dsh${expectedDsh ?: "?"})",
            )
        }
        // Ошибка сети или несовместимый удалённый манифест: вернуться к последнему удачному совместимому кэшу.
        cachedManifest()
            ?.takeIf { compatible(it, expectedDsh) }
            ?.let { return@withContext Resolved(it, Origin.CACHE) }
        bundledManifest()?.let { return@withContext Resolved(it, Origin.BUNDLED) }
        null
    }

    /** Версия DSH встроенного манифеста; нет или неверный формат — null. */
    fun bundledDshVersion(): String? =
        bundledManifest()?.runtimeVersion?.let(RootfsNaming::dshVersion)

    /** Совпадает ли версия DSH этого манифеста с целью патчей текущего APK. */
    private fun compatible(manifest: RootfsManifest, expectedDsh: String?): Boolean =
        // Образу со вшитыми при сборке патчами Android часть DSH не важна: файлы в нём уже
        // «последний DSH + патчи», приложение не заменяет их целиком, поэтому манифест принимается сразу.
        manifest.androidPatched || RootfsNaming.isDshCompatible(manifest.runtimeVersion, expectedDsh)

    /** Файл кэша манифеста; рядом с меткой установки rootfs, чтобы не засорять сам rootfs. */
    private fun cacheFile(): File = File(layout.baseDir, "runtime/${RootfsNaming.MANIFEST_CACHE_FILE}")

    /** Прочитать кэш. */
    private fun cachedManifest(): RootfsManifest? {
        val file = cacheFile()
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(RootfsManifest.serializer(), file.readText())
                .takeIf { it.runtimeVersion.isNotBlank() }
        }.getOrNull()
    }

    /** Записать кэш; ошибка на текущее использование не влияет. */
    private fun writeCache(manifest: RootfsManifest) {
        runCatching {
            val file = cacheFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(RootfsManifest.serializer(), manifest))
        }
    }

    /** Загрузить манифест из последнего выпуска репозитория rootfs. */
    private fun fetchRemote(): RootfsManifest? {
        val request = Request.Builder()
            .url(resolveManifestUrl())
            .header("User-Agent", "dsh-android")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching null
                json.decodeFromString(RootfsManifest.serializer(), body)
                    .takeIf { it.runtimeVersion.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Встроенный в APK запасной манифест; нет или повреждён — null. */
    private fun bundledManifest(): RootfsManifest? = runCatching {
        val raw = context.assets.open(RootfsNaming.MANIFEST_ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString(RootfsManifest.serializer(), raw)
            .takeIf { it.runtimeVersion.isNotBlank() }
    }.getOrNull()

    /**
     * Через GitHub API найти прямую ссылку на манифест в «последнем выпуске».
     *
     * Переадресация 302 `releases/latest/download/...` какое-то время кэшируется CDN: сразу после того, как новый выпуск
     * стал latest, клиент мог получить манифест прошлого. latest в API отвечает по текущему
     * состоянию выпуска. Если API недоступен — постоянный адрес с переадресацией.
     */
    private fun resolveManifestUrl(): String {
        val apiUrl = runCatching {
            val request = Request.Builder()
                .url(RELEASE_API_URL)
                .header("User-Agent", "dsh-android")
                .header("Accept", "application/vnd.github+json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                root["assets"]?.jsonArray
                    ?.firstOrNull {
                        it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "rootfs-manifest.json"
                    }
                    ?.jsonObject
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
        }.getOrNull()
        return apiUrl ?: LATEST_MANIFEST_URL
    }

    private companion object {
        /** Тег Logcat. */
        const val TAG = "DshRootfsManifest"

        /** GitHub API: последний выпуск, не prerelease. */
        const val RELEASE_API_URL =
            "https://api.github.com/repos/EricFL1998/deepseek-harness-mobile-rootfs/releases/latest"

        /**
         * Постоянный адрес с переадресацией — когда API недоступен. CDN может ненадолго отдавать старую версию,
         * поэтому это запасной путь, а не основной.
         */
        const val LATEST_MANIFEST_URL =
            "https://github.com/EricFL1998/deepseek-harness-mobile-rootfs/releases/latest/download/rootfs-manifest.json"
    }
}
