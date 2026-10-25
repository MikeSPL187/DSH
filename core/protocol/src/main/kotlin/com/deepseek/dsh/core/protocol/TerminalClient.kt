package com.deepseek.dsh.core.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Терминалы Host (`terminal/...`): окружение, открытие PTY, закрытие. */
internal class TerminalClient(private val conn: HostConnection) : TerminalApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    /** `terminal/environment`. */
    override suspend fun terminalEnvironment(
        hostId: String,
        sessionId: String,
    ): RemoteResult<TerminalEnvironmentInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "terminal/environment")
            put("payload", buildJsonObject { put("args", buildJsonObject { put("agentId", sessionId) }) })
        }
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/terminal/environment")
                .post(payload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure("dsh/terminal-env", "HTTP ${response.code}")
                }
                val root = json.parseToJsonElement(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject
                val value = (root?.get("result") as? kotlinx.serialization.json.JsonObject)
                    ?.get("value") as? kotlinx.serialization.json.JsonObject
                    ?: return@use RemoteResult.Failure("dsh/terminal-env", "missing value")
                RemoteResult.Success(
                    TerminalEnvironmentInfo(
                        cwd = (value["cwd"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull.orEmpty(),
                        maxCols = value["maxCols"]?.toString()?.trim('"')?.toIntOrNull() ?: 160,
                        maxRows = value["maxRows"]?.toString()?.trim('"')?.toIntOrNull() ?: 48,
                    ),
                )
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/terminal-env-network", e.message ?: "terminal env failed")
        }
    }

    /** Открыть (или переиспользовать) терминал и подключиться. */
    override suspend fun openTerminal(
        hostId: String,
        sessionId: String,
        cols: Int,
        rows: Int,
        terminalId: String,
    ): RemoteResult<TerminalConnection> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        // create для уже открытого id ничего не меняет — вызываем всегда.
        val createPayload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "terminal/create")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("request", buildJsonObject {
                        put("id", terminalId)
                        put("cols", cols)
                        put("rows", rows)
                    })
                })
            })
        }
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/terminal/create")
                .post(createPayload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure("dsh/terminal-create", "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            return@withContext RemoteResult.Failure(
                "dsh/terminal-create-network",
                e.message ?: "terminal create failed",
            )
        }
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        // replay: снимок терминала приходит сразу после подключения и не должен теряться
        // до начала collect, иначе экран навсегда застрянет на «Connecting…».
        val frames = kotlinx.coroutines.flow.MutableSharedFlow<String>(
            replay = 16,
            extraBufferCapacity = 240,
        )
        val sequence = java.util.concurrent.atomic.AtomicInteger(1)
        lateinit var socket: okhttp3.WebSocket
        var closed = false
        val handle = object : TerminalConnection {
            override val frames: Flow<String> = frames
            override fun write(data: String) {
                if (closed) return
                val seq = sequence.getAndIncrement()
                val item = buildJsonObject {
                    put("type", "item")
                    put("streamId", streamId)
                    put("value", buildJsonObject {
                        put("type", "write")
                        put("sequence", seq)
                        put("data", data)
                    })
                }
                runCatching { socket.send(item.toString()) }
            }

            override fun resize(cols: Int, rows: Int) {
                if (closed) return
                val seq = sequence.getAndIncrement()
                val item = buildJsonObject {
                    put("type", "item")
                    put("streamId", streamId)
                    put("value", buildJsonObject {
                        put("type", "resize")
                        put("sequence", seq)
                        put("cols", cols)
                        put("rows", rows)
                    })
                }
                runCatching { socket.send(item.toString()) }
            }

            override fun close() {
                closed = true
                runCatching { socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}") }
                runCatching { socket.close(1000, "client closed") }
            }
        }
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "terminal/attach")
                        put("payload", buildJsonObject {
                            put("args", buildJsonObject {
                                put("agentId", sessionId)
                                put("id", terminalId)
                            })
                        })
                    }
                    webSocket.send(open.toString())
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching {
                        val frame = json.parseToJsonElement(text)
                            as? kotlinx.serialization.json.JsonObject
                            ?: return@runCatching
                        val id = (frame["streamId"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (id != streamId) return@runCatching
                        when ((frame["type"]
                            as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                            "item" -> frame["value"]?.let { frames.tryEmit(it.toString()) }
                            "error" -> {
                                frames.tryEmit(
                                    "{\"type\":\"error\",\"message\":"
                                        + (frame["error"]?.toString() ?: "\"unknown\"") + "}",
                                )
                                frames.tryEmit("{\"type\":\"closed\"}")
                            }
                            "end" -> frames.tryEmit("{\"type\":\"closed\"}")
                        }
                    }
                }

                override fun onFailure(
                    webSocket: okhttp3.WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    frames.tryEmit(
                        "{\"type\":\"error\",\"message\":"
                            + kotlinx.serialization.json.JsonPrimitive(
                                t.message ?: "terminal socket failed",
                            ).toString()
                            + "}",
                    )
                    frames.tryEmit("{\"type\":\"closed\"}")
                }

                override fun onClosed(
                    webSocket: okhttp3.WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    frames.tryEmit("{\"type\":\"closed\"}")
                }
            },
        )
        kotlinx.coroutines.withTimeoutOrNull(5_000) { opened.await() }
        RemoteResult.Success(handle)
    }

    /** `terminal/close`. */
    override suspend fun closeTerminal(
        hostId: String,
        sessionId: String,
        id: String,
    ): RemoteResult<Boolean> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "terminal/close")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("agentId", sessionId)
                    put("id", id)
                })
            })
        }
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/terminal/close")
                .post(payload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                RemoteResult.Success(response.isSuccessful)
            }
        } catch (_: Exception) {
            RemoteResult.Success(false)
        }
    }
}
