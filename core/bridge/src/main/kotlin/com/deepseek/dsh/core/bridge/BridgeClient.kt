package com.deepseek.dsh.core.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Клиент bridge/v1.
 *
 * Делает три вещи: согласует manifest, отправляет один `query` и разбирает ответ в [BridgeResult].
 * Конкретных provider он не знает: смысл по id разбирает вызывающий, незнакомые id пропускаются,
 * поэтому новый provider в Host не требует обновления клиента.
 *
 * Cookie и авторизация — от сессии Host: переданный [OkHttpClient] несёт тот же CookieJar,
 * своих учётных данных мост не создаёт.
 */
class BridgeClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = BridgeJson.instance,
) {
    private val origin: HttpUrl? = baseUrl.toHttpUrlOrNull()

    /** Кэш manifest: в пределах сессии каталог provider Host не меняется, пока нет переподключения. */
    @Volatile
    private var cachedManifest: BridgeManifest? = null

    /** Одно согласование на всех: если страницы стартуют разом, manifest запрашивается один раз. */
    private val manifestLock = Mutex()

    /** Прочитать (или взять из кэша) каталог provider. */
    suspend fun manifest(forceRefresh: Boolean = false): BridgeResult<BridgeManifest> {
        val cached = cachedManifest
        if (!forceRefresh && cached != null) return BridgeResult.Success(cached)
        return manifestLock.withLock {
            // Пока ждали блокировку, согласование мог закончить другой корутин — проверить ещё раз.
            cachedManifest?.takeIf { !forceRefresh }?.let { return@withLock BridgeResult.Success(it) }
            val url = urlFor(BridgeProtocol.MANIFEST_PATH)
                ?: return@withLock BridgeResult.Failure(
                    BridgeErrorCode.TRANSPORT_UNREACHABLE,
                    "invalid bridge base URL: $baseUrl",
                )
            val request = Request.Builder().url(url).get().build()
            execute(request) { body ->
                val manifest = json.decodeFromString(BridgeManifest.serializer(), body)
                if (!manifest.isCompatible()) {
                    return@execute BridgeResult.Failure(
                        BridgeErrorCode.PROVIDER_VERSION_MISMATCH,
                        "Host speaks bridge ${manifest.bridgeVersion}, client speaks ${BridgeProtocol.BRIDGE_VERSION}",
                    )
                }
                cachedManifest = manifest
                BridgeResult.Success(manifest)
            }
        }
    }

    /**
     * Отправить один запрос к provider.
     *
     * Успех — разобранные данные; ошибка — стабильный код, частичных результатов не бывает.
     */
    suspend fun <T> query(
        provider: String,
        op: String,
        args: Map<String, JsonElement> = emptyMap(),
        decode: (JsonElement) -> T,
    ): BridgeResult<T> {
        val url = urlFor(BridgeProtocol.QUERY_PATH)
            ?: return BridgeResult.Failure(
                BridgeErrorCode.TRANSPORT_UNREACHABLE,
                "invalid bridge base URL: $baseUrl",
            )
        val payload = json.encodeToString(
            BridgeQuery.serializer(),
            BridgeQuery(provider = provider, op = op, args = args),
        )
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request) { body ->
            val response = json.decodeFromString(BridgeQueryResponse.serializer(), body)
            if (!response.ok) {
                val error = response.error
                return@execute BridgeResult.Failure(
                    error?.code ?: BridgeErrorCode.INTERNAL_ERROR,
                    error?.message ?: "the Host refused the query without a reason",
                )
            }
            val data = response.data
                ?: return@execute BridgeResult.Failure(
                    BridgeErrorCode.INTERNAL_ERROR,
                    "$provider/$op answered ok with no data",
                )
            BridgeResult.Success(decode(data), response.warnings)
        }
    }

    /** Сбросить кэш manifest, чтобы согласовать заново после переподключения. */
    fun invalidate() {
        cachedManifest = null
    }

    /** Добавить маршрут к base; null — сам base не разбирается. */
    private fun urlFor(path: String): HttpUrl? {
        val base = origin ?: return null
        // У base может быть префикс пути (удалённый шлюз) — склеиваем по сегментам, а не строкой.
        return base.newBuilder()
            .encodedPath(path)
            .query(null)
            .fragment(null)
            .build()
    }

    private suspend fun <T> execute(
        request: Request,
        decode: (String) -> BridgeResult<T>,
    ): BridgeResult<T> = withContext(Dispatchers.IO) {
        var last: BridgeResult<T>? = null
        // Мост на loopback: сразу после перезапуска Host или смены порта бывает разовая ошибка — повтор
        // один раз и только для TRANSPORT_UNREACHABLE. Ошибки разбора, 404 и протокола не повторяются.
        for (attempt in 0..1) {
            val result = executeOnce(request, decode)
            last = result
            val retryable = result is BridgeResult.Failure &&
                result.code == BridgeErrorCode.TRANSPORT_UNREACHABLE
            if (!retryable || attempt == 1) return@withContext result
            delay(90)
        }
        last ?: BridgeResult.Failure(
            BridgeErrorCode.INTERNAL_ERROR,
            "bridge request produced no result",
        )
    }

    private suspend fun <T> executeOnce(
        request: Request,
        decode: (String) -> BridgeResult<T>,
    ): BridgeResult<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Не-2xx тоже несёт тело ошибки JSON; разобралось — код Host, иначе ошибка транспорта.
                    val decoded = runCatching { decode(body) }.getOrNull()
                    if (decoded is BridgeResult.Failure) return@use decoded
                    // 404 и «нет связи» — разное: Host отвечает, просто такого маршрута нет,
                    // то есть bridge-плагин не установлен. Раздельно — чтобы интерфейс дал понятный совет.
                    if (response.code == 404) {
                        return@use BridgeResult.Failure(
                            BridgeErrorCode.PROVIDER_UNKNOWN,
                            "the Host serves no bridge route (HTTP 404): the bridge plugin is not installed",
                        )
                    }
                    return@use BridgeResult.Failure(
                        BridgeErrorCode.TRANSPORT_UNREACHABLE,
                        "bridge answered HTTP ${response.code}",
                    )
                }
                runCatching { decode(body) }.getOrElse { error ->
                    BridgeResult.Failure(
                        BridgeErrorCode.TRANSPORT_DECODE_FAILED,
                        "cannot decode the bridge response: ${error.message}",
                    )
                }
            }
        } catch (error: IOException) {
            BridgeResult.Failure(
                BridgeErrorCode.TRANSPORT_UNREACHABLE,
                error.message ?: "the Host is unreachable",
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** OkHttp по умолчанию: короткие таймауты, мост отвечает по loopback. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Общий предел, чтобы зависший Host не держал корутину страницы.
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}

/** Итог вызова моста: успех с данными и warnings или ошибка со стабильным кодом. */
sealed class BridgeResult<out T> {
    /** Успех. `warnings` — нефатальные замечания Host, клиент показывает или записывает их. */
    data class Success<T>(
        val value: T,
        val warnings: List<String> = emptyList(),
    ) : BridgeResult<T>()

    /** Ошибка. `code` — стабильный код, по нему клиент выбирает запасной путь. */
    data class Failure(
        val code: String,
        val message: String,
    ) : BridgeResult<Nothing>()

    /** Значение при успехе, иначе null. */
    fun valueOrNull(): T? = (this as? Success)?.value

    /** Код при ошибке, иначе null. */
    fun errorCodeOrNull(): String? = (this as? Failure)?.code
}

/** Общая настройка JSON: незнакомые поля пропускаются, новые поля Host не ломают выпущенный клиент. */
object BridgeJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }
}

/** Сборка `args` без ручного `buildJsonObject` в местах вызова. */
object BridgeArgs {
    /** Без параметров. */
    fun none(): Map<String, JsonElement> = emptyMap()

    /** Один строковый параметр. */
    fun of(key: String, value: String): Map<String, JsonElement> =
        buildJsonObject { put(key, value) }

    /** Два строковых параметра. */
    fun of(key: String, value: String, key2: String, value2: String): Map<String, JsonElement> =
        buildJsonObject {
            put(key, value)
            put(key2, value2)
        }

    /** Один целый параметр. */
    fun ofInt(key: String, value: Int): Map<String, JsonElement> =
        buildJsonObject { put(key, value) }

    /** Один параметр-объект (например, props для `render-tree`). */
    fun ofObject(key: String, value: JsonObject): Map<String, JsonElement> =
        buildJsonObject { put(key, value) }

    /** Прочитать JsonElement как объект (для разборщиков). */
    fun objectOf(element: JsonElement): JsonObject = element.jsonObject
}
