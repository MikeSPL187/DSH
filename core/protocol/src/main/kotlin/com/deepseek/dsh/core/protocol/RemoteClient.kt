package com.deepseek.dsh.core.protocol

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Клиент Host: единичные вызовы — POST /api (JSON), потоки — WS /api/remote.mux
 * (open/item/end/cancel). Собран из клиентов по областям поверх одного [HostConnection].
 */
class RemoteClient private constructor(private val conn: HostConnection) :
    DshClientProtocol,
    HostRpcApi by conn,
    AccountApi by AccountClient(conn),
    PluginsApi by PluginsClient(conn),
    SettingsApi by SettingsClient(conn),
    TeamApi by TeamClient(conn),
    JobsApi by JobsClient(conn),
    TerminalApi by TerminalClient(conn),
    ChangesApi by ChangesClient(conn),
    HostEventsApi by HostEventsClient(conn),
    SessionsApi by SessionsClient(conn) {

    constructor(baseUrl: String, client: OkHttpClient, json: Json = Json { ignoreUnknownKeys = true }) :
        this(HostConnection(baseUrl.trimEnd('/'), client, json))

    /** Привязать адрес, на котором локальный Host реально слушает (порт выбирается при запуске). */
    fun updateBaseUrl(newBaseUrl: String) {
        conn.baseUrl = newBaseUrl.trimEnd('/')
    }

    /** Текущий адрес Host — для диагностики. */
    fun currentBaseUrl(): String = conn.baseUrl
}

/**
 * Сколько ждать ответа пользователя на approval/request; затем ответ `next`, и Host
 * завершает запрос как `unavailable` (fail closed — действие не выполняется).
 * Было 2 минуты: на телефоне этого мало, чтобы увидеть уведомление и разблокировать экран.
 */
const val ANSWER_TIMEOUT_MS = 600_000L

/** Сколько ждать ответа пользователя на вопрос агента (waterfall). */
const val QUESTION_TIMEOUT_MS = 600_000L

/** Сколько раз подряд переподключать оборванный session/follow. */
internal const val FOLLOW_MAX_RETRIES = 8

/** Пауза перед попыткой [attempt] (с 1): 1, 2, 4, 8, 16, 30, 30… секунд. */
internal fun followRetryDelayMs(attempt: Int): Long =
    (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
