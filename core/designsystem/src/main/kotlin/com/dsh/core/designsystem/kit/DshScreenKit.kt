package com.dsh.core.designsystem.kit

import androidx.compose.ui.graphics.compositeOver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshMotion
import com.dsh.core.designsystem.DshSwitch
import com.dsh.core.designsystem.pressScale
import kotlinx.coroutines.delay

/*
 * Каркас экранов «Плагины», «Автоматизация» и «Настройки» по прототипу
 * (`docs/design/dsh-prototype.html`): стек страниц с пружинным переходом,
 * шапка `.sbar`, группы `.gl`/`.gr`, строки `.st-row`, листы `.sheet` и тосты.
 * Размеры — один к одному из CSS прототипа (px → dp).
 */

// ---------- Стек страниц ----------

/** Стек вложенных страниц одного экрана: push — вправо, pop — влево, как `.fs-view` в прототипе. */
@Stable
class PageStack<T : Any>(root: T) {
    private val items = mutableStateListOf(root)
    /** Номер перехода и его направление: для анимации AnimatedContent. */
    var forward by mutableStateOf(true)
        private set
    val top: T get() = items.last()
    val depth: Int get() = items.size
    val entries: List<T> get() = items

    fun push(page: T) { forward = true; items.add(page) }

    /** Назад; false — уже на корне. */
    fun pop(): Boolean {
        if (items.size <= 1) return false
        forward = false
        items.removeAt(items.lastIndex)
        return true
    }

    /** Заменить вершину без анимации назад (например, «Дублировать» → новая). */
    fun replaceTop(page: T) { forward = true; items[items.lastIndex] = page }

    /** Вернуться к корню и положить сверху [page] (или ничего). */
    fun resetTo(root: T) { forward = false; items.clear(); items.add(root) }
}

/**
 * Экран со стеком страниц: шапка + тело с переходом между страницами.
 * Системное «Назад» сначала закрывает вложенную страницу, потом экран ([onClose]).
 */
@Composable
fun <T : Any> DshStackScreen(
    stack: PageStack<T>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bar: @Composable (page: T, onBack: () -> Unit) -> Unit,
    sheetOpen: Boolean = false,
    overlay: @Composable () -> Unit = {},
    page: @Composable (T) -> Unit,
) {
    val back: () -> Unit = { if (!stack.pop()) onClose() }
    BackHandler(onBack = back)
    // Под листом экран отъезжает вглубь: масштаб .94 и скругление 28, как `go(under(), …)` в прототипе.
    val under by androidx.compose.animation.core.animateFloatAsState(if (sheetOpen) 1f else 0f, DshMotion.smooth(), label = "under")
    Box(modifier.fillMaxSize().background(if (under > 0f) Color.Black else Dsh.colors.bg)) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val sc = 1f - 0.06f * under
                    scaleX = sc; scaleY = sc
                    shape = RoundedCornerShape((28 * under).dp)
                    clip = under > 0f
                }
                .background(Dsh.colors.bg),
        ) {
            bar(stack.top, back)
            AnimatedContent(
                targetState = stack.entries.size to stack.top,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { pageTransition(stack.forward) },
                contentKey = { it.first to it.second },
                label = "pages",
            ) { (_, p) -> page(p) }
        }
        overlay()
    }
}

private fun pageTransition(forward: Boolean): ContentTransform {
    val spec = DshMotion.smooth<androidx.compose.ui.unit.IntOffset>()
    val fade = DshMotion.smooth<Float>()
    return if (forward) {
        (slideInHorizontally(spec) { it }) togetherWith
            (slideOutHorizontally(spec) { -(it * 0.28f).toInt() } + fadeOut(fade, targetAlpha = 0.6f))
    } else {
        (slideInHorizontally(spec) { -(it * 0.28f).toInt() } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
            (slideOutHorizontally(spec) { it })
    }.apply { targetContentZIndex = if (forward) 1f else -1f }
}

/** Появление содержимого при открытии экрана: снизу на 10 dp с задержкой по порядку (`delay 40 + k·35`). */
val LocalRise = compositionLocalOf { false }

@Composable
fun Modifier.rise(index: Int, step: Int = 35, start: Int = 40): Modifier {
    if (!LocalRise.current) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((start + index * step).toLong())
        a.animateTo(1f, DshMotion.smooth())
    }
    val px = with(LocalDensity.current) { 10.dp.toPx() }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * px }
}

