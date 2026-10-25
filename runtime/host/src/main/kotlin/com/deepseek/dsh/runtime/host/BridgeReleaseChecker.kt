package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Последняя выпущенная версия репозитория bridge-плагина.
 *
 * У выпуска нет файлов-вложений, сам тег `v<version>` объявляет «последнюю версию».
 * Это единственное место чтения: по нему Android решает, подсказывать ли обновление в настройках, а конвейер rootfs —
 * какую версию вложить.
 *
 * Неудачный запрос — не ошибка: без сети «не узнали» нельзя считать «уже последняя», и прерывать
 * пользователя ошибкой тоже не надо; возвращается null, вызывающий считает «нельзя определить».
 */
class BridgeReleaseChecker(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Версия последнего выпуска репозитория плагина.
     *
     * Результат ненадолго кэшируется: настройки спрашивают при каждом обновлении, а у GitHub API без авторизации
     * всего 60 запросов в час; без кэша частое открытие панели их исчерпает.
     *
     * Сначала API (отсеивает черновики и предварительные выпуски); если API недоступен (офлайн, лимит, 404) —
     * переадресация 302 `releases/latest`: у неё нет лимита API, полученный тег и есть
     * «последний выпуск». null — только если не удались оба пути.
     * @param repository адрес репозитория GitHub, например `https://github.com/owner/repo`.
     * @return версия (без префикса `v`); офлайн, 404 или странный формат — null.
     */
    suspend fun latestVersion(repository: String): String? = withContext(Dispatchers.IO) {
        val slug = repositorySlug(repository) ?: return@withContext null
        val now = System.currentTimeMillis()
        cached(slug, now)?.let { return@withContext it.version }
        val version = fetchLatestFromApi(slug) ?: fetchLatestFromRedirect(slug)
        remember(slug, version, now)
        version
    }

    /** Запрос к API выпусков; кэш — у вызывающего. */
    private fun fetchLatestFromApi(slug: String): String? {
        val url = "https://api.github.com/repos/$slug/releases?per_page=10"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "dsh-android")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                // Список по убыванию времени выпуска; берём первый тег не черновика и не предварительного выпуска.
                json.parseToJsonElement(body).jsonArray
                    .asSequence()
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { release ->
                        release["draft"]?.jsonPrimitive?.content?.toBoolean() != true &&
                            release["prerelease"]?.jsonPrimitive?.content?.toBoolean() != true
                    }
                    ?.get("tag_name")
                    ?.jsonPrimitive
                    ?.content
                    ?.removePrefix("v")
                    ?.takeIf { isPlainVersion(it) }
            }
        } catch (_: Exception) {
            // Офлайн или лимит: дальше — запасной путь переадресации.
            null
        }
    }

    /**
     * Запасной путь: переадресация 302 `https://github.com/<slug>/releases/latest`.
     *
     * По переадресации не идём (иначе скачается вся страница выпуска), только читаем тег из `Location`.
     * Если выпусков нет, GitHub ведёт на `/releases` без тега — тогда null.
     */
    private fun fetchLatestFromRedirect(slug: String): String? {
        val request = Request.Builder()
            .url("https://github.com/$slug/releases/latest")
            .header("User-Agent", "dsh-android")
            .build()
        return try {
            // Тот же пул соединений и диспетчер, но без перехода по переадресации.
            val client = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 300..399) return null
                val location = response.header("Location") ?: return null
                val resolved = request.url.resolve(location) ?: return null
                val segments = resolved.pathSegments
                val tag = segments.getOrNull(segments.indexOf("tag") + 1)
                    ?.removePrefix("v")
                    ?.takeIf { isPlainVersion(it) }
                tag
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `owner/repo` из адреса репозитория.
     * @param repository полный адрес GitHub, можно с `.git` и косой чертой в конце.
     * @return `owner/repo`; не разобрать — null.
     */
    private fun repositorySlug(repository: String): String? {
        val trimmed = repository.trim().removeSuffix("/").removeSuffix(".git")
        val match = GITHUB_REPO.find(trimmed) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    private companion object {
        private val GITHUB_REPO = Regex("""github\.com[/:]([^/]+)/([^/]+)$""")

        /** Тег выпуска — только три числовые части; тег с суффиксом не метка версии этого плагина. */
        private val PLAIN_VERSION = Regex("""^\d+\.\d+\.\d+$""")

        /** Подходит ли под вид версии выпуска плагина. */
        private fun isPlainVersion(value: String): Boolean = PLAIN_VERSION.matches(value)

        /** Срок годности удачного результата: выпуск делается вручную, за 5 минут не изменится. */
        private const val SUCCESS_TTL_MS = 5 * 60 * 1000L

        /** Срок годности неудачного: минута, чтобы при лимите каждое обновление не превращалось в запрос. */
        private const val FAILURE_TTL_MS = 60 * 1000L

        /** Итог одного запроса: `version` null — тогда не узнали. */
        private data class CacheEntry(val version: String?, val fetchedAt: Long)

        private val cache = HashMap<String, CacheEntry>()

        /** Кэш, если ещё действителен; истёк или нет — null. */
        private fun cached(slug: String, now: Long): CacheEntry? = synchronized(cache) {
            val entry = cache[slug] ?: return null
            val ttl = if (entry.version == null) FAILURE_TTL_MS else SUCCESS_TTL_MS
            entry.takeIf { now - it.fetchedAt <= ttl }
        }

        /** Запомнить итог запроса. */
        private fun remember(slug: String, version: String?, now: Long) {
            synchronized(cache) { cache[slug] = CacheEntry(version, now) }
        }
    }
}
