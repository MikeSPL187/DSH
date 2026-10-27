package com.deepseek.dsh.feature.chat

import com.deepseek.dsh.core.protocol.parseHostJson
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.pressScale
import com.deepseek.dsh.core.model.ChatMessage
import com.deepseek.dsh.core.model.MessageRole
import kotlinx.coroutines.launch

// Лента чата из прототипа: `.msg-user`, `.msg-ai`, `.work` с шагами `.step` и карточками `.tool`.

/** Пузырь пользователя `.msg-user`: до 82 % ширины, s2, радиусы 22/22/6/22. */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Dsh.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * 0.82f)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp))
                    .background(c.s2).padding(horizontal = 15.dp, vertical = 11.dp),
            ) { Text(text, style = ckBody(16.sp, 23.2.sp), color = c.t1) }
        }
        // Host не принял сообщение: пометка и «Повторить» (кнопка как `.nb-btn` полосы «Нет соединения»).
        if (onRetry != null) Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(DshGlyphs.xs("alert"), null, tint = c.danger, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.chat_not_sent), style = ckBody(12.5.sp, 16.sp), color = c.danger)
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(c.s2)
                    .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.chat_retry_send), style = ckBody(13.5.sp).copy(fontWeight = FontWeight.Medium), color = c.accent)
            }
        }
    }
}

/** Состояние блока хода работы. */
enum class WorkState { Running, Done, Stopped }

/**
 * Шапка `.work-head`: «орб» (крутится, пока агент работает; зелёная галочка — готово),
 * подпись с переливом во время работы и шеврон сворачивания.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun WorkHead(state: WorkState, label: String, collapsed: Boolean, onToggle: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val c = Dsh.colors
    val rot by animateFloatAsState(if (collapsed) -90f else 0f, DshMotion.snappy(), label = "chev")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .combinedClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onLongClick = onLongPress, onClick = onToggle)
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Orb(state)
        if (state == WorkState.Running) ShimmerText(label, Modifier.weight(1f))
        else Text(label, style = ckBody(14.sp, 18.sp), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(DshGlyphs.Chev, null, tint = c.t3, modifier = Modifier.size(18.dp).rotate(rot))
    }
}

/** `.orb`: квадрат 22 с радиусом 7 — вращающийся конический градиент с «дыркой»; готово — ok-soft с галочкой. */
@Composable
fun Orb(state: WorkState) {
    val c = Dsh.colors
    if (state != WorkState.Running) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (state == WorkState.Done) c.okSoft else c.s2), contentAlignment = Alignment.Center) {
            Icon(if (state == WorkState.Done) DshGlyphs.Check else DshGlyphs.Stop, null, tint = if (state == WorkState.Done) c.ok else c.t3, modifier = Modifier.size(13.dp))
        }
        return
    }
    val t = rememberInfiniteTransition(label = "orb")
    val deg by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "deg")
    val bg = c.bg
    Box(
        Modifier.size(22.dp).graphicsLayer { rotationZ = deg }.drawBehind {
            drawRoundRect(
                Brush.sweepGradient(listOf(c.accent, c.accent2, Color(0xFFC2BBFF), c.accent)),
                cornerRadius = CornerRadius(7.dp.toPx()),
            )
            val inset = 3.dp.toPx()
            drawRoundRect(bg, topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), cornerRadius = CornerRadius(5.dp.toPx()))
        },
    )
}

