package com.deepseek.dsh.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.pressScale

// Части главного экрана чата из прототипа: `.topbar`, `.home`, `.dock`, `.composer`.

/** Текст как в CSS: межстрочный интервал делится поровну сверху и снизу. */
private val CssLeading = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

fun ckBody(size: TextUnit, lh: TextUnit = size * 1.3f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.body, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)

internal fun ckDisplay(size: TextUnit, lh: TextUnit = size * 1.25f, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = DshFonts.display, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)

internal fun ckMono(size: TextUnit, lh: TextUnit = size * 1.4f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.mono, fontWeight = weight, fontSize = size, lineHeight = lh, lineHeightStyle = CssLeading)

/** Тень плавающих элементов `--shadow-float`. */
internal fun Modifier.floatShadow(shape: androidx.compose.ui.graphics.Shape, dark: Boolean) = shadow(
    elevation = 14.dp, shape = shape, clip = false,
    ambientColor = Color(0xFF1C1A46).copy(alpha = if (dark) 0.5f else 0.10f),
    spotColor = Color(0xFF1C1A46).copy(alpha = if (dark) 0.5f else 0.14f),
)

/** Круглая кнопка `.iconbtn` 44 dp. */
@Composable
fun ChatIconBtn(icon: ImageVector, description: String, tint: Color = Dsh.colors.t1, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    val ia = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(ia, 0.9f).clip(CircleShape)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize)) }
}

/**
 * Шапка чата `.topbar`: меню, название сессии с папкой проекта, новая сессия.
 * Когда лента прокручена, под шапкой появляется «стекло» и линия.
 *
 * @param extra дополнительные кнопки (задачи, команда, боковая панель) — перед «Новой сессией».
 */
@Composable
fun ChatTopBar(
    title: String,
    workspace: String?,
    live: Boolean,
    scrolled: Boolean,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val c = Dsh.colors
    val bg by animateColorAsState(if (scrolled) c.glass.compositeOn(c.bg) else c.bg, tween(250), label = "tb")
    val line by animateColorAsState(if (scrolled) c.line else Color.Transparent, tween(250), label = "tbl")
    Row(
        modifier.fillMaxWidth().background(bg)
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatIconBtn(DshGlyphs.Menu, stringResource(R.string.ck_menu), onClick = onMenu)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title.ifBlank { stringResource(R.string.ck_new_session) },
                style = ckDisplay(16.sp, 19.2.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!workspace.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (live) c.ok else c.t3))
                Text(workspace, style = ckBody(12.sp, 14.4.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        extra()
        ChatIconBtn(DshGlyphs.New, stringResource(R.string.ck_new_session), onClick = onNew)
    }
}

/** Полупрозрачный цвет поверх фона — непрозрачный результат. */
internal fun Color.compositeOn(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

/** Карточка «продолжить» `.resume`: сессия, в которой агент сейчас работает. */
data class ChatResume(val sessionId: String, val title: String, val step: Int? = null, val steps: Int? = null)

/** Появление снизу с задержкой — каскад элементов главного экрана. */
@Composable
private fun Modifier.riseIn(index: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(40L + index * 45L); p.animateTo(1f, DshMotion.smooth()) }
    return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 14.dp.toPx() }
}

/**
 * Главный экран новой сессии `.home`: приветствие, карточка работающей сессии, «Начать».
 *
 * @param name имя из профиля аккаунта; без него — только «Доброй ночи».
 * @param onCard подставить запрос карточки в поле ввода.
 */