// ---------- Шапка ----------

/**
 * Шапка `.sbar`: «Назад», заголовок 16/600 с подписью 12 и действия справа.
 * Отступы: сверху 10 + системная панель, по бокам 8, снизу 6, промежуток 2.
 */
@Composable
fun SBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backDescription: String = "Назад",
    live: Boolean? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onBack != null) IconBtn(DshGlyphs.Left, backDescription, onClick = onBack) else Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(
                title,
                style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
                color = Dsh.colors.t1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                // `.title .live`: точка 6 dp перед подписью — зелёная, когда подключено
                if (live != null) Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(if (live) Dsh.colors.ok else Dsh.colors.t3))
                Text(subtitle, style = small(12.sp), color = Dsh.colors.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** Круглая кнопка 44 dp с иконкой 22 (`.iconbtn`). */
@Composable
fun IconBtn(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Dsh.colors.t1, size: Dp = 44.dp, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .clickable(interaction, ripple(bounded = true), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize)) }
}

// ---------- Тело страницы ----------

/** Прокручиваемая страница `.fs-view`: отступы 4 / 16 / 28. */
@Composable
fun FsView(modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(Dsh.colors.bg)
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        content = content,
    )
}

fun small(size: TextUnit = 12.5.sp, lh: TextUnit = 16.sp, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.body, fontWeight = weight, fontSize = size, lineHeight = lh)

fun body(size: TextUnit = 15.sp, lh: TextUnit = 19.5.sp, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DshFonts.body, fontWeight = weight, fontSize = size, lineHeight = lh)

fun display(size: TextUnit, lh: TextUnit = size * 1.25f, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = DshFonts.display, fontWeight = weight, fontSize = size, lineHeight = lh)

fun mono(size: TextUnit = 13.5.sp, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = DshFonts.mono, fontWeight = weight, fontSize = size, lineHeight = size * 1.3f)

/** Подпись группы `.glabel`: 12, прописные, разрядка .06em, отступы 16 / 6 / 8. */
@Composable
fun GLabel(text: String, modifier: Modifier = Modifier, top: Dp = 16.dp) {
    Text(
        text.uppercase(),
        style = small(12.sp, 16.sp).copy(letterSpacing = 0.06.em),
        color = Dsh.colors.t3,
        modifier = modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = top, bottom = 8.dp).semantics { heading() },
    )
}

/** Касаемая зона [min] по высоте, не раздвигающая строку (как `margin: -12px 0` у `.sec-btn`). */
fun Modifier.touchOverflow(min: Dp = 40.dp): Modifier = this.then(
    Modifier.layout { m, c ->
        val p = m.measure(c.copy(minHeight = 0))
        val h = maxOf(p.height, min.roundToPx())
        val inner = m.measure(c.copy(minHeight = h, maxHeight = h))
        layout(inner.width, p.height) { inner.place(0, (p.height - h) / 2) }
    },
)

/** Заголовок раздела `.fs-sec`: слева подпись, справа число или своё действие. */
@Composable
fun FsSec(text: String, modifier: Modifier = Modifier, count: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = small(12.sp, 16.sp).copy(letterSpacing = 0.06.em),
            color = Dsh.colors.t3,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) trailing() else if (count != null) Text(count, style = small(12.sp, 16.sp), color = Dsh.colors.t3)
    }
}

/** Пустое состояние `.fs-empty`. */
@Composable
fun FsEmpty(text: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = body(14.5.sp, 21.sp), color = Dsh.colors.t3, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = small(), color = Dsh.colors.t3, textAlign = TextAlign.Center)
    }
}

/** Подсказка под группой `.sub`. */
@Composable
fun SubNote(text: String, modifier: Modifier = Modifier, top: Dp = 8.dp) {
    Text(text, style = small(12.5.sp, 18.sp), color = Dsh.colors.t3, modifier = modifier.padding(start = 6.dp, end = 6.dp, top = top))
}

