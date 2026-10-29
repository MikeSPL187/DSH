package com.dsh.core.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** События Host, пересланные клиенту (approval/request и др.), и ответы на них. */
internal class HostEventsClient(private val conn: HostConnection) : HostEventsApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    /** Имена событий, пересылаемых Host (поток Gateway `$events`). */
    private val forwardedBus =
        kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)

    private val forwardedSockets = mutableListOf<okhttp3.WebSocket>()

    private val forwardedStarted = mutableSetOf<String>()

    private val forwardedScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Запросы waterfall от Host (approval/request и др.), ждущие ответа приложения. */
    private val waterfallBus =
        kotlinx.coroutines.flow.MutableSharedFlow<ForwardedWaterfall>(extraBufferCapacity = 32)

    private val answeredWaterfalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Пересланные события с параметрами (прогресс установки плагина и т. п.). */
    private val eventPayloadBus =
        kotlinx.coroutines.flow.MutableSharedFlow<ForwardedEvent>(extraBufferCapacity = 64)

    @Volatile
    internal var forwardedClientId: String? = null

    /** Пауза между повторами ответа на waterfall; тесты уменьшают. */
    internal var answerRetryDelayMs = 1_000L

    /**
     * Имена событий, пересылаемых Host (поток Gateway `$events`).
     *
     * Клиент слушает `llm/adapters-updated`,
     * `settings/document-updated`, обновления учётных данных — и обновляет каталог моделей;
     * здесь отдаются только имена, что обновлять — решает слой данных.
     */
    override fun forwardedHostEvents(hostId: String): Flow<String> =
        forwardedBus.onStart { ensureForwardedStream(hostId) }

    private fun ensureForwardedStream(hostId: String) {
        synchronized(forwardedStarted) {
            if (!forwardedStarted.add(hostId)) return
        }
        forwardedScope.launch {
            // Пауза растёт, пока Host недоступен (остановлен, авто-сон), чтобы не будить
            // радио/CPU каждые 2 секунды; после живого соединения снова 2 секунды.
            var failures = 0
            while (true) {
                val startedAt = System.currentTimeMillis()
                runCatching { pumpForwardedStream(hostId) }
                failures = if (System.currentTimeMillis() - startedAt > 10_000L) 0 else failures + 1
                kotlinx.coroutines.delay(followRetryDelayMs(failures.coerceAtLeast(1)).coerceAtLeast(2_000L))
            }
        }
    }

    /** Одно подключение к `$events`; возвращается по его завершению, переподключает [ensureForwardedStream]. */
    private suspend fun pumpForwardedStream(hostId: String) {
        if (baseUrl.isBlank()) return
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var clientId: String? = null
        val socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "\$events")
                        put("payload", buildJsonObject { put("args", buildJsonObject { }) })
                    }
                    webSocket.send(open.toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching {
                        val root = json.parseHostJson(text) as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        if (root["type"]?.toString()?.trim('"') != "item") return@runCatching
                        if (root["streamId"]?.toString()?.trim('"') != streamId) return@runCatching
                        val value = root["value"] as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        when (value["type"]?.toString()?.trim('"')) {
                            "ready" -> {
                                clientId = value["clientId"]?.toString()?.trim('"')
                                forwardedClientId = clientId
                            }
                            "emit" -> {
                                val name = value["event"]?.toString()?.trim('"')
                                    ?: return@runCatching
                                forwardedBus.tryEmit(name)
                                val args = value["args"]
                                    as? kotlinx.serialization.json.JsonArray
                                    ?: kotlinx.serialization.json.JsonArray(emptyList())
                                eventPayloadBus.tryEmit(ForwardedEvent(name, args))
                            }
                            "waterfall" -> {
                                val eventId = value["eventId"]?.toString()?.trim('"')
                                    ?: return@runCatching
                                val event = value["event"]?.toString()?.trim('"').orEmpty()
                                val request = value["request"]
                                    as? kotlinx.serialization.json.JsonObject
                                    ?: kotlinx.serialization.json.JsonObject(emptyMap())
                                val agentId = value["agentId"]?.toString()?.trim('"')
                                if (event == "approval/request" ||
                                    event == "user-questions/request"
                                ) {
                                    // Отдаём приложению; без ответа к сроку уходит next, и Host не зависает.
                                    waterfallBus.tryEmit(
                                        ForwardedWaterfall(
                                            hostId = hostId,
                                            event = event,
                                            eventId = eventId,
                                            agentId = agentId,
                                            request = request,
                                        ),
                                    )
                                    forwardedScope.launch {
                                        val timeout = if (event == "user-questions/request") {
                                            QUESTION_TIMEOUT_MS
                                        } else {
                                            ANSWER_TIMEOUT_MS
                                        }
                                        kotlinx.coroutines.delay(timeout)
                                        answerForwardedEvent(eventId, null)
                                    }
                                } else {
                                    // Остальные waterfall — сразу next: Host обработает их по умолчанию.
                                    answerForwardedEvent(eventId, null)
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    closed.complete(Unit)
                }

                // Host закрыл поток сам (перезапуск, обновление плагина): закрытие нужно подтвердить —
                // без ответа OkHttp не доводит рукопожатие до onClosed, и подписка не восстанавливалась (DSH-08).
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
            },
        )
        synchronized(forwardedSockets) { forwardedSockets.add(socket) }
        try {
            closed.await()
        } finally {
            synchronized(forwardedSockets) { forwardedSockets.remove(socket) }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    /** На waterfall, которые клиент не обрабатывает, — `next`: Host продолжит по умолчанию. */
    private fun answerForwardedEvent(eventId: String, valueJson: String?) {
        if (forwardedClientId == null) return
        if (!answeredWaterfalls.add(eventId)) return
        forwardedScope.launch {
            // H-2: у шлюза Host нет своего срока — недошедший ответ оставил бы ход агента висеть. Несколько попыток;
            // не дошло — событие снова можно ответить (Host доставит его заново после переподключения).
            val delivered = (0 until ANSWER_ATTEMPTS).any { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(answerRetryDelayMs * attempt)
                val clientId = forwardedClientId ?: return@any false
                runCatching { postForwardedAnswer(clientId, eventId, valueJson) }.getOrDefault(false)
            }
            if (!delivered) answeredWaterfalls.remove(eventId)
        }
    }

    private fun postForwardedAnswer(clientId: String, eventId: String, valueJson: String?): Boolean {
        val outcome = if (valueJson == null) {
            buildJsonObject { put("kind", "next") }
        } else {
            buildJsonObject {
                put("kind", "result")
                put("value", json.parseHostJson(valueJson))
            }
        }
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "\$events/result")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("clientId", clientId)
                    put("eventId", eventId)
                    put("outcome", outcome)
                })
            })
        }
        val request = okhttp3.Request.Builder()
            .url("$baseUrl/api/\$events/result")
            .post(payload.toString().toRequestBody(mediaTypeJson))
            .build()
        // Доставлено — только когда Host принял ответ (`ok: true`). HTTP 200 с `ok: false` (поток уже
        // переподключён, clientId устарел) — не доставка: событие снова можно ответить после переподключения.
        return client.newCall(request).execute().use { response ->
            response.isSuccessful && runCatching {
                val result = (json.parseHostJson(response.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonObject)
                    ?.get("result") as? kotlinx.serialization.json.JsonObject
                (result?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
            }.getOrDefault(false)
        }
    }

    /** Отправка ответа приложения на waterfall. */
    override fun forwardedWaterfalls(hostId: String): Flow<ForwardedWaterfall> = waterfallBus

    override fun forwardedEventPayloads(hostId: String): Flow<ForwardedEvent> = eventPayloadBus

    override fun answerWaterfall(hostId: String, eventId: String, valueJson: String?) =
        answerForwardedEvent(eventId, valueJson)
}

/** Попыток доставить ответ на waterfall (H-2). */
private const val ANSWER_ATTEMPTS = 3