@Composable
fun ChatHome(
    name: String?,
    resume: ChatResume?,
    onResume: (ChatResume) -> Unit,
    onCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    hour: Int = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) },
) {
    val c = Dsh.colors
    val greet = stringResource(
        when {
            hour < 5 -> R.string.chat_greet_night
            hour < 12 -> R.string.chat_greet_morning
            hour < 18 -> R.string.chat_greet_day
            else -> R.string.chat_greet_evening
        },
    )
    var i = 0
    Column(modifier.padding(top = 14.dp)) {
        // `.greet`
        Row(Modifier.riseIn(i++), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier.size(42.dp)
                    .shadow(11.dp, shape, ambientColor = c.accent.copy(alpha = 0.35f), spotColor = c.accent.copy(alpha = 0.35f))
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(c.accent2, c.accent), start = Offset.Zero, end = Offset(42f * 3, 42f * 3 * 1.4f))),
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Dsh, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Column {
                Text(
                    if (name.isNullOrBlank()) greet else stringResource(R.string.ck_greet_name, greet, name),
                    style = ckDisplay(21.sp, 25.2.sp).copy(letterSpacing = (-0.015).em), color = c.t1,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.chat_greet_sub), style = ckBody(15.sp, 19.5.sp), color = c.t2, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (resume != null) {
            Spacer(Modifier.height(22.dp))
            val ia = remember { MutableInteractionSource() }
            Row(
                Modifier.riseIn(i++).fillMaxWidth().pressScale(ia, 0.98f).clip(RoundedCornerShape(20.dp)).background(c.s1)
                    .border(1.dp, c.line, RoundedCornerShape(20.dp))
                    .clickable(ia, ripple(), role = Role.Button) { onResume(resume) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Pulse()
                Column(Modifier.weight(1f)) {
                    Text(resume.title, style = ckBody(14.5.sp, 17.5.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (resume.step != null && resume.steps != null) stringResource(R.string.ck_resume_step, resume.step, resume.steps)
                        else stringResource(R.string.ck_resume_working),
                        style = ckBody(12.5.sp, 15.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        // `.section-label` (margin-bottom −16 → до карточек 6)
        Text(
            stringResource(R.string.chat_start_label).uppercase(),
            style = ckBody(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = c.t3,
            modifier = Modifier.riseIn(i++),
        )
        Spacer(Modifier.height(6.dp))
        val cards = listOf(
            Triple(DshGlyphs.Book, R.string.chat_card_explore, R.string.chat_card_explore_sub) to R.string.chat_card_explore_prompt,
            Triple(DshGlyphs.Bug, R.string.chat_card_fix, R.string.chat_card_fix_sub) to R.string.chat_card_fix_prompt,
            Triple(DshGlyphs.Code, R.string.chat_card_script, R.string.chat_card_script_sub) to R.string.chat_card_script_prompt,
            Triple(DshGlyphs.Flow, R.string.chat_card_auto, R.string.chat_card_auto_sub) to R.string.chat_card_auto_prompt,
        )
        cards.chunked(2).forEachIndexed { r, pair ->
            if (r > 0) Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (info, promptRes) ->
                    val prompt = stringResource(promptRes)
                    StartCard(info.first, stringResource(info.second), stringResource(info.third), Modifier.weight(1f).fillMaxHeight().riseIn(i++)) { onCard(prompt) }
                }
            }
        }
    }
}

/** Карточка `.card`: иконка 32, заголовок 14,5/500, подпись 12,5. */
@Composable
private fun StartCard(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.heightIn(min = 108.dp).pressScale(ia, 0.97f).clip(shape).background(c.s1).border(1.dp, c.line, shape)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick)
            .padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Text(title, style = ckBody(14.5.sp, 18.85.sp, FontWeight.Medium), color = c.t1)
        Text(sub, style = ckBody(12.5.sp, 16.9.sp), color = c.t3, modifier = Modifier.padding(top = 0.dp))
    }
}

/** `.pulse`: точка 10 dp с расходящимся кольцом. */
@Composable
internal fun Pulse(color: Color = Dsh.colors.accent) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    Box(Modifier.size(10.dp).drawBehind {
        val s = 0.5f + 1.1f * p
        val r = (size.minDimension / 2 + 5.dp.toPx()) * s
        drawCircle(color.copy(alpha = 0.7f * (1f - p)), radius = r - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(color)
    })
}

/**
 * Док `.dock`: «Файлы» с числом изменённых файлов и «Терминал»; при прокрутке вниз
 * подписи прячутся (`.compact`).
 */
@Composable
fun ChatDock(
    filesBadge: Int,
    compact: Boolean,
    onFiles: () -> Unit,
    onTerminal: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    Row(
        modifier.floatShadow(CircleShape, c.isDark).clip(CircleShape).background(c.glass.compositeOn(c.bg))
            .border(1.dp, c.glassLine, CircleShape).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DockBtn(DshGlyphs.Folder, stringResource(R.string.ck_files), compact, filesBadge.takeIf { it > 0 }, onFiles)
        if (onTerminal != null) DockBtn(DshGlyphs.Term, stringResource(R.string.ck_terminal), compact, null, onTerminal)
    }
}

@Composable
private fun DockBtn(icon: ImageVector, label: String, compact: Boolean, badge: Int?, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val show by animateFloatAsState(if (compact) 0f else 1f, DshMotion.snappy(), label = "lbl")
    Box {
        Row(
            Modifier.height(44.dp).clip(CircleShape).clickable(ia, ripple(), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(start = 11.dp, end = if (compact) 11.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((7 * show).dp),
        ) {
            Icon(icon, null, tint = c.t2, modifier = Modifier.size(18.dp))
            if (show > 0.01f) Text(
                label, style = ckBody(13.5.sp, 17.sp, FontWeight.Medium), color = c.t2, maxLines = 1, softWrap = false,
                modifier = Modifier.widthIn(max = (90 * show).dp).graphicsLayer { alpha = show },
            )
            if (badge != null && !compact) Badge(badge)
        }
        if (badge != null && compact) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }) { Badge(badge) }
    }
}

/** `.badge`: 17 dp, акцент. */
@Composable
internal fun Badge(n: Int) {
    val c = Dsh.colors
    Box(Modifier.heightIn(min = 17.dp).widthIn(min = 17.dp).clip(RoundedCornerShape(9.dp)).background(c.accent).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(n.toString(), style = ckBody(10.5.sp, 13.sp, FontWeight.SemiBold), color = c.onAccent, textAlign = TextAlign.Center)
    }
}

/** Чип над полем ввода `.chip`: 36 dp; `accent` — модель. */
@Composable
fun ComposerChip(text: String, icon: ImageVector?, accent: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val ink = if (accent) lerp(c.t1, c.accent, 0.82f) else c.t2
    Row(
        Modifier.height(36.dp).pressScale(ia, 0.95f).clip(CircleShape).background(if (accent) c.accentSoft else c.s2)
            .clickable(ia, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(14.dp))
        Text(text, style = ckBody(13.sp, 16.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (chevron) Icon(DshGlyphs.Chev, null, tint = ink, modifier = Modifier.size(14.dp))
    }
}

/** Состояние круглой кнопки справа `.send`: микрофон, отправить, остановить. */
enum class SendMode { Mic, Send, Stop, Idle }

/**
 * Поле ввода `.composer`: «стеклянная» карточка 28 dp; сверху чипы (модель, режим),
 * когда поле в фокусе или в нём есть текст; слева «+», справа микрофон / отправка / стоп.
 *
 * @param above содержимое над строкой ввода внутри карточки (вложения, команда).
 */
@Composable
fun ChatComposer(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    mode: SendMode,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    chipsOpen: Boolean = false,
    onFocus: (Boolean) -> Unit = {},
    chips: @Composable RowScope.() -> Unit = {},
    above: @Composable ColumnScope.() -> Unit = {},
    leading: @Composable () -> Unit = {},
) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().floatShadow(shape, c.isDark).clip(shape).background(c.glass.compositeOn(c.bg))
            .border(1.dp, c.glassLine, shape).padding(6.dp),
    ) {
        // У свёрнутых `.chips` остаётся отступ 4 + 2.
        if (!chipsOpen) Spacer(Modifier.height(6.dp))
        AnimatedVisibility(chipsOpen, enter = expandVertically(DshMotion.snappy()) + fadeIn(), exit = shrinkVertically(DshMotion.snappy()) + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = chips,
            )
        }
        above()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val ia = remember { MutableInteractionSource() }
            val plusDesc = stringResource(R.string.ck_plus)
            Box(
                Modifier.size(42.dp).pressScale(ia, 0.9f).clip(CircleShape).background(c.s2)
                    .clickable(ia, ripple(), role = Role.Button, onClick = onPlus).semantics { contentDescription = plusDesc },
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Plus, null, tint = c.t1, modifier = Modifier.size(22.dp)) }
            leading()
            val fieldDesc = stringResource(R.string.ck_field)
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = ckBody(16.sp, 22.4.sp).copy(color = c.t1),
                cursorBrush = SolidColor(c.accent),
                maxLines = 5,
                modifier = Modifier.weight(1f).heightIn(min = 42.dp, max = 120.dp)
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = fieldDesc },
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                        if (text.isEmpty()) Text(placeholder, style = ckBody(16.sp, 22.4.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            SendButton(mode, onSend)
        }
    }
}

/** `.send`: тёмный круг с микрофоном; акцент со стрелкой, когда есть что отправить; стоп во время работы. */
@Composable
private fun SendButton(mode: SendMode, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (mode == SendMode.Send) c.accent else c.t1, DshMotion.smooth(), label = "send")
    val fg = if (mode == SendMode.Send) c.onAccent else c.bg
    val desc = stringResource(
        when (mode) {
            SendMode.Mic -> R.string.ck_mic
            SendMode.Send, SendMode.Idle -> R.string.ck_send
            SendMode.Stop -> R.string.ck_stop
        },
    )
    Box(
        Modifier.size(42.dp).pressScale(ia, 0.9f).clip(CircleShape).background(bg)
            .clickable(ia, ripple(), enabled = mode != SendMode.Idle, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        GlyphSwap(DshGlyphs.Mic, mode == SendMode.Mic, fg)
        GlyphSwap(DshGlyphs.Up, mode == SendMode.Send || mode == SendMode.Idle, if (mode == SendMode.Idle) fg.copy(alpha = 0.45f) else fg)
        GlyphSwap(DshGlyphs.Stop, mode == SendMode.Stop, fg)
    }
}

/** Иконка, которая появляется пружиной из 0,4 с поворотом (−40°) и уходит в 0,4. */
@Composable
private fun GlyphSwap(icon: ImageVector, on: Boolean, tint: Color) {
    val p by animateFloatAsState(if (on) 1f else 0f, DshMotion.lively(), label = "glyph")
    if (p < 0.01f && !on) return
    Icon(
        icon, null, tint = tint,
        modifier = Modifier.size(22.dp).graphicsLayer {
            val s = 0.4f + 0.6f * p
            scaleX = s; scaleY = s; alpha = p.coerceIn(0f, 1f); rotationZ = if (on) -40f * (1f - p) else 0f
        },
    )
}

/** Затемнение снизу `.fade`: лента уходит под поле ввода. */
@Composable
fun ChatFade(modifier: Modifier = Modifier) {
    val bg = Dsh.colors.bg
    Box(
        modifier.fillMaxWidth().height(200.dp).background(
            Brush.verticalGradient(0f to Color.Transparent, 0.45f to bg.copy(alpha = 0.82f), 0.8f to bg, 1f to bg),
        ),
    )
}

/** Есть ли у телефона интернет (Android проверил доступ), с подпиской на изменения. */
@Composable
fun rememberOnline(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cm = remember { ctx.getSystemService(android.net.ConnectivityManager::class.java) }
    fun now(): Boolean = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
    val state = remember { androidx.compose.runtime.mutableStateOf(now()) }
    androidx.compose.runtime.DisposableEffect(cm) {
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { state.value = true }
            override fun onLost(network: android.net.Network) { state.value = now() }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return state.value
}

/** Состояние плашки `.netbar`. */
enum class NetState { Offline, Checking, Back }

/**
 * Плашка «Нет соединения» под шапкой: что происходит и «Повторить»;
 * когда сеть вернулась — зелёное «Снова в сети» на полторы секунды.
 */
@Composable
fun NetBar(state: NetState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(16.dp)
    val ok = state == NetState.Back
    Row(
        modifier.fillMaxWidth().floatShadow(shape, c.isDark).clip(shape).background(c.glass.compositeOn(c.bg)).border(1.dp, c.glassLine, shape)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(if (ok) c.okSoft else c.warnSoft), contentAlignment = Alignment.Center) {
            Icon(if (ok) DshGlyphs.Check else DshGlyphs.Globe, null, tint = if (ok) c.ok else c.warn, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(when (state) { NetState.Offline -> R.string.ck_net_off; NetState.Checking -> R.string.ck_net_checking; NetState.Back -> R.string.ck_net_back }),
                style = ckBody(14.sp, 18.2.sp), color = c.t1,
            )
            Text(
                stringResource(when (state) { NetState.Offline -> R.string.ck_net_off_sub; NetState.Checking -> R.string.ck_net_checking_sub; NetState.Back -> R.string.ck_net_back_sub }),
                style = ckBody(12.sp, 15.6.sp), color = c.t3,
            )
        }
        if (!ok) Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(c.s2).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.ck_retry), style = ckBody(13.5.sp, 17.sp, FontWeight.Medium), color = c.accent) }
    }
}