/** Строка поиска `.fs-search`: 44 dp, радиус 14, фон s2. */
@Composable
fun FsSearch(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Dsh.colors.s2).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DshGlyphs.Search, null, tint = Dsh.colors.t3, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = body(15.sp), color = Dsh.colors.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = body(15.sp).copy(color = Dsh.colors.t1),
                cursorBrush = SolidColor(Dsh.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (value.isNotEmpty()) IconBtn(DshGlyphs.X, "Очистить", size = 40.dp, iconSize = 18.dp, tint = Dsh.colors.t3) { onValueChange("") }
    }
}

// ---------- Группы и строки ----------

/** Сборщик строк группы: между строками рисуется линия `.gr + .gr`. */
class GlScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

/** Группа `.gl`: фон s1, обводка line, радиус 18. */
@Composable
fun Gl(modifier: Modifier = Modifier, radius: Dp = 18.dp, build: GlScope.() -> Unit) {
    val scope = GlScope().apply(build)
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Dsh.colors.s1).border(BorderStroke(1.dp, Dsh.colors.line), shape).padding(1.dp),
    ) {
        scope.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Dsh.colors.line))
            r()
        }
    }
}

enum class Tone { Plain, Ok, Warn, Red }

/** Плитка иконки `.gic`: 30, радиус 9, фон s2 или мягкий цвет состояния. */
@Composable
fun Gic(icon: ImageVector, tone: Tone = Tone.Plain) {
    val c = Dsh.colors
    val (bg, fg) = when (tone) {
        Tone.Plain -> c.s2 to c.t2
        Tone.Ok -> c.okSoft to c.ok
        Tone.Warn -> c.warnSoft to c.warn
        Tone.Red -> c.dangerSoft to c.danger
    }
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

/** Цвета плиток `.si.c1…c8`. */
object Si {
    val c1 = Color(0xFF5A4CF0)
    val c2 = Color(0xFF2F7DE1)
    val c3 = Color(0xFF128A5C)
    val c4 = Color(0xFFD97706)
    val c5 = Color(0xFFC4323A)
    val c6 = Color(0xFF6B7280)
    val c7 = Color(0xFF0E9AA7)
    val c8 = Color(0xFFA855F7)
}

/** Цветная плитка `.si`: 30, радиус 9, белая иконка 17. */
@Composable
fun SiTile(icon: ImageVector, color: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

/** Нажимаемая область строки: подсветка s2 при нажатии и мгновенное затухание, как в прототипе. */
@Composable
fun Modifier.rowClick(enabled: Boolean = true, role: Role = Role.Button, onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    return clickable(enabled = enabled, role = role, interactionSource = remember { MutableInteractionSource() }, indication = ripple(color = Dsh.colors.t1), onClick = onClick)
}

/**
 * Строка группы `.gr`: 52 dp, отступы 11 / 14, промежуток 12;
 * слева плитка, в середине текст 15 с подписью 12,5, справа значение или свой элемент.
 */
@Composable
fun Gr(
    title: String? = null,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subColor: Color = Dsh.colors.t3,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    valueColor: Color = Dsh.colors.t2,
    minHeight: Dp = 52.dp,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    center: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    // Со значением: заголовок — сколько ему нужно (не больше 60 % строки, не рвётся по буквам на 360),
    // значение — всё остальное, у правого края, с многоточием.
    val titleMax = maxWidth * 0.6f
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).rowClick(onClick = onClick).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(if (value != null) Modifier.widthIn(max = titleMax) else Modifier.weight(1f)) {
            if (center != null) center() else {
                if (title != null) Text(title, style = body(15.sp), color = Dsh.colors.t1)
                if (!sub.isNullOrEmpty()) Text(sub, style = small(), color = subColor)
            }
        }
        if (value != null) Text(
            value, style = body(14.5.sp, 19.sp).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), color = valueColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (chevron) Icon(DshGlyphs.Right, null, tint = Dsh.colors.t3, modifier = Modifier.size(18.dp))
    }
    }
}

/**
 * Строка настроек `.st-row`: 52 dp, отступы 8 / 12 / 8 / 14, цветная плитка,
 * значение 14 t3 (не шире 46 %) и стрелка. [badge] — пометка `.mod` после заголовка («изменено»).
 */
