package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.protocol.SessionRawFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Строка траектории (как строка ui-trajectory). */
data class TrajectoryRow(
    val seq: Long,
    val kind: String,
    val title: String,
    val detail: String,
    val turn: Int,
    val step: Int,
    val raw: String,
)

private val trajectoryJson = Json { ignoreUnknownKeys = true }

/**
 * Строки траектории из сырых кадров: только события сессии со смыслом.
 *
 * В потоке follow два вида кадров item: `{type:'item', value:{type:'snapshot', records:[…]}}`
 * и `{type:'item', value:{type:'event', event:{…}}}`, разворачиваются оба. Событие,
 * пришедшее и в снимке, и в потоке, остаётся одной строкой.
 */
fun buildTrajectory(frames: List<SessionRawFrame>): List<TrajectoryRow> {
    val events = frames.flatMap { frame ->
        val root = runCatching {
            trajectoryJson.parseToJsonElement(frame.text) as? JsonObject
        }.getOrNull() ?: return@flatMap emptyList()
        if (root["type"].textOrNull() != "item") return@flatMap emptyList()
        val value = root["value"] as? JsonObject ?: return@flatMap emptyList()
        when (value["type"].textOrNull()) {
            "snapshot" -> (value["records"] as? JsonArray)
                ?.mapNotNull { record ->
                    ((record as? JsonObject)?.get("event") as? JsonObject)
                }.orEmpty()
            "event" -> listOfNotNull(value["event"] as? JsonObject)
            else -> emptyList()
        }
    }
    return events.mapNotNull { event -> runCatching { trajectoryRow(event) }.getOrNull() }
        .distinctBy { it.seq }
        .sortedBy { it.seq }
}

/**
 * Краткий текст сообщения (data.message.content у событий user/assistant): строка
 * или части `[{type:'text', text:…}]`, в одну строку.
 */
internal fun textOfMessage(data: JsonObject?): String {
    val content = (data?.get("message") as? JsonObject)?.get("content") ?: return ""
    val text = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text").textOrNull() }.joinToString(" ")
        else -> ""
    }
    return text.replace(Regex("\\s+"), " ").trim().take(MESSAGE_PREVIEW_CHARS)
}

private const val MESSAGE_PREVIEW_CHARS = 80

/** Строка для одного события; при неожиданной форме — null, панель не падает. */
private fun trajectoryRow(event: JsonObject): TrajectoryRow? {
    val type = event["type"].textOrNull() ?: return null
    val seq = event["seq"].textOrNull()?.toLongOrNull() ?: return null
    val data = event["data"] as? JsonObject
    val turn = data?.get("turn").textOrNull()?.toIntOrNull() ?: 0
    val step = data?.get("step").textOrNull()?.toIntOrNull() ?: 0
    val detail = when (type) {
        "tool/call" -> data?.get("name").textOrNull().orEmpty()
        "assistant/message", "user/message" -> textOfMessage(data)
        "request/header" -> data?.get("model").textOrNull().orEmpty()
        "turn/end" -> data?.get("reason").textOrNull().orEmpty()
        else -> ""
    }
    val title = when (type) {
        "assistant/message" -> "assistant"
        "user/message" -> "user"
        "request/header" -> "request"
        "session/title" -> "title"
        else -> type
    }
    return TrajectoryRow(
        seq = seq,
        kind = type,
        title = title,
        detail = detail,
        turn = turn,
        step = step,
        raw = event.toString(),
    )
}

/**
 * Лист «Ход сессии» (trajectory): события сессии по порядку — номер, тип,
 * ход и шаг, кратко; нажатие показывает исходный JSON.
 */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.TrajectoryBody() {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val services = LocalChatServices.current
    val frames = remember { services.sessionFrames() }
    val rows = remember(frames) { buildTrajectory(frames).takeLast(200) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    com.deepseek.dsh.core.designsystem.DshSheetTitle(stringResource(R.string.trajectory_title))
    Text(stringResource(R.string.trajectory_subtitle, rows.size), style = ckBody(12.5.sp, 16.sp), color = c.t3, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
    if (rows.isEmpty()) {
        Text(stringResource(R.string.trajectory_empty), style = ckBody(14.sp, 19.sp), color = c.t3, modifier = Modifier.padding(4.dp))
        return
    }
    com.deepseek.dsh.core.designsystem.DshSheetList {
        rows.forEachIndexed { i, row ->
            val open = expandedSeq == row.seq
            Column(
                Modifier.fillMaxWidth()
                    .then(if (i > 0) Modifier.drawBehind { drawRect(c.line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) } else Modifier)
                    .clickable { expandedSeq = if (open) null else row.seq }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.seq.toString(), style = ckMono(11.5.sp, 15.sp), color = c.t3)
                    Text(row.title, style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.t1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.turn > 0) Text("T${row.turn}" + if (row.step > 0) "·S${row.step}" else "", style = ckMono(11.5.sp, 15.sp), color = c.t3)
                }
                if (open) Text(row.raw, style = ckMono(11.sp, 16.sp), color = c.t2, modifier = Modifier.padding(top = 6.dp))
                else if (row.detail.isNotBlank()) Text(row.detail, style = ckBody(12.5.sp, 16.sp), color = c.t3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Строка поля без исключений: поле может быть объектом или массивом (reason у turn/end), jsonPrimitive нельзя. */
private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