/**
 * Вложения над строкой ввода `.atts`: плитка 54 dp — миниатюра фото или значок файла,
 * имя и тип; круглая «×» убирает.
 */
@Composable
internal fun AttachmentRail(attachments: List<ChatAttachment>, onRemove: (ChatAttachment) -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { a ->
            Box {
                Row(
                    Modifier.height(54.dp).widthIn(max = 200.dp).clip(RoundedCornerShape(14.dp)).background(c.s2).padding(start = 6.dp, end = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val thumb = rememberThumb(a.localPath.takeIf { a.isImageAttachment() })
                    if (thumb != null) {
                        androidx.compose.foundation.Image(
                            thumb, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)),
                        )
                    } else Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(c.s1), contentAlignment = Alignment.Center) {
                        Icon(if (a.isImageAttachment()) DshGlyphs.Image else DshGlyphs.File, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(a.displayName, style = ckBody(13.sp, 16.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                        Text(
                            if (a.isImageAttachment()) stringResource(R.string.ck_att_image) else listOf(stringResource(R.string.ck_att_file), a.formattedSize()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = ckBody(12.5.sp, 16.sp), color = c.t2, maxLines = 1,
                        )
                    }
                }
                // `.x`: круг 20 dp, касаемая зона 40.
                val desc = stringResource(R.string.ck_att_remove)
                Box(
                    Modifier.align(Alignment.TopEnd).size(40.dp).graphicsLayer { translationX = 10.dp.toPx(); translationY = -6.dp.toPx() }
                        .clip(CircleShape).clickable(role = Role.Button) { onRemove(a) }.semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(c.t3), contentAlignment = Alignment.Center) {
                        Icon(DshGlyphs.X, null, tint = c.bg, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

/** Миниатюра фото ~126 px по меньшей стороне, читается в фоне. */
@Composable
private fun rememberThumb(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var bmp by remember(path) { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, o)
                var s = 1
                while (minOf(o.outWidth, o.outHeight) / (s * 2) >= 126) s *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

/** Плашка режима плана над полем ввода: агент сначала покажет шаги и подождёт «да». */
@Composable
internal fun PlanModeBar(onExit: () -> Unit) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().clip(shape).background(c.accentSoft.compositeOn(c.bg))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DshGlyphs.Tasks, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.plan_banner_title), style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.accent)
            Text(stringResource(R.string.plan_banner_desc), style = ckBody(12.5.sp, 16.sp), color = c.t2, maxLines = 2)
        }
        ChatIconBtn(DshGlyphs.X, stringResource(R.string.plan_banner_exit), tint = c.t2, iconSize = 18.dp, onClick = onExit)
    }
}

/**
 * Цель и план сессии (goal / todos Host): свёрнуто — текущий шаг и полоса прогресса,
 * развёрнуто — все шаги с отметками.
 */
@Composable
internal fun PlanDock(goal: com.deepseek.dsh.core.model.GoalInfo?, todos: List<com.deepseek.dsh.core.model.TodoEntryInfo>) {
    val c = Dsh.colors
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status != "completed" }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().floatShadow(shape, c.isDark).clip(shape)
            .background(c.glass.compositeOn(c.bg)).border(1.dp, c.glassLine, shape)
            .clickable(role = Role.Button) { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (goal != null) DshGlyphs.Flow else DshGlyphs.Tasks, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Text(
                goal?.objective ?: (current?.content ?: stringResource(R.string.ck_plan_done)),
                style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (todos.isNotEmpty()) Text(stringResource(R.string.ck_plan_progress, done, todos.size), style = ckBody(12.5.sp, 16.sp), color = c.t3)
            Icon(DshGlyphs.Chev, null, tint = c.t3, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (open) 180f else 0f })
        }
        if (todos.isNotEmpty()) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.s3)) {
            Box(Modifier.fillMaxWidth(done / todos.size.toFloat()).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        }
        goal?.blockedReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = ckBody(12.5.sp, 16.sp), color = c.warn, modifier = Modifier.padding(top = 6.dp)) }
        AnimatedVisibility(open && todos.isNotEmpty(), enter = expandVertically(DshMotion.snappy()) + fadeIn(), exit = shrinkVertically(DshMotion.snappy()) + fadeOut()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                todos.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val st = when (t.status) { "completed" -> c.ok; "in_progress" -> c.accent; else -> c.t3 }
                        Box(Modifier.size(16.dp).clip(CircleShape).background(if (t.status == "completed") st else Color.Transparent).border(1.5.dp, st, CircleShape), contentAlignment = Alignment.Center) {
                            if (t.status == "completed") Icon(DshGlyphs.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                            if (t.status == "in_progress") Box(Modifier.size(6.dp).clip(CircleShape).background(st))
                        }
                        Text(t.content, style = ckBody(13.5.sp, 18.sp), color = if (t.status == "completed") c.t3 else c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