@Composable
fun StRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tile: Color = Si.c6,
    sub: String? = null,
    subColor: Color = Dsh.colors.t3,
    value: String? = null,
    valueColor: Color = Dsh.colors.t3,
    titleColor: Color = Dsh.colors.t1,
    chevron: Boolean = false,
    badge: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxVal = maxWidth * 0.46f
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).rowClick(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) leading() else if (icon != null) SiTile(icon, tile)
            Column(Modifier.weight(1f)) {
                if (badge == null) Text(title, style = body(15.sp), color = titleColor) else TitleWithBadge(title, badge, titleColor)
                if (!sub.isNullOrEmpty()) Text(sub, style = small(), color = subColor)
            }
            if (value != null) Text(value, style = body(14.sp, 18.sp), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxVal))
            trailing?.invoke()
            if (chevron) Icon(DshGlyphs.Right, null, tint = Dsh.colors.t3, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Заголовок с пилюлей `.mod`: 11/600, цвет — акцент на 82 % к t1, фон accentSoft, отступ слева 6.
 * Пилюля встроена в текст, поэтому переносится вместе с последним словом, а не уезжает на свою строку.
 */
@Composable
private fun TitleWithBadge(title: String, badge: String, color: Color) {
    val c = Dsh.colors
    val style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { (measurer.measure(badge, style).size.width + (6 + 14).dp.roundToPx()).toSp() }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(title)
        appendInlineContent("badge", badge)
    }
    val inline = mapOf(
        "badge" to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(width, 18.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.Center),
        ) {
            Box(Modifier.fillMaxSize().padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    badge, style = style, color = lerp(c.t1, c.accent, 0.82f), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accentSoft).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        },
    )
    Text(text, style = body(15.sp), color = color, inlineContent = inline)
}

/** Строка с переключателем `.st-row .toggle`: вся строка переключает. */
@Composable
fun StToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector, tile: Color, sub: String? = null) {
    StRow(title, icon = icon, tile = tile, sub = sub, onClick = { onChange(!checked) }) {
        Box(Modifier.offset(x = 4.dp)) { DshSwitch(checked, onChange, contentDescription = title) }
    }
}

/** Переключатель в обёртке `.tsw` 52 × 44. */
@Composable
fun Tsw(checked: Boolean, onChange: (Boolean) -> Unit, description: String, enabled: Boolean = true) {
    Box(Modifier.size(52.dp, 44.dp), contentAlignment = Alignment.Center) { DshSwitch(checked, onChange, enabled = enabled, contentDescription = description) }
}

// ---------- Выбор ----------

/** Маленькие варианты `.opts`: фон s2, внутренний отступ 2, кнопки 40 dp. [wide] — на всю ширину поровну. */
@Composable
fun Opts(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, wide: Boolean = false) {
    Row(
        modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier).clip(RoundedCornerShape(10.dp)).background(Dsh.colors.s2).padding(2.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Dsh.colors.s1 else Color.Transparent, DshMotion.snappy(), label = "opt")
            Box(
                Modifier
                    .then(if (wide) Modifier.weight(1f) else Modifier.widthIn(min = 40.dp))
                    .height(40.dp)
                    .then(if (on) Modifier.shadow(1.5.dp, RoundedCornerShape(8.dp)) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = if (wide) 2.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = small(12.5.sp, 16.sp, if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) Dsh.colors.t1 else Dsh.colors.t2, maxLines = 1)
            }
        }
    }
}

/** Сегменты `.segm` с бегунком, который переезжает пружиной. */
@Composable
fun Segm(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp) {
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dsh.colors.s2).padding(3.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected.coerceAtLeast(0), DshMotion.snappy(), label = "seg")
        Box(Modifier.offset(x = x).width(w).height(40.dp).shadow(2.dp, RoundedCornerShape(11.dp)).clip(RoundedCornerShape(11.dp)).background(Dsh.colors.s1))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).selectable(on, role = Role.RadioButton) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = body(fontSize, 18.sp, if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) Dsh.colors.t1 else Dsh.colors.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Варианты-«таблетки» `.st-chips`: поровну, фон s2, выбранный — s1 с тенью. */
