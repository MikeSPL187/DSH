package com.deepseek.dsh.core.protocol

import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.ComposerStats
import com.deepseek.dsh.core.model.MessageRole
import com.deepseek.dsh.core.model.SessionInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** Сессии: список, создание, модель и доступ, отправка, поток follow с историей, поиск. */
internal class SessionsClient(private val conn: HostConnection) : SessionsApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson
    private fun extractRpcError(body: String) = conn.extractRpcError(body)

    /** Первый `sessionId` в дереве JSON ответа. */
    private fun findFirstSessionId(element: kotlinx.serialization.json.JsonElement?): String? {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                (element["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.takeIf { it.isNotBlank() }?.let { return it }
                element.values.forEach { value ->
                    findFirstSessionId(value)?.let { return it }
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { value ->
                findFirstSessionId(value)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    override suspend fun listSessions(hostId: String): RemoteResult<List<SessionInfo>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("method", "session/list")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {})
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure(
                        "dsh/remote/error",
                        "Failed to list sessions, HTTP ${response.code}"
                    )
                }
                val bodyStr = response.body?.string() ?: "{}"
                try {
                    // Try parsing standard DSH response or fallback to direct array
                    val jsonElem = json.parseHostJson(bodyStr)
                    val itemsElem = if (jsonElem is kotlinx.serialization.json.JsonObject) {
                        jsonElem["result"]?.let { res ->
                            if (res is kotlinx.serialization.json.JsonObject) res["value"]?.let { v ->
                                if (v is kotlinx.serialization.json.JsonObject) v["items"] else v
                            } else null
                        } ?: jsonElem["items"] ?: jsonElem
                    } else jsonElem
                    val list = json.decodeFromJsonElement<List<SessionInfo>>(itemsElem)
                    RemoteResult.Success(list)
                } catch (_: Exception) {
                    RemoteResult.Failure(
                        "dsh/session/list-malformed",
                        "Failed to decode session list from DSH Host"
                    )
                }
            }
        } catch (e: IOException) {
            RemoteResult.Failure("dsh/net/timeout", e.message ?: "Network error listing sessions")
        }
    }

    override suspend fun createSession(
        hostId: String,
        title: String,
        workspacePath: String?,
        agentPreset: String?
    ): RemoteResult<SessionInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/create")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        if (workspacePath != null) put("cwd", workspacePath)
                        if (!agentPreset.isNullOrBlank()) put("agentPreset", agentPreset)
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/session/create")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure(
                        "dsh/session/create-failed",
                        com.deepseek.dsh.core.model.Tr.t("Host returned an error: HTTP ${response.code}", "Host вернул ошибку: HTTP ${response.code}")
                    )
                }
                val bodyStr = response.body?.string() ?: ""
                val value = runCatching {
                    (json.parseHostJson(bodyStr) as? kotlinx.serialization.json.JsonObject)
                        ?.get("result")?.let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("value") as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
                val createdId = value?.get("sessionId")?.toString()?.trim('"')
                val resolvedId = createdId?.takeIf { it.isNotBlank() }
                    ?: findFirstSessionId(runCatching { json.parseHostJson(bodyStr) }.getOrNull())
                if (resolvedId.isNullOrBlank()) {
                    return@withContext RemoteResult.Failure("dsh/session/create-malformed", "Host did not return sessionId")
                }
                RemoteResult.Success(
                    SessionInfo(
                        hostId = hostId,
                        sessionId = resolvedId,
                        title = title,
                        workspacePath = workspacePath,
                        status = "ready",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            RemoteResult.Failure(
                "dsh/session/create-error",
                e.message ?: com.deepseek.dsh.core.model.Tr.t("Cannot reach the Host; session was not created", "Нет связи с Host: сессия не создана")
            )
        }
    }

    override suspend fun selectModel(
        hostId: String,
        sessionId: String,
        modelId: String,
        provider: String?,
        reasoningEffort: String?
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/selectModel")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("sessionId", sessionId)
                        if (!provider.isNullOrBlank()) put("provider", provider)
                        put("model", modelId)
                        if (!reasoningEffort.isNullOrBlank()) {
                            put("reasoningEffort", reasoningEffort)
                        }
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/session/selectModel")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure(
                        "dsh/session/select-model",
                        extractRpcError(body) ?: "HTTP ${response.code}",
                    )
                }
                val root = runCatching { json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject }
                    .getOrNull()
                val result = root?.get("result") as? kotlinx.serialization.json.JsonObject
                if (result?.get("ok")?.toString() == "false") {
                    val error = result["error"] as? kotlinx.serialization.json.JsonObject
                    RemoteResult.Failure(
                        "dsh/session/select-model",
                        (error?.get("message")?.toString() ?: error?.toString()).orEmpty(),
                    )
                } else {
                    RemoteResult.Success(true)
                }
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/session/select-model", e.message ?: "network error")
        }
    }

    override suspend fun selectPermissionPreset(
        hostId: String,
        sessionId: String,
        preset: String
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/selectPermission")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("sessionId", sessionId)
                    put("preset", preset)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/session/selectPermission")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        // Права — граница безопасности: сбой сети или отказ Host — это отказ, а не «выбрано».
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure("dsh/session/select-permission", extractRpcError(body) ?: "HTTP ${response.code}")
                }
                val result = runCatching { json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject }
                    .getOrNull()?.get("result") as? kotlinx.serialization.json.JsonObject
                if (result?.get("ok")?.toString() == "false") {
                    RemoteResult.Failure("dsh/session/select-permission", extractRpcError(body) ?: "rejected by Host")
                } else {
                    RemoteResult.Success(true)
                }
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/session/select-permission", e.message ?: "network error")
        }
    }

    override suspend fun cancelSession(hostId: String, sessionId: String): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val payload = buildJsonObject {
                put("type", "client-request")
                put("rpcId", java.util.UUID.randomUUID().toString())
                put("method", "session/cancel")
                put("payload", buildJsonObject {
                    put("args", buildJsonObject { put("sessionId", sessionId) })
                })
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/session/cancel")
                .post(payload.toRequestBody(mediaTypeJson))
                .build()
            runCatching { client.newCall(request).execute().use(::hostAccepted) }.getOrDefault(false)
        }

    override suspend fun answerUserQuestion(
        hostId: String,
        sessionId: String,
        callId: String,
        questionId: String,
        selected: String
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "userQuestions/answer")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("callId", callId)
                    put("answer", buildJsonObject {
                        put("answers", kotlinx.serialization.json.JsonArray(listOf(
                            buildJsonObject {
                                put("id", questionId)
                                put("selected", kotlinx.serialization.json.JsonArray(listOf(
                                    kotlinx.serialization.json.JsonPrimitive(selected)
                                )))
                            }
                        )))
                    })
                })
            })
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/userQuestions/answer")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()
        runCatching {
            client.newCall(request).execute().use(::hostAccepted)
        }.getOrDefault(false)
    }

    /** HTTP 200 и Host не ответил `ok: false`: отказ Host — не успех (G-03, G-22). */
    private fun hostAccepted(response: okhttp3.Response): Boolean {
        if (!response.isSuccessful) return false
        val body = response.body?.string().orEmpty()
        val result = runCatching { (json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject)?.get("result") }
            .getOrNull() as? kotlinx.serialization.json.JsonObject
        return (result?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
    }

    override suspend fun getSessionStats(
        hostId: String,
        sessionId: String
    ): RemoteResult<ComposerStats> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/projections")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("sessionId", sessionId)
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/session/projections")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val jsonElem = json.parseHostJson(bodyStr)
                    val values = if (jsonElem is kotlinx.serialization.json.JsonObject) {
                        jsonElem["result"]?.let { res ->
                            if (res is kotlinx.serialization.json.JsonObject) {
                                res["value"]?.let { v ->
                                    if (v is kotlinx.serialization.json.JsonObject) v["values"] else null
                                }
                            } else null
                        } ?: jsonElem["values"]
                    } else null

                    if (values is kotlinx.serialization.json.JsonObject) {
                        val statsObj = values["sessionStats"] as? kotlinx.serialization.json.JsonObject
                        if (statsObj != null) {
                            val decodeMs = statsObj["decodeMs"]?.let { runCatching { it.toString().toDouble() }.getOrNull() } ?: 0.0
                            val decodeTokens = statsObj["decodeTokens"]?.let { runCatching { it.toString().toDouble() }.getOrNull() } ?: 0.0
                            val speed = if (decodeMs > 0.0) {
                                Math.round((decodeTokens / (decodeMs / 1000.0)) * 10.0) / 10.0
                            } else 0.0

                            val tokenUsage = values["tokenUsage"] as? kotlinx.serialization.json.JsonObject
                            val uncached = tokenUsage?.get("uncachedInputTokens")?.let { runCatching { it.toString().toDouble() }.getOrNull() } ?: 0.0
                            val cacheRead = tokenUsage?.get("cacheReadTokens")?.let { runCatching { it.toString().toDouble() }.getOrNull() } ?: 0.0
                            val cacheWrite = tokenUsage?.get("cacheWriteTokens")?.let { runCatching { it.toString().toDouble() }.getOrNull() } ?: 0.0
                            val totalInput = uncached + cacheRead + cacheWrite
                            val cacheHit = if (totalInput > 0.0) {
                                Math.round((cacheRead / totalInput) * 100.0).toInt().coerceIn(0, 100)
                            } else 0

                            val contextPercent = if (totalInput > 0.0) {
                                Math.min(100, Math.max(1, Math.round((totalInput / 64000.0) * 100.0).toInt()))
                            } else 0

                            return@withContext RemoteResult.Success(
                                ComposerStats(
                                    outputSpeedTokensPerSec = speed,
                                    contextPercent = contextPercent,
                                    cacheHitPercent = cacheHit
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        RemoteResult.Failure(
            "dsh/session/stats-unavailable",
            "Session stats are unavailable from DSH Host"
        )
    }

    override suspend fun sendMessage(
        hostId: String,
        sessionId: String,
        text: String,
        images: List<PromptImagePart>,
    ): RemoteResult<ChatMessage> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val requestId = java.util.UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/prompt")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("requestId", requestId)
                        put("sessionId", sessionId)
                        put("mode", "queue")
                        put("content", buildJsonArray {
                            if (text.isNotBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", text) })
                            }
                            images.forEach { image ->
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("mediaType", image.mediaType)
                                    put("data", image.base64)
                                    image.name?.let { put("name", it) }
                                })
                            }
                        })
                    })
                })
            })
        }.toString()

        // Сначала подписка на поток сессии, затем prompt: Host ведёт ход, только пока есть подписчик follow.
        startSessionFollow(sessionId, hostId)
        val request = Request.Builder()
            .url("$baseUrl/api/session/prompt")
            .post(payload.toRequestBody(mediaTypeJson))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext RemoteResult.Failure(
                        "dsh/remote/error",
                        "Send message failed, HTTP ${response.code}"
                    )
                }
                val envelope = runCatching {
                    json.parseHostJson(body) as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
                val result = envelope?.get("result") as? kotlinx.serialization.json.JsonObject
                if (result != null && result["ok"]?.toString() == "false") {
                    val error = result["error"] as? kotlinx.serialization.json.JsonObject
                    val code = (error?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: "dsh/remote/error"
                    val message = (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: "Host rejected the prompt"
                    return@withContext RemoteResult.Failure(code, message)
                }
                val msg = ChatMessage(
                    hostId = hostId,
                    sessionId = sessionId,
                    messageId = "msg_${System.currentTimeMillis()}",
                    role = MessageRole.USER,
                    content = text,
                    createdAt = System.currentTimeMillis()
                )
                RemoteResult.Success(msg)
            }
        } catch (e: IOException) {
            RemoteResult.Failure("dsh/net/timeout", e.message ?: "Network error sending message")
        }
    }

    /** Последние сырые кадры каждой сессии (диагностика), не больше 500. */
    /** Состояние запусков workflow: sessionId -> runId -> снимок. */
    private val workflowRuns =
        java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String,
                com.deepseek.dsh.core.model.WorkflowRunInfo>>()

    private val rawFrames =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<SessionRawFrame>>()

    private val rawFrameCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    override fun rawSessionFrames(sessionId: String): List<SessionRawFrame> =
        rawFrames[sessionId]?.toList() ?: emptyList()

    /** Записать сырой кадр; не больше 500 на сессию, чтобы не копить память. */
    private fun recordRawFrame(sessionId: String, text: String) {
        val counter = rawFrameCounters.getOrPut(sessionId) {
            java.util.concurrent.atomic.AtomicInteger(0)
        }
        val index = counter.incrementAndGet()
        val type = runCatching {
            val root = json.parseHostJson(text) as? kotlinx.serialization.json.JsonObject
            (root?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "frame"
        }.getOrDefault("frame")
        val queue = rawFrames.getOrPut(sessionId) {
            java.util.concurrent.ConcurrentLinkedDeque()
        }
        queue.addLast(SessionRawFrame(index, type, text, System.currentTimeMillis()))
        while (queue.size > 500) queue.pollFirst()
    }

    /** Номер попытки переподключения follow по сессии; сбрасывается после успешного open. */
    private val followRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val followReconnectExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsh-follow-reconnect").apply { isDaemon = true }
        }

    /** Одно подключение follow на сессию: открытие и отправка подписываются оба, дубль удвоил бы delta. */
    private val followSocketsBySession =
        java.util.concurrent.ConcurrentHashMap<String, okhttp3.WebSocket>()

    private val eventBus = kotlinx.coroutines.flow.MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 128)

    /** Курсор истории из снимка follow: sessionId -> самый ранний загруженный seq. */
    private val historyCursors =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Есть ли у сессии более ранняя история. */
    private val historyHasMore =
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val toolNames = mutableMapOf<String, String>()

    private val toolArgs = mutableMapOf<String, String>()

    /** Подписка session/follow: Host выполняет prompt из очереди, приходят кадры инструментов и сообщений. */
    private fun startSessionFollow(sessionId: String, hostId: String) {
        if (followSocketsBySession[sessionId] != null) return
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val request = okhttp3.Request.Builder().url(wsUrl).build()
        val socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                followRetries.remove(sessionId)
                val streamId = java.util.UUID.randomUUID().toString()
                val open = buildJsonObject {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", "session/follow")
                    put("payload", buildJsonObject {
                        put("args", buildJsonObject {
                            put("request", buildJsonObject {
                                put("address", buildJsonObject {
                                    put("kind", "session")
                                    put("sessionId", sessionId)
                                })
                                put("assistantStream", true)
                            })
                        })
                    })
                }
                webSocket.send(open.toString())
            }
            override fun onMessage(
                webSocket: okhttp3.WebSocket, text: String) {
                recordRawFrame(sessionId, text)
                try {
                    val root = json.parseHostJson(text) as? kotlinx.serialization.json.JsonObject ?: return
                    if (root["type"]?.toString()?.trim('"') != "item") return
                    val value = root["value"] as? kotlinx.serialization.json.JsonObject ?: return

                    when (value["type"]?.toString()?.trim('"')) {
                        // Первый кадр follow — окно истории: проигрываем его, а при hasMore
                        // догружаем более раннюю через session/page.
                        "snapshot" -> {
                            val records = value["records"]
                                as? kotlinx.serialization.json.JsonArray
                            var minSeq = 0L
                            // Ответ ассистента, который шёл в момент переподключения: собираем текст из baseline,
                            // дальше дописываются живые фрагменты.
                            val baselineText = StringBuilder()
                            val baselineReasoning = StringBuilder()
                            run {
                                val active = (value["assistantStream"]
                                    as? kotlinx.serialization.json.JsonObject)
                                    ?.get("activeAttempt")
                                    as? kotlinx.serialization.json.JsonObject
                                val stream = active?.get("stream")
                                    as? kotlinx.serialization.json.JsonArray
                                stream?.forEach { timed ->
                                    val chunk = (timed as? kotlinx.serialization.json.JsonObject)
                                        ?.get("chunk")
                                        as? kotlinx.serialization.json.JsonObject
                                        ?: return@forEach
                                    val text = (chunk["text"]
                                        as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.content.orEmpty()
                                    when (chunk["type"]?.toString()?.trim('"')) {
                                        "text-delta" -> baselineText.append(text)
                                        "reasoning-delta" -> baselineReasoning.append(text)
                                    }
                                }
                            }
                            if (baselineReasoning.isNotBlank()) {
                                eventBus.tryEmit(
                                    RemoteEvent.ReasoningDelta(
                                        sessionId,
                                        baselineReasoning.toString(),
                                    ),
                                )
                            }
                            if (baselineText.isNotBlank()) {
                                eventBus.tryEmit(
                                    RemoteEvent.MessageDelta(sessionId, baselineText.toString()),
                                )
                            }
                            records?.forEach { record ->
                                val entry = record as? kotlinx.serialization.json.JsonObject
                                    ?: return@forEach
                                if (entry["type"]?.toString()?.trim('"') != "event") {
                                    return@forEach
                                }
                                val event = entry["event"]
                                    as? kotlinx.serialization.json.JsonObject ?: return@forEach
                                val seq = event["seq"]?.toString()?.toLongOrNull() ?: 0L
                                if (seq > 0 && (minSeq == 0L || seq < minSeq)) minSeq = seq
                                handleDurableEvent(sessionId, hostId, event)
                            }
                            val hasMore = value["hasMore"]?.toString() == "true"
                            val cursor = value["cursor"]?.toString()?.toLongOrNull()
                                ?: minSeq
                            if (cursor > 0) historyCursors[sessionId] = cursor
                            historyHasMore[sessionId] = hasMore
                        }
                        "event" -> {
                            val event = value["event"]
                                as? kotlinx.serialization.json.JsonObject ?: return
                            handleDurableEvent(sessionId, hostId, event)
                        }
                        // Фрагмент текста ассистента: показываем сразу, не дожидаясь конца хода.
                        "assistant-stream" -> {
                            val frame = value["frame"]
                                as? kotlinx.serialization.json.JsonObject ?: return
                            if (frame["type"]?.toString()?.trim('"') != "chunk") return
                            val chunk = frame["chunk"]
                                as? kotlinx.serialization.json.JsonObject ?: return
                            val text = (chunk["text"]
                                as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (text.isNullOrEmpty()) return
                            when (chunk["type"]?.toString()?.trim('"')) {
                                "text-delta" -> eventBus.tryEmit(
                                    RemoteEvent.MessageDelta(sessionId, text),
                                )
                                "reasoning-delta" -> eventBus.tryEmit(
                                    RemoteEvent.ReasoningDelta(sessionId, text),
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                // remove(key, value): колбэк старого сокета не должен снять запись нового,
                // иначе следующая подписка откроет второй follow и дельты удвоятся.
                followSocketsBySession.remove(sessionId, webSocket)
                // Обрыв (смена сети, Doze, перезапуск Host) раньше тихо прекращал поток ответа
                // до повторного открытия сессии. Переподключаемся с нарастающей паузой.
                scheduleFollowReconnect(sessionId, hostId, webSocket)
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                followSocketsBySession.remove(sessionId, webSocket)
                // Сам клиент follow-сокеты не закрывает: закрытие пришло от Host
                // (перезапуск, обновление плагина) — подписку нужно восстановить.
                scheduleFollowReconnect(sessionId, hostId, webSocket)
            }
        })
        followSocketsBySession[sessionId] = socket
    }

    private fun scheduleFollowReconnect(sessionId: String, hostId: String, failed: okhttp3.WebSocket) {
        val attempt = followRetries.merge(sessionId, 1, Int::plus) ?: 1
        if (attempt > FOLLOW_MAX_RETRIES) {
            // Дальше подписку восстановит следующее открытие сессии или отправка.
            followRetries.remove(sessionId)
            return
        }
        val delayMs = followRetryDelayMs(attempt)
        followReconnectExecutor.schedule({
            // Быстрый обрыв может прийти раньше, чем сокет записан в таблицу: тогда там
            // лежит уже мёртвый сокет. Снимаем именно его и переподключаемся.
            followSocketsBySession.remove(sessionId, failed)
            if (followSocketsBySession[sessionId] == null && baseUrl.isNotBlank()) {
                runCatching { startSessionFollow(sessionId, hostId) }
            }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Превратить durable-событие в ChatMessage и отдать слою данных. */
    private fun emitDurableMessage(
        sessionId: String,
        hostId: String,
        seq: Long,
        messageId: String,
        role: MessageRole,
        content: String,
    ) {
        if (content.isBlank()) return
        eventBus.tryEmit(
            RemoteEvent.MessageDone(
                sessionId = sessionId,
                message = ChatMessage(
                    hostId = hostId,
                    sessionId = sessionId,
                    messageId = messageId,
                    role = role,
                    content = content,
                    seq = seq,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        )
    }

    /** Собрать события tool-workflow и отдать UI (каждое несёт полный снимок). */
    private fun applyWorkflowEvent(
        sessionId: String,
        eventType: String,
        data: kotlinx.serialization.json.JsonObject,
    ) {
        fun field(key: String): String? =
            (data[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val runId = field("runId") ?: return
        val runs = workflowRuns.getOrPut(sessionId) {
            java.util.concurrent.ConcurrentHashMap()
        }
        val current = runs[runId] ?: com.deepseek.dsh.core.model.WorkflowRunInfo(runId = runId)

        // Участники по фазам; отсутствующая фаза и пустая строка — разные фазы.
        fun updateMembers(transform: (MutableList<com.deepseek.dsh.core.model.WorkflowPhaseInfo>)
            -> Unit): List<com.deepseek.dsh.core.model.WorkflowPhaseInfo> {
            val phases = current.phases.map { phase ->
                com.deepseek.dsh.core.model.WorkflowPhaseInfo(
                    phase = phase.phase,
                    members = phase.members.toMutableList(),
                )
            }.toMutableList()
            transform(phases)
            return phases
        }

        val next = when (eventType) {
            "tool-workflow/run-start" -> current.copy(
                name = field("name") ?: current.name,
                status = "running",
            )
            "tool-workflow/agent-start" -> {
                val phase = field("phase")
                val member = com.deepseek.dsh.core.model.WorkflowMemberInfo(
                    seq = field("seq")?.toIntOrNull() ?: 0,
                    label = field("label").orEmpty(),
                    childId = field("childId").orEmpty(),
                    status = "running",
                )
                val phases = updateMembers { list ->
                    val existing = list.firstOrNull { it.phase == phase }
                    if (existing != null) {
                        list[list.indexOf(existing)] = existing.copy(
                            members = existing.members + member,
                        )
                    } else {
                        list += com.deepseek.dsh.core.model.WorkflowPhaseInfo(
                            phase = phase,
                            members = listOf(member),
                        )
                    }
                }
                current.copy(status = "running", phases = phases)
            }
            "tool-workflow/agent-end" -> {
                val seq = field("seq")?.toIntOrNull()
                val outcome = field("outcome") ?: "completed"
                val phases = updateMembers { list ->
                    list.forEachIndexed { index, phase ->
                        if (phase.members.any { it.seq == seq }) {
                            list[index] = phase.copy(
                                members = phase.members.map { member ->
                                    if (member.seq == seq) member.copy(status = outcome) else member
                                },
                            )
                        }
                    }
                }
                current.copy(phases = phases)
            }
            else -> current.copy(status = field("stopReason").orEmpty().let { reason ->
                when (reason) {
                    "completed" -> "completed"
                    "cancelled" -> "cancelled"
                    "error" -> "failed"
                    else -> reason.ifBlank { "completed" }
                }
            })
        }
        runs[runId] = next
        eventBus.tryEmit(RemoteEvent.WorkflowRunUpdated(sessionId, next))
    }

    /** Обработка durable-событий — общая для снимка и живых событий. */
    private fun handleDurableEvent(
        sessionId: String,
        hostId: String,
        event: kotlinx.serialization.json.JsonObject,
    ) {
        val eventType = event["type"]?.toString()?.trim('"') ?: return
        val data = event["data"] as? kotlinx.serialization.json.JsonObject ?: return
        val seq = event["seq"]?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
        when (eventType) {
            "tool-workflow/run-start", "tool-workflow/agent-start",
            "tool-workflow/agent-end", "tool-workflow/run-end" -> {
                applyWorkflowEvent(sessionId, eventType, data)
            }
            "user/message" -> {
                // Host пишет служебные вставки («Current runtime context», «Time sampled …»)
                // как user/message; это контекст модели, а не ввод пользователя — в чате не показываем.
                val sourceKind = ((data["source"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("kind") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (sourceKind != null && sourceKind != "user") return
                val message = data["message"] as? kotlinx.serialization.json.JsonObject
                val blocks = (message?.get("content") ?: data["content"])
                    as? kotlinx.serialization.json.JsonArray
                val body = blocks?.mapNotNull { part ->
                    val obj = part as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    if (obj["type"]?.toString()?.trim('"') == "text") {
                        obj["text"]?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                    } else {
                        null
                    }
                }?.joinToString("").orEmpty()
                val userMessageId = "user_$seq"
                emitDurableMessage(sessionId, hostId, seq, userMessageId, MessageRole.USER, body)
                // Картинки в user/message: запоминаем ссылки, в пузыре — миниатюры.
                val imageRefs = blocks?.mapNotNull { part ->
                    val obj = part as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    if (obj["type"]?.toString()?.trim('"') != "image") return@mapNotNull null
                    val attachment = obj["attachment"] as? kotlinx.serialization.json.JsonObject
                    val id = (attachment?.get("attachmentId")
                        as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    AttachmentImageRef(
                        attachmentId = id,
                        mediaType = (attachment["mediaType"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty(),
                        width = (attachment["width"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?.toIntOrNull() ?: 0,
                        height = (attachment["height"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?.toIntOrNull() ?: 0,
                        name = (attachment["name"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                    )
                }.orEmpty()
                if (imageRefs.isNotEmpty()) {
                    eventBus.tryEmit(RemoteEvent.UserImages(sessionId, userMessageId, imageRefs))
                }
            }
            // Результаты инструмента present: список файлов — в UI.
            "deliverables/presented" -> {
                val files = (data["files"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { item ->
                        val obj = item as? kotlinx.serialization.json.JsonObject
                            ?: return@mapNotNull null
                        val path = (obj["path"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull ?: return@mapNotNull null
                        PresentedFileInfo(
                            path = path,
                            description = (obj["description"]
                                as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                        )
                    } ?: emptyList()
                if (files.isNotEmpty()) {
                    eventBus.tryEmit(
                        RemoteEvent.DeliverablesPresented(sessionId, files),
                    )
                }
            }
            // workspace/changes: изменения хода записаны, UI загрузит сводку по seq.
            "workspace/changes" -> {
                val turn = data["turn"]?.toString()?.toIntOrNull() ?: return
                eventBus.tryEmit(RemoteEvent.ChangesAnnounced(sessionId, seq, turn))
            }
            "turn/start" -> eventBus.tryEmit(RemoteEvent.SessionRunning(sessionId, true))
            "turn/end" -> {
                eventBus.tryEmit(RemoteEvent.SessionRunning(sessionId, false))
                // При сбое модели или сети Host шлёт только turn/end.reason.error без ответа;
                // чтобы не было пустого пузыря, текст ошибки становится сообщением ассистента.
                val reason = data["reason"] as? kotlinx.serialization.json.JsonObject
                val error = reason?.get("error") as? kotlinx.serialization.json.JsonObject
                val message = (error?.get("message")
                    as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val code = (error?.get("code")
                    as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (!message.isNullOrBlank()) {
                    // Полная ошибка: весь message + code (без обрезки), построчно в пузыре.
                    val full = buildString {
                        append("⚠️ ")
                        append(message)
                        if (!code.isNullOrBlank()) append("\n\n" + com.deepseek.dsh.core.model.Tr.t("Error code: ", "Код ошибки: ")).append(code)
                    }
                    emitDurableMessage(
                        sessionId = sessionId,
                        hostId = hostId,
                        seq = seq,
                        messageId = "error_turn_$seq",
                        role = MessageRole.ASSISTANT,
                        content = full,
                    )
                }
            }
            "assistant/message" -> {
                val message = data["message"] as? kotlinx.serialization.json.JsonObject ?: return
                val blocks = message["content"] as? kotlinx.serialization.json.JsonArray
                val reasoning = blocks?.mapNotNull { part ->
                    val obj = part as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    if (obj["type"]?.toString()?.trim('"') == "reasoning") {
                        obj["text"]?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                    } else {
                        null
                    }
                }?.joinToString("").orEmpty()
                val answer = blocks?.mapNotNull { part ->
                    val obj = part as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    if (obj["type"]?.toString()?.trim('"') == "text") {
                        obj["text"]?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                    } else {
                        null
                    }
                }?.joinToString("").orEmpty()
                val content = when {
                    reasoning.isBlank() -> answer
                    answer.isBlank() -> reasoning + "\u0001"
                    else -> reasoning + "\u0001" + answer
                }
                // messageFeedback привязан к MessageId ответа ассистента;
                // берём настоящий id из события, иначе seq.
                val assistantId = (message["id"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull ?: "assistant_$seq"
                emitDurableMessage(sessionId, hostId, seq, assistantId, MessageRole.ASSISTANT, content)
            }
            "tool/call" -> {
                val callId = data["callId"]?.toString()?.trim('"')
                    ?: data["callID"]?.toString()?.trim('"') ?: return
                val name = data["name"]?.toString()?.trim('"') ?: "tool"
                val args = (data["arguments"]
                    as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: data["arguments"]?.toString()?.trim('"') ?: ""
                toolNames[callId] = name
                toolArgs[callId] = args
                emitDurableMessage(
                    sessionId, hostId, seq, "tool_$callId", MessageRole.TOOL,
                    "\u0002TOOL\u0002$callId\u0002$name\u0002running\u0002$args\u0002$args",
                )
            }
            "tool/result" -> {
                val message = data["message"] as? kotlinx.serialization.json.JsonObject
                val callId = message?.get("toolCallId")?.toString()?.trim('"')
                    ?: data["callId"]?.toString()?.trim('"') ?: return
                val name = toolNames[callId] ?: "tool"
                val blocks = message?.get("content") as? kotlinx.serialization.json.JsonArray
                val body = blocks?.mapNotNull { part ->
                    val obj = part as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    if (obj["type"]?.toString()?.trim('"') == "text") {
                        obj["text"]?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                    } else {
                        null
                    }
                }?.joinToString("\n").orEmpty()
                val failed = data["error"] != null ||
                    message?.get("isError")?.toString() == "true"
                emitDurableMessage(
                    sessionId, hostId, seq, "tool_$callId", MessageRole.TOOL,
                    "\u0002TOOL\u0002$callId\u0002$name\u0002" +
                        "${if (failed) "error" else "ok"}\u0002$body\u0002" +
                        "${toolArgs.remove(callId) ?: ""}",
                )
            }
        }
    }

    private data class HistoryPage(
        val records: List<kotlinx.serialization.json.JsonObject>,
        val hasMore: Boolean,
        val minSeq: Long,
    )

    /** Догрузить страницу более ранней истории (UI вызывает при прокрутке к началу). */
    override suspend fun loadOlderHistory(
        hostId: String,
        sessionId: String,
    ): RemoteResult<Boolean> {
        if (historyHasMore[sessionId] != true) return RemoteResult.Success(false)
        val through = historyCursors[sessionId] ?: return RemoteResult.Success(false)
        val page = fetchSessionPage(sessionId, through, through)
            ?: return RemoteResult.Success(false)
        if (page.records.isEmpty()) {
            historyHasMore[sessionId] = false
            return RemoteResult.Success(false)
        }
        page.records.forEach { entry ->
            if (entry["type"]?.toString()?.trim('"') != "event") return@forEach
            val event = entry["event"] as? kotlinx.serialization.json.JsonObject
                ?: return@forEach
            handleDurableEvent(sessionId, hostId, event)
        }
        if (page.minSeq > 1L) historyCursors[sessionId] = page.minSeq
        val more = page.hasMore && page.minSeq > 1L
        historyHasMore[sessionId] = more
        return RemoteResult.Success(more)
    }

    /** Запрос страницы ранней истории (`session/page`). */
    private suspend fun fetchSessionPage(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
    ): HistoryPage? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/page")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("address", buildJsonObject {
                            put("kind", "session")
                            put("sessionId", sessionId)
                        })
                        put("throughSeq", throughSeq)
                        put("beforeSeq", beforeSeq)
                        put("maxMessages", 60)
                    })
                })
            })
        }
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/session/page")
                .post(payload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = json.parseHostJson(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject ?: return@use null
                val value = ((root["result"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("value")) as? kotlinx.serialization.json.JsonObject
                    ?: return@use null
                val records = (value["records"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    ?: emptyList()
                val hasMore = value["hasMore"]?.toString() == "true"
                val minSeq = records.mapNotNull { record ->
                    ((record["event"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("seq"))?.toString()?.toLongOrNull()
                }.minOrNull() ?: 0L
                HistoryPage(records, hasMore, minSeq)
            }
        }.getOrNull()
    }

    /**
     * Подписка `workspace/follow` (базовое дерево рабочих папок и сессий + изменения).
     *
     * Кадры уходят слою данных сырым JSON, что обновлять — решает он.
     */
    override fun workspaceFollow(hostId: String): Flow<String> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        val socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "workspace/follow")
                        put("payload", buildJsonObject { put("args", buildJsonObject { }) })
                    }
                    webSocket.send(open.toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching {
                        val frame = json.parseHostJson(text)
                            as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        val type = (frame["type"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content
                        val id = (frame["streamId"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (id != streamId) return@runCatching
                        when (type) {
                            "item" -> {
                                val value = frame["value"]
                                if (value != null) trySend(value.toString())
                            }
                            "error", "end" -> close()
                        }
                    }
                }

                override fun onFailure(
                    webSocket: okhttp3.WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    close(t)
                }

                override fun onClosed(
                    webSocket: okhttp3.WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    close()
                }
            },
        )
        awaitClose {
            runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    /** `session/search`: поиск по содержимому — сессии и фрагменты. */
    override suspend fun searchSessions(
        hostId: String,
        query: String,
    ): RemoteResult<List<SessionSearchHit>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "session/search")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject { put("query", query) })
                })
            })
        }
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/session/search")
                .post(payload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure(
                        "dsh/search-failed",
                        "HTTP ${response.code}",
                    )
                }
                val root = json.parseHostJson(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject
                    ?: return@use RemoteResult.Success(emptyList())
                val value = ((root["result"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("value")) as? kotlinx.serialization.json.JsonObject
                val items = value?.get("items")
                    as? kotlinx.serialization.json.JsonArray
                    ?: return@use RemoteResult.Success(emptyList())
                val hits = items.mapNotNull { item ->
                    val obj = item as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    val sessionId = (obj["sessionId"]
                        as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: return@mapNotNull null
                    val snippet = (obj["snippet"]
                        as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    SessionSearchHit(sessionId, snippet)
                }
                RemoteResult.Success(hits)
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/search-network", e.message ?: "search failed")
        }
    }

    /** `session/control`: базовые проекции сессий + изменения. */
    override fun sessionControl(hostId: String): Flow<String> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        val socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "session/control")
                        put("payload", buildJsonObject { put("args", buildJsonObject { }) })
                    }
                    webSocket.send(open.toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching {
                        val frame = json.parseHostJson(text)
                            as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        val id = (frame["streamId"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (id != streamId) return@runCatching
                        when ((frame["type"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                            "item" -> frame["value"]?.let { trySend(it.toString()) }
                            "error", "end" -> close()
                        }
                    }
                }

                override fun onFailure(
                    webSocket: okhttp3.WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    close(t)
                }

                override fun onClosed(
                    webSocket: okhttp3.WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    close()
                }
            },
        )
        awaitClose {
            runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    /**
     * Подписка на полосу модов Claude Code.
     *
     * Через потоковый WebSocket Gateway (`/api/remote.mux`): сначала `open`
     * `claudeCodeMods/watchBand`, затем каждый кадр `item` — новый
     * `SurfaceSnapshot`; `end`/`error` завершают подписку.
     */
    override fun watchClaudeCodeBand(hostId: String, sessionId: String): Flow<JsonElement?> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val request = Request.Builder().url(wsUrl).build()
        val streamId = java.util.UUID.randomUUID().toString()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val open = buildJsonObject {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", "claudeCodeMods/watchBand")
                    put("payload", buildJsonObject {
                        put("args", buildJsonObject { put("agentId", sessionId) })
                    })
                }
                webSocket.send(open.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val frame = json.parseHostJson(text) as? kotlinx.serialization.json.JsonObject
                        ?: return@runCatching
                    fun field(key: String): String? =
                        (frame[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    if (field("streamId") != streamId) return@runCatching
                    when (field("type")) {
                        "item" -> trySend(frame["value"])
                        "error", "end" -> {
                            trySend(null)
                            close()
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        })
        awaitClose {
            runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    override fun followSession(hostId: String, sessionId: String) {
        startSessionFollow(sessionId, hostId)
    }

    override fun subscribeEvents(hostId: String): Flow<RemoteEvent> = eventBus
}