/** `.shimmer`: текст t2 с бегущим светлым бликом t1. */
@Composable
private fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val t = rememberInfiniteTransition(label = "shim")
    val p by t.animateFloat(1f, -1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val brush = Brush.linearGradient(
            0f to c.t2, 0.35f to c.t2, 0.5f to c.t1, 0.65f to c.t2, 1f to c.t2,
            start = Offset(w * 2 * p - w * 0f, 0f), end = Offset(w * 2 * p + w * 2f, 0f),
        )
        Text(text, style = ckBody(14.sp, 18.sp).copy(brush = brush), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Состояние шага `.step`: точка на линии слева. */
enum class StepState { Running, Ok, Warn, Plain, Danger }

/**
 * Список шагов `.steps`: отступ 30, вертикальная линия 1,5 dp на x = 10,
 * у каждого шага точка 14 dp (идёт — дышит акцентом, ok — зелёная с галочкой, warn — жёлтая).
 */
@Composable
fun Steps(steps: List<Pair<StepState, @Composable () -> Unit>>) {
    val c = Dsh.colors
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val x = 10.dp.toPx()
            drawRoundRect(c.line, topLeft = Offset(x, 6.dp.toPx()), size = Size(1.5.dp.toPx(), size.height - 12.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
        }.padding(start = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEach { (state, content) ->
            Box {
                Column(Modifier.fillMaxWidth()) { content() }
                StepDot(state, Modifier.padding(top = 10.dp).graphicsLayer { translationX = -26.dp.toPx() })
            }
        }
    }
}

@Composable
private fun StepDot(state: StepState, modifier: Modifier) {
    val c = Dsh.colors
    val (bg, border) = when (state) {
        StepState.Ok -> c.ok to c.ok
        StepState.Warn -> c.warn to c.warn
        StepState.Running -> c.bg to c.accent
        StepState.Danger -> c.bg to c.danger
        StepState.Plain -> c.bg to c.t3
    }
    Box(modifier.size(14.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.Ok -> Icon(DshGlyphs.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            StepState.Warn -> Icon(DshGlyphs.Alert, null, tint = Color.White, modifier = Modifier.size(10.dp))
            StepState.Running, StepState.Danger -> {
                val t = rememberInfiniteTransition(label = "breathe")
                val s by t.animateFloat(1f, 0.55f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "s")
                Box(Modifier.size(6.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = 0.45f + 0.55f * s }.clip(CircleShape).background(if (state == StepState.Danger) c.danger else c.accent))
            }
            StepState.Plain -> Unit
        }
    }
}

/**
 * Карточка инструмента `.tool`: иконка 28 на s2, что делает агент (14/500),
 * аргумент моноширинным 12, время справа; ниже — хвост вывода `.log`.
 */
@Composable
fun ToolCard(
    icon: ImageVector,
    title: String,
    sub: String?,
    duration: String? = null,
    log: List<String> = emptyList(),
    error: Boolean = false,
    onClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.s1).border(1.dp, c.line, shape)) {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(c.s2), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.t2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = ckBody(14.sp, 17.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrBlank()) Text(sub, style = ckMono(12.sp, 14.5.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!duration.isNullOrBlank()) Text(duration, style = ckBody(12.sp, 15.sp), color = c.t3)
        }
        if (log.isNotEmpty()) LogTail(log)
        extra()
    }
}

/** `.log`: последние строки вывода, ошибки красным, успех зелёным; не выше 118 dp. */
@Composable
private fun LogTail(lines: List<String>) {
    val c = Dsh.colors
    val text = remember(lines, c.isDark) {
        buildAnnotatedString {
            lines.forEachIndexed { i, l ->
                if (i > 0) append('\n')
                val color = when {
                    ERR.containsMatchIn(l) -> c.danger
                    OK.containsMatchIn(l) -> c.ok
                    else -> null
                }
                if (color != null) withStyle(SpanStyle(color = color)) { append(l) } else append(l)
            }
        }
    }
    // Как `overflow: hidden` в CSS: текст сверху с отступом 10, лишнее срезается снизу.
    Box(
        Modifier.fillMaxWidth().heightIn(max = 118.dp).clip(androidx.compose.ui.graphics.RectangleShape).background(c.codeBg)
            .drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) },
    ) {
        Text(
            text, style = ckMono(11.5.sp, 18.4.sp), color = c.t2, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                .wrapContentHeight(Alignment.Top, unbounded = true),
        )
    }
}

private val ERR = Regex("""(^e: |^\s+requires |error:|Error:|ERROR|не найден|not found)""")
private val OK = Regex("""(BUILD SUCCESSFUL|passed|✓|\bOK\b)""")