@Composable
fun StChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dsh.colors.s2).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Dsh.colors.s1 else Color.Transparent, tween(250), label = "chip")
            Box(
                Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(11.dp)) else Modifier)
                    .clip(RoundedCornerShape(11.dp)).background(bg)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = body(14.sp, 18.sp, if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) Dsh.colors.t1 else Dsh.colors.t2, maxLines = 1) }
        }
    }
}

/** Круг выбора `.ob-radio`: 22, линия 1,75; выбранный — акцент и точка 11. */
@Composable
fun RadioDot(on: Boolean) {
    val c = Dsh.colors
    val border by animateColorAsState(if (on) c.accent else c.t3, tween(200), label = "radio")
    val dot by animateDpAsState(if (on) 11.dp else 0.dp, DshMotion.lively(), label = "dot")
    Box(Modifier.padding(top = 1.dp).size(22.dp).border(1.75.dp, border, CircleShape), contentAlignment = Alignment.Center) {
        if (dot > 0.dp) Box(Modifier.size(dot).clip(CircleShape).background(c.accent))
    }
}

/** Строка выбора `.rad`: круг, заголовок 15 и подпись 12,5. */
@Composable
fun Rad(title: String, sub: String?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioDot(on)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15.sp, 20.sp), color = Dsh.colors.t1)
            if (sub != null) Text(sub, style = small(12.5.sp, 17.sp), color = Dsh.colors.t3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Неотключаемое правило `.rad.never`: красный крестик вместо круга. */
@Composable
fun RadNever(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(DshGlyphs.X, null, tint = Dsh.colors.danger, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15.sp, 20.sp), color = Dsh.colors.t2)
            Text(sub, style = small(12.5.sp, 17.sp), color = Dsh.colors.t3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------- Кнопки ----------

enum class BtnKind { Primary, Ghost, Surface }

/**
 * Кнопка `.btn` с настраиваемыми размерами: по умолчанию 44 dp, радиус 14, текст 15/500.
 * [Surface] — белая карточка с обводкой (`.pd-dev button`, `.au-act2 button`).
 */
@Composable
fun KBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Ghost,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    radius: Dp = 14.dp,
    fontSize: TextUnit = 15.sp,
    ink: Color? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    horizontal: Dp = 18.dp,
) {
    val c = Dsh.colors
    val (fill, defInk) = when {
        !enabled && kind == BtnKind.Primary -> c.s3 to c.t3
        kind == BtnKind.Primary -> c.accent to c.onAccent
        kind == BtnKind.Ghost -> c.s2 to c.t1
        else -> c.s1 to c.t1
    }
    val color = ink ?: defInk
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    Row(
        modifier
            .height(height)
            .pressScale(interaction)
            .clip(shape)
            .background(fill)
            .then(if (kind == BtnKind.Surface) Modifier.border(1.dp, c.line, shape) else Modifier)
            .clickable(interaction, ripple(), enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontal),
        horizontalArrangement = Arrangement.spacedBy(if (kind == BtnKind.Surface) 8.dp else 6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(color, 16.dp) else if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        if (text.isNotEmpty()) Text(text, style = body(fontSize, fontSize * 1.3f, FontWeight.Medium), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Вращающийся круг `.spin-w`. */
@Composable
fun Spinner(color: Color, size: Dp = 16.dp, stroke: Dp = 2.dp) { com.dsh.core.designsystem.DshSpinner(color, size, stroke) }

// ---------- Буквенная иконка плагина ----------

/** Иконка плагина `.pl-ic`: буква на фоне оттенка [hue]; выключенный — серый и бледный. */
@Composable
fun PlIc(letter: String, hue: Float, big: Boolean = false, off: Boolean = false, size: Dp = if (big) 56.dp else 40.dp, radius: Dp = if (big) 17.dp else 12.dp, fontSize: TextUnit = if (big) 24.sp else 17.sp) {
    val dark = Dsh.colors.isDark
    var bg = if (dark) Color.hsl(hue, 0.40f, 0.20f) else Color.hsl(hue, 0.70f, 0.92f)
    var fg = if (dark) Color.hsl(hue, 0.80f, 0.78f) else Color.hsl(hue, 0.60f, 0.26f)
    if (off) { bg = gray(bg); fg = gray(fg) }
    Box(
        Modifier.size(size).graphicsLayer { alpha = if (off) 0.6f else 1f }.clip(RoundedCornerShape(radius)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Text(letter, style = display(fontSize, fontSize * 1.2f), color = fg) }
}

private fun gray(c: Color): Color {
    val l = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return Color(l, l, l, c.alpha)
}

/** Мягкий градиент «акцентной» карточки (`.pl-create`, `.au-next`). */
@Composable
fun accentCardBrush(mix: Float = 0.4f): Brush {
    val c = Dsh.colors
    val soft = c.accentSoft.compositeOver(c.s1)
    return Brush.linearGradient(listOf(soft, lerp(c.s1, soft, mix)), start = Offset.Zero, end = Offset.Infinite)
}

/** Смесь цветов, как `color-mix(in srgb, a p%, b)`. */
fun mix(a: Color, p: Float, b: Color): Color = lerp(b, a, p)

// ---------- Тост ----------

@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    fun show(text: String) { message = text; serial++ }
    internal fun clear() { message = null }
}

@Composable
fun rememberToast(): ToastState = remember { ToastState() }

/** Тост `.toast`: тёмная «таблетка» над низом экрана (112 dp), 2,3 с. */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val msg = state.message
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.serial) {
        if (msg == null) return@LaunchedEffect
        shown = msg
        delay(2_300)
        shown = null
        state.clear()
    }
    Box(modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 112.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = shown != null,
            enter = fadeIn(DshMotion.lively()) + slideInVertically(DshMotion.lively()) { -it / 2 } + scaleIn(DshMotion.lively(), initialScale = 0.96f),
            exit = fadeOut(DshMotion.snappy()) + slideOutVertically(DshMotion.snappy()) { -it / 3 },
        ) {
            val text = shown ?: msg.orEmpty()
            Box(
                Modifier.shadow(10.dp, CircleShape).clip(CircleShape).background(Dsh.colors.t1).padding(horizontal = 16.dp, vertical = 11.dp),
            ) { Text(text, style = body(14.sp, 18.sp), color = Dsh.colors.bg, maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}

// ---------- Лист ----------

/**
 * Лист `.sheet`: отступ 8 от краёв, радиус 34, «ручка» 38 × 5, затемнение под ним.
 * Открывается пружиной снизу; «Назад» и нажатие на затемнение закрывают.
 */
@Composable
fun KSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (visible) BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(DshMotion.smooth()), exit = fadeOut(DshMotion.smooth())) {
            Box(
                Modifier.fillMaxSize().background(Dsh.colors.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(DshMotion.smooth()) { it + it / 20 },
            exit = slideOutVertically(DshMotion.smooth()) { it + it / 20 },
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(34.dp))
                    .clip(RoundedCornerShape(34.dp))
                    .background(Dsh.colors.s1)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
            ) {
                Box(Modifier.padding(top = 4.dp, bottom = 12.dp).size(38.dp, 5.dp).clip(CircleShape).background(Dsh.colors.s3).align(Alignment.CenterHorizontally))
                content()
            }
        }
    }
}

/** Заголовок листа `h3`: 19 / 600, отступы 2 / 4 / 14. */
@Composable
fun SheetTitle(text: String) {
    Text(text, style = display(19.sp, 24.sp), color = Dsh.colors.t1, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp).semantics { heading() })
}

/** Список листа `.list`: фон s2, радиус 18; строки `.li` с плиткой `.lic` 32. */
@Composable
fun SheetList(build: GlScope.() -> Unit) {
    val scope = GlScope().apply(build)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dsh.colors.s2)) {
        scope.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Dsh.colors.line))
            r()
        }
    }
}

