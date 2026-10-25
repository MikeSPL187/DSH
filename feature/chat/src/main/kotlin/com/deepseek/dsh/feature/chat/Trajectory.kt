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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话原始帧的提供者（宿主注入）。
 *
 * 与消息反馈一样用桥接：轨迹面板需要的帧来自 RemoteClient 的环形缓存，宿主只在
 * 已有的会话 effect 里赋值，不再扩大 `MainAppHost`。
 */
object SessionFramesBridge {
    var provider: (() -> List<SessionRawFrame>)? = null
}

/** 一条轨迹记录（对齐官方 ui-trajectory 的一行）。 */
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
 * 从原始帧里解析出轨迹行：只保留真正有语义的会话事件。
 *
 * follow 流有两种 item 帧：`{type:'item', value:{type:'snapshot', records:[…]}}`
 * 与 `{type:'item', value:{type:'event', event:{…}}}`，两者都要展开。
 */
fun buildTrajectory(frames: List<SessionRawFrame>): List<TrajectoryRow> {
    val events = frames.flatMap { frame ->
        val root = runCatching {
            trajectoryJson.parseToJsonElement(frame.text) as? JsonObject
        }.getOrNull() ?: return@flatMap emptyList()
        if (root["type"].textOrNull() != "item") return@flatMap emptyList()
        val value = root["value"] as? JsonObject ?: return@flatMap emptyList()
        when (value["type"].textOrNull()) {
            "snapshot" -> (value["records"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { record ->
                    ((record as? JsonObject)?.get("event") as? JsonObject)
                }.orEmpty()
            "event" -> listOfNotNull(value["event"] as? JsonObject)
            else -> emptyList()
        }
    }
    return events.mapNotNull { event -> runCatching { trajectoryRow(event) }.getOrNull() }
        .sortedBy { it.seq }
}

/** 取消息文本摘要（user/assistant 事件的 data.message.content）。 */
private fun textOfMessage(data: JsonObject?): String {
    val message = data?.get("message") as? JsonObject ?: return ""
    val content = message["content"] ?: return ""
    return content.toString().replace("\\n", " ").take(120)
}

/** 单个事件的轨迹行；形状异常时返回 null 而不是让面板崩溃。 */
private fun trajectoryRow(event: JsonObject): TrajectoryRow? {
    val type = event["type"].textOrNull() ?: return null
    val seq = event["seq"].textOrNull()?.toLongOrNull() ?: return null
    val data = event["data"] as? JsonObject
    val turn = data?.get("turn").textOrNull()?.toIntOrNull() ?: 0
    val step = data?.get("step").textOrNull()?.toIntOrNull() ?: 0
    val detail = when (type) {
        "tool/call" -> data?.get("name").textOrNull().orEmpty()
        "assistant/message" -> textOfMessage(data).take(80)
        "user/message" -> textOfMessage(data).take(80)
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
    val frames = remember { SessionFramesBridge.provider?.invoke().orEmpty() }
    val rows = remember(frames) { buildTrajectory(frames).takeLast(200) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    com.deepseek.dsh.core.designsystem.DshSheetTitle(stringResource(R.string.trajectory_title))
    Text(stringResource(R.string.trajectory_subtitle, rows.size), style = ckBody(12.5.sp, 16.sp), color = c.t3, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp).offsetUp())
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

private fun Modifier.offsetUp() = this.then(Modifier.padding(top = 0.dp))

/** 安全取字符串：字段可能是对象/数组（如 turn/end 的 reason），不能直接 jsonPrimitive。 */
private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
