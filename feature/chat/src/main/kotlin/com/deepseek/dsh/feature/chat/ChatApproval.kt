package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.protocol.parseHostJson
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.pressScale
import kotlinx.coroutines.delay

/** Один путь из запроса на удаление: размер, если удалось посчитать; вне ли он проекта. */
data class DangerTarget(val path: String, val bytes: Long?, val outside: Boolean)

/** Что известно о последствиях удаления на этом телефоне. */
data class DangerInfo(val targets: List<DangerTarget>, val freeBytes: Long?)

/**
 * Запрос агента на действие (`approval/request`).
 *
 * @param onAllow разрешить; `always` — больше не спрашивать для этого инструмента.
 * @param inspect посчитать размеры путей и свободное место (только Linux на телефоне); null — неизвестно.
 */
data class ChatApproval(
    val eventId: String,
    val toolName: String,
    val reason: String?,
    val args: String?,
    val callId: String?,
    val onAllow: (always: Boolean) -> Unit,
    val onReject: () -> Unit,
    val inspect: (suspend (List<String>) -> DangerInfo)? = null,
) {
    val tier: ApprovalTier get() = approvalTier(toolName, args)
}

/** Размер по-человечески: «312 МБ», «1,4 ГБ». */
@Composable
internal fun bytesLabel(bytes: Long): String {
    val kb = 1024.0
    val f = java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance())
    return when {
        bytes >= kb * kb * kb -> stringResource(R.string.ck_gb, f.format(bytes / (kb * kb * kb)))
        bytes >= kb * kb -> stringResource(R.string.ck_mb, java.text.DecimalFormat("0", java.text.DecimalFormatSymbols.getInstance()).format(bytes / (kb * kb)))
        bytes >= kb -> stringResource(R.string.ck_kb, java.text.DecimalFormat("0").format(bytes / kb))
        else -> stringResource(R.string.ck_b, bytes)
    }
}

/**
 * Одобрение правки `.approve`: метка уровня, что агент сделает, правка построчно
 * (`.diff`), «больше не спрашивать» и кнопки «Отклонить» / «Разрешить».
 */
@Composable
fun ApproveCard(a: ChatApproval, onDecided: (Boolean) -> Unit) {
    val c = Dsh.colors
    var always by remember(a.eventId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val edge = lerp(c.line, c.accent, 0.35f)
    Column(
        Modifier.fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = c.accent.copy(alpha = 0.12f), spotColor = c.accent.copy(alpha = 0.12f))
            .clip(shape).background(c.s1).border(1.dp, edge, shape).padding(14.dp),
    ) {
        val n = a.toolName.lowercase()
        val shell = n.contains("bash") || n.contains("shell") || n.contains("exec")
        Tier(stringResource(if (shell) R.string.ck_tier_cmd else R.string.ck_tier_file), DshGlyphs.Shield)
        Text(ckToolTitle(a.toolName), style = ckDisplay(16.sp, 20.sp), color = c.t1, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Text(
            if (a.reason.isNullOrBlank()) stringResource(R.string.chat_approval_no_reason) else a.reason,
            style = ckBody(13.5.sp, 19.6.sp), color = c.t2, modifier = Modifier.padding(bottom = 10.dp),
        )
        ApprovalDiff(a.toolName, a.args)
        // `.check`: тумблер «больше не спрашивать» — правило в Настройках → Плагины → инструменты.
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Switch) { always = !always }
                .semantics { toggleableState = if (always) ToggleableState.On else ToggleableState.Off },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Toggle(always)
            Text(stringResource(R.string.ck_always_allow), style = ckBody(13.sp, 16.sp), color = c.t2)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(stringResource(R.string.chat_approval_reject), BtnStyle.Ghost, Modifier.weight(1f)) { onDecided(false); a.onReject() }
            Btn(stringResource(R.string.chat_approval_allow_once), BtnStyle.Primary, Modifier.weight(1f)) { onDecided(true); a.onAllow(always) }
        }
    }
}