@Composable
fun SheetItem(icon: ImageVector, title: String, sub: String?, iconTint: Color = Dsh.colors.t1, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rowClick(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Dsh.colors.s1), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15.sp, 19.sp), color = Dsh.colors.t1)
            if (sub != null) Text(sub, style = small(12.5.sp, 17.sp), color = Dsh.colors.t3, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** Подсказка с иконкой (`.au-host`, `.tr-note`): 12,5 t3, иконка 15. */
@Composable
fun IconNote(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Dsh.colors.t3, modifier = Modifier.padding(top = 1.dp).size(15.dp))
        Text(text, style = small(12.5.sp, 18.sp), color = Dsh.colors.t3, modifier = Modifier.weight(1f))
    }
}

/** Индикатор «живой» точки `.live-dot` 7 dp с ореолом. */
@Composable
fun LiveDot(on: Boolean) {
    val c = Dsh.colors
    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(13.dp).clip(CircleShape).background(c.okSoft))
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) c.ok else c.t3))
    }
}

/** Полоса заполнения `.st-m .bar`: 6 dp, трек s3, заливка акцентом. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Dsh.colors.accent) {
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), DshMotion.smooth(), label = "meter")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Dsh.colors.s3)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Счётчик-«метр» `.st-m`: подпись слева, значение справа, полоса. */
@Composable
fun Meter(label: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
            Text(label, style = small(), color = Dsh.colors.t2, modifier = Modifier.weight(1f))
            Text(value, style = small(weight = FontWeight.Medium), color = Dsh.colors.t1)
        }
        MeterBar(fraction)
    }
}

