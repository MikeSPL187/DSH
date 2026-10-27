package com.dsh.core.protocol

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Фоновые задачи сессии (`job/list`, `job/follow`, `job/kill`). */
internal class JobsClient(private val conn: HostConnection) : JobsApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json
    private val mediaTypeJson get() = conn.mediaTypeJson

    /** Поток `job/list`: каждый кадр item — полный набор видимых задач. */
    override fun jobList(hostId: String, sessionId: String): Flow<List<HostJobInfo>> =
        callbackFlow {
            val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
            val streamId = java.util.UUID.randomUUID().toString()
            val socket = client.newWebSocket(
                okhttp3.Request.Builder().url(wsUrl).build(),
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                        val open = buildJsonObject {
                            put("type", "open")
                            put("streamId", streamId)
                            put("endpoint", "job/list")
                            put("payload", buildJsonObject {
                                put("args", buildJsonObject {
                                    put("request", buildJsonObject {
                                        put("sessionId", sessionId)
                                    })
                                })
                            })
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
                                "item" -> {
                                    val value = frame["value"]
                                        as? kotlinx.serialization.json.JsonObject
                                        ?: return@runCatching
                                    val jobs = (value["jobs"]
                                        as? kotlinx.serialization.json.JsonArray)
                                        ?.mapNotNull { item ->
                                            parseJobView(item)
                                        }
                                    if (jobs != null) trySend(jobs)
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
                runCatching {
                    socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}")
                }
                runCatching { socket.close(1000, "client closed") }
            }
        }

    /** Поток `job/follow`: кадры opened / output / status передаются как есть. */
    override fun jobFollow(
        hostId: String,
        sessionId: String?,
        jobId: String,
        from: Long?,
    ): Flow<String> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst("^http".toRegex(), "ws") + "/api/remote.mux"
        val streamId = java.util.UUID.randomUUID().toString()
        val socket = client.newWebSocket(
            okhttp3.Request.Builder().url(wsUrl).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                    val open = buildJsonObject {
                        put("type", "open")
                        put("streamId", streamId)
                        put("endpoint", "job/follow")
                        put("payload", buildJsonObject {
                            put("args", buildJsonObject {
                                put("request", buildJsonObject {
                                    put("jobId", jobId)
                                    if (sessionId != null) put("sessionId", sessionId)
                                    if (from != null) put("from", from)
                                })
                            })
                        })
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
            runCatching {
                socket.send("{\"type\":\"cancel\",\"streamId\":\"$streamId\"}")
            }
            runCatching { socket.close(1000, "client closed") }
        }
    }

    /** `job/kill`: остановить фоновую задачу вручную. */
    override suspend fun killJob(
        hostId: String,
        sessionId: String,
        jobId: String,
    ): RemoteResult<String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "client-request")
            put("rpcId", java.util.UUID.randomUUID().toString())
            put("method", "job/kill")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("sessionId", sessionId)
                        put("jobId", jobId)
                    })
                })
            })
        }
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/job/kill")
                .post(payload.toString().toRequestBody(mediaTypeJson))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure("dsh/job-kill-failed", "HTTP ${response.code}")
                }
                val root = json.parseHostJson(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject
                val result = root?.get("result") as? kotlinx.serialization.json.JsonObject
                val error = result?.get("error") as? kotlinx.serialization.json.JsonObject
                if (error != null) {
                    val code = (error["code"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull ?: "dsh/job-kill-failed"
                    val message = (error["message"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull ?: "kill failed"
                    return@use RemoteResult.Failure(code, message)
                }
                val value = result?.get("value") as? kotlinx.serialization.json.JsonObject
                val outcome = (value?.get("outcome") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull ?: "requested"
                RemoteResult.Success(outcome)
            }
        } catch (e: Exception) {
            RemoteResult.Failure("dsh/job-kill-network", e.message ?: "kill failed")
        }
    }

    /** Разбор проекции JobView. */
    private fun parseJobView(element: kotlinx.serialization.json.JsonElement): HostJobInfo? {
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        fun text(key: String): String? =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        fun long(key: String): Long? =
            obj[key]?.toString()?.trim('"')?.toLongOrNull()
        val id = text("id") ?: return null
        return HostJobInfo(
            id = id,
            kind = text("kind").orEmpty(),
            label = text("label").orEmpty(),
            owner = text("owner"),
            status = text("status") ?: "running",
            progress = text("progress"),
            detail = text("detail"),
            startedAt = long("startedAt") ?: 0L,
            finishedAt = long("finishedAt"),
        )
    }
}