/** Правка построчно: файл и строки «−» на danger-soft, «+» на ok-soft; команда — моноширинным. */
@Composable
private fun ApprovalDiff(toolName: String, args: String?) {
    val c = Dsh.colors
    // Аргументы от агента — через разбор с пределом глубины (org.json переполнял стек на `[[[[…`).
    val obj = remember(args) {
        runCatching { kotlinx.serialization.json.Json.parseHostJson(args.orEmpty()) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    }
    fun str(key: String) = (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    val path = str("file_path")?.ifBlank { null } ?: str("path")?.ifBlank { null }
    val old = str("old_string").orEmpty()
    val new = str("new_string")?.ifBlank { null } ?: str("content").orEmpty()
    val command = str("command")?.ifBlank { null }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape)) {
        if (command != null) {
            Text(command, style = ckMono(12.sp, 20.4.sp), color = c.t1, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(c.codeBg).padding(horizontal = 10.dp, vertical = 7.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().background(c.s2).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(path ?: toolName, style = ckMono(12.sp, 20.4.sp), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val dels = old.lines().filter { it.isNotEmpty() }.take(6)
        val adds = new.lines().filter { it.isNotEmpty() }.take(if (old.isEmpty()) 8 else 6)
        dels.forEach { Text("− $it", style = ckMono(12.sp, 20.4.sp), color = c.danger, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().background(c.dangerSoft).padding(horizontal = 10.dp)) }
        adds.forEach { Text("+ $it", style = ckMono(12.sp, 20.4.sp), color = c.ok, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().background(c.okSoft).padding(horizontal = 10.dp)) }
    }
}

/** Тумблер `.toggle` 38 × 23. */
@Composable
internal fun Toggle(on: Boolean) {
    val c = Dsh.colors
    val bg by animateColorAsState(if (on) c.accent else c.s3, tween(250), label = "tg")
    val x by animateFloatAsState(if (on) 15f else 0f, DshMotion.lively(), label = "tgx")
    Box(Modifier.size(width = 38.dp, height = 23.dp).clip(RoundedCornerShape(12.dp)).background(bg)) {
        Box(
            Modifier.offset(x = (2.5f + x).dp, y = 2.5.dp).size(18.dp)
                .shadow(1.5.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

internal enum class BtnStyle { Ghost, Primary, Danger }

/** Кнопка `.btn`: 44 dp, радиус 14, 15/500. */
@Composable
internal fun Btn(text: String, style: BtnStyle, modifier: Modifier = Modifier, height: Dp = 44.dp, radius: Dp = 14.dp, fontSize: Float = 15f, ink: Color? = null, arm: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val (bg, fg) = when (style) {
        BtnStyle.Ghost -> c.s2 to c.t1
        BtnStyle.Primary -> c.accent to c.onAccent
        BtnStyle.Danger -> c.dangerFill to Color.White
    }
    val pulse = if (arm) {
        val t = rememberInfiniteTransition(label = "arm")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p").value
    } else 0f
    Box(
        modifier.height(height).pressScale(ia, 0.96f)
            .drawBehind { if (arm) drawRoundRect(c.danger.copy(alpha = 0.22f * pulse), topLeft = androidx.compose.ui.geometry.Offset(-5.dp.toPx() * pulse, -5.dp.toPx() * pulse), size = Size(size.width + 10.dp.toPx() * pulse, size.height + 10.dp.toPx() * pulse), cornerRadius = androidx.compose.ui.geometry.CornerRadius((radius + 5.dp * pulse).toPx())) }
            .clip(RoundedCornerShape(radius)).background(bg)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = ckBody(fontSize.sp, (fontSize * 1.25f).sp, FontWeight.Medium), color = ink ?: fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp)) }
}

/** Карточка опасного шага `.ask`: что удаляется, размер и «ждёт вашего решения». */
@Composable
fun DangerAskCard(a: ChatApproval, info: DangerInfo?, onOpen: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val total = info?.targets?.mapNotNull { it.bytes }?.takeIf { it.isNotEmpty() }?.sum()
    Row(
        Modifier.fillMaxWidth().pressScale(ia, 0.98f).clip(shape).background(c.s1).border(1.dp, lerp(c.line, c.warn, 0.4f), shape)
            .clickable(ia, ripple(), role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.dangerSoft), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Trash, null, tint = c.danger, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(dangerTitle(a), style = ckBody(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(total?.let { bytesLabel(it) }, stringResource(R.string.ck_waits_decision)).joinToString(" · "),
                style = ckBody(12.5.sp, 16.25.sp), color = c.warn,
            )
        }
        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
    }
}

/** «Удалить build/ и ещё 1» — из путей команды; иначе — что делает инструмент. */
@Composable
internal fun dangerTitle(a: ChatApproval): String {
    val targets = remember(a.toolName, a.args) { deletionTargets(a.toolName, a.args) }
    return when {
        targets.size == 1 -> stringResource(R.string.ck_delete_one, targets[0])
        targets.size > 1 -> stringResource(R.string.ck_delete_many, targets[0], targets.size - 1)
        else -> ckToolTitle(a.toolName)
    }
}

/** Итог решения `.result` под шагом. */
@Composable
fun DecisionResult(allowed: Boolean, destructive: Boolean) {
    val c = Dsh.colors
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (allowed) DshGlyphs.Check else DshGlyphs.X, null, tint = if (allowed) c.ok else c.t3, modifier = Modifier.size(15.dp))
        Text(
            stringResource(
                when {
                    allowed -> R.string.ck_you_allowed
                    destructive -> R.string.ck_you_rejected_delete
                    else -> R.string.ck_you_rejected
                },
            ),
            style = ckBody(13.5.sp, 17.sp), color = if (allowed) c.ok else c.t3,
        )
    }
}

/**
 * Панель решения `.decide` вместо поля ввода: «Ждёт вашего решения · удаление · 1,4 ГБ»,
 * «Отклонить» и «Удалить» — второе касание в течение 3 с подтверждает.
 */
@Composable
fun DecidePanel(a: ChatApproval, info: DangerInfo?, onDecided: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val haptics = LocalHapticFeedback.current
    var armed by remember(a.eventId) { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    val shape = RoundedCornerShape(28.dp)
    val total = info?.targets?.mapNotNull { it.bytes }?.takeIf { it.isNotEmpty() }?.sum()
    Column(
        modifier.fillMaxWidth().floatShadow(shape, c.isDark).clip(shape).background(c.glass.compositeOn(c.bg))
            .border(1.dp, c.glassLine, shape).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp)) { Pulse(c.warn) }
            Text(stringResource(R.string.ck_waits_decision_title), style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.t1, modifier = Modifier.weight(1f))
            Text(listOfNotNull(stringResource(R.string.ck_deletion), total?.let { bytesLabel(it) }).joinToString(" · "), style = ckBody(12.5.sp, 16.sp), color = c.t3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(stringResource(R.string.chat_approval_reject), BtnStyle.Ghost, Modifier.weight(1f), height = 46.dp, radius = 15.dp, fontSize = 14.5f) { onDecided(false); a.onReject() }
            Btn(
                stringResource(if (armed) R.string.chat_approval_tap_again else R.string.ck_delete), BtnStyle.Danger, Modifier.weight(1f),
                height = 46.dp, radius = 15.dp, fontSize = 14.5f, arm = armed,
            ) {
                if (!armed) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); armed = true }
                else { onDecided(true); a.onAllow(false) }
            }
        }
        Text(
            stringResource(if (armed) R.string.chat_approval_armed_hint else R.string.ck_two_taps),
            style = ckBody(11.5.sp, 14.sp), color = c.t3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        )
    }
}

/**
 * Экран «Запрос агента» `#screen-danger`: что удаляется и сколько, что изменится,
 * команда целиком; внизу «Отклонить» и «Удалить N» (два касания).
 */
@Composable
fun DangerScreen(a: ChatApproval, info: DangerInfo?, session: String?, onBack: () -> Unit, onDecided: (Boolean) -> Unit) {
    val c = Dsh.colors
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    BackHandler(onBack = onBack)
    var armed by remember(a.eventId) { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    val targets = remember(a.toolName, a.args) { deletionTargets(a.toolName, a.args) }
    val known = info?.targets.orEmpty()
    val total = known.mapNotNull { it.bytes }.takeIf { it.isNotEmpty() }?.sum()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        // `.sbar`
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChatIconBtn(DshGlyphs.Left, stringResource(R.string.ck_back), onClick = onBack)
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(stringResource(R.string.ck_agent_request), style = ckDisplay(16.sp, 19.2.sp), color = c.t1)
                if (!session.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(c.warn))
                    Text(session, style = ckBody(12.sp, 14.4.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)) {
            // `.dz-hero`
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(c.dangerSoft), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Trash, null, tint = c.danger, modifier = Modifier.size(22.dp))
                }
                Text(dangerTitle(a), style = ckDisplay(23.sp, 27.6.sp).copy(letterSpacing = (-0.015).em), color = c.t1, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Text(if (a.reason.isNullOrBlank()) stringResource(R.string.chat_approval_no_reason) else a.reason, style = ckBody(15.sp, 22.5.sp), color = c.t2)
            }
            if (targets.isNotEmpty()) {
                DzLabel(stringResource(R.string.ck_what_delete), if (targets.size > 1) stringResource(R.string.ck_n_paths, targets.size) else null)
                DzGroup {
                    targets.forEachIndexed { i, p ->
                        val t = known.firstOrNull { it.path == p }
                        DzRow(first = i == 0) {
                            Column(Modifier.weight(1f)) {
                                Text(p, style = ckMono(13.5.sp, 18.sp, FontWeight.Medium), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(if (t?.outside == true) R.string.ck_outside_project else R.string.ck_in_project),
                                    style = ckBody(12.5.sp, 16.sp), color = if (t?.outside == true) c.warn else c.t3,
                                )
                            }
                            Text(t?.bytes?.let { bytesLabel(it) } ?: "—", style = ckBody(15.sp, 19.sp, FontWeight.SemiBold), color = c.t1)
                        }
                    }
                    if (total != null) DzRow(first = false, total = true) {
                        Text(stringResource(R.string.ck_will_delete), style = ckBody(14.sp, 18.sp), color = c.t2, modifier = Modifier.weight(1f))
                        Text(bytesLabel(total), style = ckDisplay(17.sp, 21.sp), color = c.danger)
                    }
                }
            }
            DzLabel(stringResource(R.string.ck_what_changes), null)
            DzGroup {
                if (info?.freeBytes != null && total != null) DzRow(first = true) {
                    Gic(DshGlyphs.Check, c.okSoft, c.ok)
                    Text(stringResource(R.string.ck_free_space), style = ckBody(15.sp, 19.sp), color = c.t1, modifier = Modifier.weight(1f))
                    // Как в прототипе: «0,9 → 2,3 ГБ» — единица одна, у итога.
                    val after = info.freeBytes + total
                    val gb = 1024.0 * 1024 * 1024
                    val before = if (after >= gb) java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance()).format(info.freeBytes / gb) else bytesLabel(info.freeBytes)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("$before → ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = c.ok, fontWeight = FontWeight.SemiBold)) { append(bytesLabel(after)) }
                        },
                        style = ckBody(14.5.sp, 18.sp), color = c.t2,
                    )
                }
                DzRow(first = info?.freeBytes == null || total == null) {
                    Gic(DshGlyphs.Redo, c.dangerSoft, c.danger)
                    Text(stringResource(R.string.ck_undo), style = ckBody(15.sp, 19.sp), color = c.t1, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.ck_undo_no), style = ckBody(14.5.sp, 18.sp, FontWeight.Medium), color = c.danger)
                }
            }
            approvalTarget(a.args)?.let { cmd ->
                DzLabel(stringResource(R.string.ck_command), null)
                DzGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(cmd, style = ckMono(13.sp, 18.sp), color = c.t1, modifier = Modifier.weight(1f))
                        ChatIconBtn(DshGlyphs.Copy, stringResource(R.string.ck_copy_command), tint = c.t3, iconSize = 18.dp) {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("command", cmd))
                        }
                    }
                }
            }
        }
        // `.dz-bar`
        Column(
            Modifier.fillMaxWidth().background(c.bg).drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(stringResource(R.string.chat_approval_reject), BtnStyle.Ghost, Modifier.weight(1f), height = 52.dp, radius = 16.dp, fontSize = 16f) { onDecided(false); a.onReject() }
                Btn(
                    when {
                        armed -> stringResource(R.string.chat_approval_tap_again)
                        total != null -> stringResource(R.string.ck_delete_size, bytesLabel(total))
                        else -> stringResource(R.string.ck_delete)
                    },
                    BtnStyle.Danger, Modifier.weight(1.35f), height = 52.dp, radius = 16.dp, fontSize = 16f, arm = armed,
                ) {
                    if (!armed) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); armed = true }
                    else { onDecided(true); a.onAllow(false) }
                }
            }
            Text(
                stringResource(if (armed) R.string.chat_approval_armed_hint else R.string.ck_no_trash),
                style = ckBody(12.sp, 15.sp), color = c.t3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DzLabel(text: String, right: String?) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 8.dp)) {
        Text(text.uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = c.t3, modifier = Modifier.weight(1f))
        if (right != null) Text(right, style = ckBody(12.5.sp, 15.sp), color = c.t3)
    }
}

@Composable
private fun DzGroup(content: @Composable () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(1.dp)) { content() }
}

@Composable
private fun DzRow(first: Boolean, total: Boolean = false, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (total) 46.dp else 54.dp)
            .then(if (total) Modifier.background(c.s2) else Modifier)
            .drawBehind { if (!first) drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Gic(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