/** Карточка `.st-card` / `.st-hostcard`: радиус 22, отступ 16. */
@Composable
fun StCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Dsh.colors.s1).border(1.dp, Dsh.colors.line, shape).padding(17.dp), content = content)
}

/** Ряд из трёх чисел `.st-bal` / `.pd-stats` с разделителями. */
@Composable
fun StatRow(items: List<Triple<String, String, Boolean>>, modifier: Modifier = Modifier, valueSize: TextUnit = 17.sp, padV: Dp = 10.dp) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(Dsh.colors.s1).border(1.dp, Dsh.colors.line, shape).padding(1.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        items.forEachIndexed { i, (v, label, danger) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxSize().background(Dsh.colors.line))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = padV)) {
                Text(v, style = display(valueSize, valueSize * 1.25f), color = if (danger) Dsh.colors.danger else Dsh.colors.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = small(11.5.sp, 14.5.sp), color = Dsh.colors.t3)
            }
        }
    }
}

/** Двухфазное удаление: первое нажатие «взводит» на 3 с, второе — подтверждает. */
@Stable
class ArmState {
    var armed by mutableStateOf(false)
        private set
    private var serial by mutableIntStateOf(0)

    fun tap(onConfirm: () -> Unit) {
        if (armed) { armed = false; onConfirm() } else { armed = true; serial++ }
    }

    @Composable
    fun AutoDisarm() {
        LaunchedEffect(serial) {
            if (!armed) return@LaunchedEffect
            delay(3_000)
            armed = false
        }
    }
}

@Composable
fun rememberArm(): ArmState = remember { ArmState() }.also { it.AutoDisarm() }

/**
 * Ползунок `input[type=range]` прототипа: дорожка 4 dp (заполнена акцентом слева),
 * круглый бегунок 16 dp, шаги [range] целыми. Тянуть и нажимать можно по всей высоте 40 dp.
 */
@Composable
fun KSlider(value: Int, range: IntRange, onChange: (Int) -> Unit, onDone: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val steps = (range.last - range.first).coerceAtLeast(1)
    val frac = (value - range.first).toFloat() / steps
    val anim by androidx.compose.animation.core.animateFloatAsState(frac, DshMotion.snappy(), label = "slider")
    BoxWithConstraints(
        modifier
            .height(40.dp)
            .semantics {
                contentDescription = description
                stateDescription = value.toString()
                setProgress { target ->
                    val v = target.toInt().coerceIn(range)
                    onChange(v); onDone(); true
                }
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), steps - 1)
            }
            .pointerInput(range) {
                fun at(x: Float) = (range.first + ((x / size.width).coerceIn(0f, 1f) * steps).roundToIntCompat()).coerceIn(range)
                detectTapGestures { onChange(at(it.x)); onDone() }
            }
            .pointerInput(range) {
                fun at(x: Float) = (range.first + ((x / size.width).coerceIn(0f, 1f) * steps).roundToIntCompat()).coerceIn(range)
                detectHorizontalDragGestures(onDragEnd = onDone) { change, _ -> onChange(at(change.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.s3))
        Box(Modifier.width(w * anim).height(4.dp).clip(CircleShape).background(c.accent))
        Box(Modifier.offset(x = (w - 16.dp) * anim).size(16.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(c.accent))
    }
}

private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()