/** Рассуждение модели как шаг: свёрнутая карточка «Рассуждает», по нажатию — текст и перевод. */
@Composable
fun ReasoningStep(text: String, translate: (suspend (String) -> Result<String>)?) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var translated by remember(text) { mutableStateOf<String?>(null) }
    var showTr by remember(text) { mutableStateOf(false) }
    var busy by remember(text) { mutableStateOf(false) }
    var failed by remember(text) { mutableStateOf(false) }
    ToolCard(
        icon = DshGlyphs.Spark,
        title = stringResource(R.string.chat_thinking),
        sub = if (open) null else text.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
        onClick = { open = !open },
    ) {
        AnimatedVisibility(open, enter = expandVertically(DshMotion.smooth()) + fadeIn(), exit = shrinkVertically(DshMotion.snappy()) + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(if (showTr) translated ?: text else text, style = ckBody(13.5.sp, 20.sp), color = c.t2)
                if (translate != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction(
                        stringResource(
                            when {
                                busy -> R.string.chat_translating
                                failed -> R.string.action_retry
                                showTr -> R.string.chat_view_original
                                else -> R.string.chat_view_translation
                            },
                        ),
                    ) {
                        if (busy) return@SmallAction
                        if (showTr) { showTr = false; return@SmallAction }
                        translated?.let { showTr = true; return@SmallAction }
                        scope.launch {
                            busy = true; failed = false
                            translate(text).fold(onSuccess = { translated = it; showTr = true }, onFailure = { failed = true })
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

/** Маленькая кнопка `.mini`: 36, радиус 10, s2. */
@Composable
internal fun SmallAction(text: String, icon: ImageVector? = null, accent: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val fg = if (accent) c.accent else c.t1
    Row(
        Modifier.height(36.dp).pressScale(ia, 0.96f).clip(RoundedCornerShape(10.dp)).background(if (accent) c.accentSoft else c.s2)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = ckBody(12.5.sp, 16.sp), color = fg, maxLines = 1)
    }
}

/** Ответ агента `.msg-ai`: 16 sp, межстрочный 1,6. */
@Composable
fun AiAnswer(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        MarkdownMessageText(text = text, color = Dsh.colors.t1)
    }
}

/** Ошибка выполнения: красная карточка с полным текстом. */
@Composable
fun RunErrorCard(text: String) {
    val c = Dsh.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.dangerSoft).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(DshGlyphs.Alert, null, tint = c.danger, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.chat_run_failed), style = ckBody(13.sp, 17.sp, FontWeight.SemiBold), color = c.danger)
        }
        Text(text, style = ckBody(13.5.sp, 20.sp), color = c.t1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Действия под ответом `.actions`: копировать, повторить, поделиться (+ оценка, если включена). */
@Composable
fun AnswerActions(onCopy: () -> Unit, onRetry: () -> Unit, onShare: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = Dsh.colors
    Row(Modifier.padding(top = 4.dp, bottom = 12.dp).graphicsLayer { translationX = -10.dp.toPx() }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ChatIconBtn(DshGlyphs.Copy, stringResource(R.string.ck_copy), tint = c.t3, iconSize = 18.dp, onClick = onCopy)
        ChatIconBtn(DshGlyphs.Redo, stringResource(R.string.ck_retry), tint = c.t3, iconSize = 18.dp, onClick = onRetry)
        ChatIconBtn(DshGlyphs.Share, stringResource(R.string.ck_share), tint = c.t3, iconSize = 18.dp, onClick = onShare)
        extra()
    }
}

/** Иконка инструмента по имени. */
internal fun toolGlyph(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("command") -> DshGlyphs.Term
        n.contains("edit") || n.contains("write") || n.contains("patch") || n.contains("replace") -> DshGlyphs.Pencil
        n.contains("read") || n.contains("view") || n.contains("cat") -> DshGlyphs.File
        n.contains("grep") || n.contains("search") || n.contains("find") || n.contains("glob") || n.contains("list") -> DshGlyphs.Search
        n.contains("web") || n.contains("fetch") || n.contains("http") -> DshGlyphs.Globe
        n.contains("ask") || n.contains("question") -> DshGlyphs.Msg
        n.contains("todo") || n.contains("plan") -> DshGlyphs.Tasks
        else -> DshGlyphs.Bolt
    }
}

/** Разбор сообщения инструмента: `\u0002`-поля (id, имя, статус, тело, аргументы). */
internal data class ToolMsg(val callId: String, val name: String, val status: String, val body: String, val args: String)

internal fun parseTool(m: ChatMessage): ToolMsg? {
    if (m.role != MessageRole.TOOL) return null
    val p = m.content.split(2.toChar())
    if (p.size < 5) return null
    return if (p.size >= 6) ToolMsg(p[2], p[3], p[4], p[5], p.getOrNull(6).orEmpty())
    else ToolMsg("", p[2], p[3], p[4], "")
}


/** Название шага в карточке — что делает агент (3-е лицо, как в прототипе). */
@Composable
internal fun ckToolTitle(name: String): String {
    val n = name.lowercase()
    return stringResource(
        when {
            n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("command") -> R.string.ck_t_cmd
            n.contains("ask_user_question") -> R.string.ck_t_ask
            n.contains("goal") -> R.string.ck_t_goal
            n.contains("subagent") -> R.string.ck_t_sub
            n.contains("todo") -> R.string.ck_t_todo
            n.contains("write") -> R.string.ck_t_write
            n.contains("edit") || n.contains("patch") || n.contains("replace") -> R.string.ck_t_edit
            n.contains("read") || n.contains("view") -> R.string.ck_t_read
            n.contains("web") || n.contains("fetch") -> R.string.ck_t_web
            n.contains("grep") || n.contains("glob") || n.contains("search") || n.contains("find") || n.contains("list") -> R.string.ck_t_search
            else -> R.string.ck_t_generic
        },
    )
}

/** Подпись шапки хода работы — что агент делает сейчас (1-е лицо). */
@Composable
internal fun ckHeadLabel(runningTool: String?): String {
    val n = runningTool?.lowercase() ?: return stringResource(R.string.ck_h_thinking)
    return stringResource(
        when {
            n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("command") -> R.string.ck_h_cmd
            n.contains("ask_user_question") -> R.string.ck_h_ask
            n.contains("edit") || n.contains("write") || n.contains("patch") -> R.string.ck_h_edit
            n.contains("read") || n.contains("view") -> R.string.ck_h_read
            n.contains("web") || n.contains("fetch") -> R.string.ck_h_web
            n.contains("grep") || n.contains("glob") || n.contains("search") || n.contains("find") || n.contains("list") -> R.string.ck_h_search
            else -> R.string.ck_h_generic
        },
    )
}

/** «0,4 с», «41 с», «1 мин 4 с». */
@Composable
internal fun ckDuration(ms: Long): String {
    val s = ms / 1000.0
    if (s >= 60) return stringResource(R.string.ck_min_sec, (ms / 60000).toInt(), ((ms / 1000) % 60).toInt())
    val f = java.text.DecimalFormat(if (s < 10) "0.#" else "0", java.text.DecimalFormatSymbols.getInstance())
    return stringResource(R.string.ck_sec, f.format(s))
}

/**
 * Ход работы одного запроса `.work`: шапка с орбом и шаги — карточки инструментов,
 * рассуждения и промежуточные ответы. Пока агент работает, шаги раскрыты; готово — свёрнуто.
 */
@Composable
internal fun WorkBlock(
    items: List<ChatMessage>,
    done: Boolean,
    durationMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    liveReasoning: String = "",
    translate: (suspend (String) -> Result<String>)? = null,
    onOpenDetail: (ToolDetailData) -> Unit = {},
    askContent: @Composable (ToolMsg) -> Unit = {},
    approval: ChatApproval? = null,
    dangerInfo: DangerInfo? = null,
    decided: Map<String, Pair<Boolean, Boolean>> = emptyMap(),
    onDecided: (ChatApproval, Boolean) -> Unit = { _, _ -> },
    onOpenDanger: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val tools = remember(items) { items.mapNotNull { parseTool(it) } }
    val running = tools.lastOrNull { it.status == "running" }
    val label = when {
        approval != null && !done -> stringResource(R.string.ck_h_wait)
        done && tools.isNotEmpty() -> stringResource(R.string.ck_work_done, androidx.compose.ui.res.pluralStringResource(R.plurals.ck_steps, tools.size, tools.size), ckDuration(durationMs))
        done -> stringResource(R.string.ck_work_done_short, ckDuration(durationMs))
        else -> ckHeadLabel(running?.name)
    }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 18.dp)) {
        WorkHead(if (done) WorkState.Done else WorkState.Running, label, collapsed = !expanded, onToggle = onToggle, onLongPress = onLongPress)
        AnimatedVisibility(expanded, enter = expandVertically(DshMotion.smooth()) + fadeIn(), exit = shrinkVertically(DshMotion.snappy()) + fadeOut()) {
            val steps = mutableListOf<Pair<StepState, @Composable () -> Unit>>()
            items.forEach { m ->
                val t = parseTool(m)
                when {
                    // Вызов, который ждёт одобрения, показан карточкой решения ниже.
                    t != null && approval != null && t.callId.isNotBlank() && t.callId == approval.callId && t.status == "running" -> Unit
                    t != null -> steps += when (t.status) {
                        "running" -> StepState.Running
                        "error" -> StepState.Warn
                        else -> StepState.Ok
                    } to {
                        val shell = t.name.contains("bash") || t.name.contains("shell") || t.name.contains("exec")
                        val summary = remember(t) { toolSummary(t.name, t.args, t.body, t.status) }
                        decided[t.callId]?.let { (allowed, destructive) -> DecisionResult(allowed, destructive) }
                        ToolCard(
                            icon = toolGlyph(t.name),
                            title = ckToolTitle(t.name),
                            sub = summary,
                            log = if (shell) remember(t.body) { t.body.lines().filter { it.isNotBlank() }.takeLast(6) } else emptyList(),
                            error = t.status == "error",
                            onClick = { onOpenDetail(ToolDetailData(t.callId, t.name, t.status, t.args, t.body)) },
                        ) {
                            // tool.call.toolview: плагин может нарисовать результат инструмента сам.
                            com.deepseek.dsh.plugin.nativehost.NativeSlot(
                                slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.TOOL_VIEW_SHEET,
                                spec = kotlinx.serialization.json.buildJsonObject {
                                    put("name", kotlinx.serialization.json.JsonPrimitive(t.name))
                                    put("status", kotlinx.serialization.json.JsonPrimitive(t.status))
                                    put("args", kotlinx.serialization.json.JsonPrimitive(t.args))
                                },
                            )
                            if (t.name.contains("ask_user_question") && t.status == "running") askContent(t)
                        }
                    }
                    m.role == MessageRole.ASSISTANT && m.content.contains('\u0001') -> {
                        val r = m.content.substringBefore('\u0001')
                        if (r.isNotBlank()) steps += StepState.Plain to { ReasoningStep(r, translate) }
                        val a = m.content.substringAfter('\u0001')
                        if (a.isNotBlank()) steps += StepState.Plain to { Text(a, style = ckBody(15.sp, 22.sp), color = Dsh.colors.t1, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    m.role == MessageRole.ASSISTANT && m.content.isNotBlank() ->
                        steps += StepState.Plain to { Text(m.content, style = ckBody(15.sp, 22.sp), color = Dsh.colors.t1, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
            if (!done && liveReasoning.isNotBlank()) steps += StepState.Running to { ReasoningStep(liveReasoning, null) }
            if (approval != null && !done) steps += if (approval.tier == ApprovalTier.DESTRUCTIVE) {
                StepState.Danger to { DangerAskCard(approval, dangerInfo, onOpenDanger) }
            } else {
                StepState.Running to { ApproveCard(approval) { allowed -> onDecided(approval, allowed) } }
            }
            if (steps.isNotEmpty()) Steps(steps)
        }
    }
}

/** Служебное сообщение (сжатие контекста, смена модели): мелко и по центру. */
@Composable
internal fun SystemNote(text: String) {
    Text(
        text, style = ckBody(12.5.sp, 17.sp), color = Dsh.colors.t3,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Вопрос агента (ask_user_question) — карточка в стиле `.approve`: метка «Вопрос»,
 * формулировка и варианты; выбор сразу уходит агенту.
 */
@Composable
internal fun AskCard(callId: String, body: String, onAnswer: (callId: String, questionId: String, selected: String) -> Unit) {
    val c = Dsh.colors
    val questions = remember(body) { parseAskQuestions(body) }
    if (questions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        questions.forEach { q ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1)
                    .border(1.dp, androidx.compose.ui.graphics.lerp(c.line, c.accent, 0.35f), RoundedCornerShape(18.dp)).padding(14.dp),
            ) {
                Tier(q.header.ifBlank { stringResource(R.string.ck_question) }, DshGlyphs.Msg)
                Text(q.question, style = ckDisplay(16.sp, 20.sp), color = c.t1, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                q.options.forEach { o ->
                    ModelRadio(o.label, o.description.ifBlank { null }, false) { if (callId.isNotBlank()) onAnswer(callId, q.id, o.label) }
                }
            }
        }
    }
}

/** Метка уровня `.tier`: 11,5 / 600, прописные, на accent-soft (или danger-soft). */
@Composable
internal fun Tier(text: String, icon: ImageVector, danger: Boolean = false) {
    val c = Dsh.colors
    val fg = if (danger) c.danger else c.accent
    Row(
        Modifier.clip(CircleShape).background(if (danger) c.dangerSoft else c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text.uppercase(), style = ckBody(11.5.sp, 14.sp, FontWeight.SemiBold).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.04f, androidx.compose.ui.unit.TextUnitType.Em)), color = fg)
    }
}

/** Лист деталей шага: что за инструмент, его аргументы и полный вывод. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.ToolDetailBody(detail: ToolDetailData) {
    val c = Dsh.colors
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.s2), contentAlignment = Alignment.Center) {
            Icon(toolGlyph(detail.name), null, tint = c.t2, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ckToolTitle(detail.name), style = ckDisplay(19.sp, 24.sp), color = c.t1)
            Text(
                stringResource(
                    when (detail.status) {
                        "running" -> R.string.tool_detail_running
                        "error" -> R.string.tool_detail_error
                        else -> R.string.tool_detail_done
                    },
                ),
                style = ckBody(12.5.sp, 16.sp), color = if (detail.status == "error") c.danger else c.t3,
            )
        }
    }
    val args = remember(detail.args) {
        runCatching {
            PrettyArgsJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseHostJson(detail.args))
        }.getOrElse { detail.args }
    }
    if (args.isNotBlank()) CodeSection(stringResource(R.string.tool_detail_args), args)
    if (detail.body.isNotBlank()) CodeSection(
        stringResource(if (detail.status == "error") R.string.tool_detail_error_output else R.string.tool_detail_output),
        detail.body,
    )
}

@Composable
private fun CodeSection(label: String, text: String) {
    val c = Dsh.colors
    Text(
        label.uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.06f, androidx.compose.ui.unit.TextUnitType.Em)), color = c.t3,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text, style = ckMono(12.sp, 18.sp), color = c.t2,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.codeBg).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp),
        )
    }
}

/**
 * Оценка ответа (плагин message-feedback): «полезно / не полезно» такими же кнопками,
 * как «Копировать»; после оценки — заметка в небольшом поле под ответом.
 */
@Composable
internal fun MessageFeedbackActions(
    current: com.deepseek.dsh.core.model.MessageFeedbackInfo?,
    onRating: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    val c = Dsh.colors
    var noteOpen by remember { mutableStateOf(false) }
    ChatIconBtn(DshGlyphs.Check, stringResource(R.string.feedback_helpful), tint = if (current?.rating == "positive") c.accent else c.t3, iconSize = 18.dp) { onRating("positive") }
    ChatIconBtn(DshGlyphs.X, stringResource(R.string.feedback_not_helpful), tint = if (current?.rating == "negative") c.danger else c.t3, iconSize = 18.dp) { onRating("negative") }
    if (current != null) ChatIconBtn(DshGlyphs.Pencil, stringResource(if (current.note.isNullOrBlank()) R.string.feedback_add_note else R.string.feedback_edit_note), tint = c.t3, iconSize = 18.dp) { noteOpen = !noteOpen }
    if (noteOpen) {
        var draft by remember(current?.note) { mutableStateOf(current?.note.orEmpty()) }
        androidx.compose.ui.window.Popup(onDismissRequest = { noteOpen = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
            Column(
                Modifier.widthIn(max = 320.dp).floatShadow(RoundedCornerShape(18.dp), c.isDark).clip(RoundedCornerShape(18.dp)).background(c.s1)
                    .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.feedback_note_title), style = ckBody(14.5.sp, 18.sp, FontWeight.Medium), color = c.t1)
                androidx.compose.foundation.text.BasicTextField(
                    draft, { draft = it }, textStyle = ckBody(15.sp, 20.sp).copy(color = c.t1),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), minLines = 2,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.s2).padding(10.dp),
                    decorationBox = { inner -> if (draft.isEmpty()) Text(stringResource(R.string.feedback_note_hint), style = ckBody(15.sp, 20.sp), color = c.t3); inner() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(stringResource(R.string.queue_cancel)) { noteOpen = false }
                    SmallAction(stringResource(R.string.queue_save), accent = true) { noteOpen = false; onNote(draft.trim()) }
                }
            }
        }
    }
}

/** Аргументы инструмента в листе деталей — с отступами; разбор с пределом глубины (`parseHostJson`). */
private val PrettyArgsJson = kotlinx.serialization.json.Json { prettyPrint = true }
