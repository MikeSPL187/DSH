package com.deepseek.dsh.core.protocol

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/** Изменения файлов сессии и выгрузка журнала сессии. */
internal class ChangesClient(private val conn: HostConnection) : ChangesApi {
    private val baseUrl get() = conn.baseUrl
    private val client get() = conn.client
    private val json get() = conn.json

    /** `/api/session.export`: ZIP с журналом сессии. */
    override suspend fun downloadSessionExport(
        hostId: String,
        sessionId: String,
    ): RemoteResult<ByteArray> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/session.export?sessionId=$sessionId&includeDescendants=true")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure(
                        "dsh/session-export",
                        "HTTP ${response.code}",
                    )
                }
                RemoteResult.Success(response.body?.bytes() ?: ByteArray(0))
            }
        }.getOrElse { e ->
            RemoteResult.Failure("dsh/session-export-network", e.message ?: "export failed")
        }
    }

    /** `/api/changes.summary`. */
    override suspend fun fetchChangesSummary(
        hostId: String,
        sessionId: String,
        seq: Long,
    ): RemoteResult<ChangesSummaryInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/changes.summary?sessionId=$sessionId&seq=$seq")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure(
                        "dsh/changes-summary",
                        "HTTP ${response.code}",
                    )
                }
                val value = json.parseToJsonElement(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject
                    ?: return@use RemoteResult.Failure("dsh/changes-summary", "malformed")
                val files = (value["files"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { item ->
                        val obj = item as? kotlinx.serialization.json.JsonObject
                            ?: return@mapNotNull null
                        fun text(key: String) = (obj[key]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        fun number(key: String) = (obj[key]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?.toIntOrNull() ?: 0
                        val path = text("path") ?: return@mapNotNull null
                        ChangedFileInfo(
                            path = path,
                            display = text("display") ?: path,
                            added = number("added"),
                            deleted = number("deleted"),
                            binary = text("binary") == "true",
                            oversized = text("oversized") == "true",
                        )
                    } ?: emptyList()
                fun number(key: String) = (value[key]
                    as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.toIntOrNull() ?: 0
                RemoteResult.Success(
                    ChangesSummaryInfo(
                        turn = number("turn"),
                        seq = seq,
                        files = files,
                        total = number("total").takeIf { it > 0 } ?: files.size,
                        added = number("added"),
                        deleted = number("deleted"),
                    ),
                )
            }
        }.getOrElse { e ->
            RemoteResult.Failure("dsh/changes-summary-network", e.message ?: "summary failed")
        }
    }

    /** `/api/changes.diff`. */
    override suspend fun fetchChangesDiff(
        hostId: String,
        sessionId: String,
        seq: Long,
        index: Int,
    ): RemoteResult<ChangesDiffInfo> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/changes.diff?sessionId=$sessionId&seq=$seq&index=$index")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use RemoteResult.Failure("dsh/changes-diff", "HTTP ${response.code}")
                }
                val value = json.parseToJsonElement(response.body?.string().orEmpty())
                    as? kotlinx.serialization.json.JsonObject
                    ?: return@use RemoteResult.Failure("dsh/changes-diff", "malformed")
                fun text(key: String) = (value[key]
                    as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                fun flag(key: String) = text(key) == "true"
                val hunks = (value["hunks"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { item ->
                        val obj = item as? kotlinx.serialization.json.JsonObject
                            ?: return@mapNotNull null
                        fun num(key: String) = (obj[key]
                            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?.toIntOrNull() ?: 0
                        DiffHunkInfo(
                            oldStart = num("oldStart"),
                            oldLines = num("oldLines"),
                            newStart = num("newStart"),
                            newLines = num("newLines"),
                            lines = (obj["lines"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { line ->
                                    (line as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.contentOrNull
                                } ?: emptyList(),
                        )
                    } ?: emptyList()
                RemoteResult.Success(
                    ChangesDiffInfo(
                        kind = text("kind") ?: "text",
                        path = text("path") ?: "",
                        display = text("display") ?: text("path").orEmpty(),
                        hunks = hunks,
                        before = flag("before"),
                        after = flag("after"),
                        coarse = flag("coarse"),
                    ),
                )
            }
        }.getOrElse { e ->
            RemoteResult.Failure("dsh/changes-diff-network", e.message ?: "diff failed")
        }
    }
}
