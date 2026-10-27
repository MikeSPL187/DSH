package com.dsh.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Пружинное сжатие при нажатии — отклик для кнопок и карточек.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, DshMotion.lively(), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Шапка вложенного экрана — та же `.sbar` прототипа, что [com.dsh.core.designsystem.kit.SBar]:
 * «Назад», заголовок с подписью и действия справа.
 */
@Composable
fun DshTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Назад",
    actions: @Composable RowScope.() -> Unit = {},
) = com.dsh.core.designsystem.kit.SBar(title, subtitle, onBack, modifier, backDescription, actions = actions)

/** Круглая кнопка-иконка 44 dp. */
@Composable
fun DshIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Dsh.colors.t1,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(DshSpace.touch)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .clickable(interaction, indication = androidx.compose.material3.ripple(bounded = true), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
    }
}

/** Подпись раздела над группой: «ПРОВАЙДЕРЫ», с необязательным значением справа. */
@Composable
fun DshSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = Dsh.type.sectionLabel, color = Dsh.colors.t3, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = Dsh.type.footnote, color = Dsh.colors.t3)
    }
}

/** Сгруппированный блок строк с разделителями — основа экранов настроек и списков. */
@Composable
fun DshGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshSpace.groupRadius))
            .background(Dsh.colors.s1)
            .border(BorderStroke(1.dp, Dsh.colors.line), RoundedCornerShape(DshSpace.groupRadius)),
        content = content,
    )
}

/** Разделитель между строками группы, начинается от текста, а не от края. */
@Composable
fun DshDivider(inset: Dp = 0.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Dsh.colors.line)
}

/** Цветная плитка 30 dp под иконкой строки. */
@Composable
fun DshIconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(DshSpace.tileRadius)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.57f))
    }
}

/** Цвета плиток строк настроек, как в iOS: каждый раздел узнаётся по цвету. */
object DshTileColors {
    val indigo = Color(0xFF5A4CF0)
    val blue = Color(0xFF2F7DE1)
    val green = Color(0xFF128A5C)
    val orange = Color(0xFFD97706)
    val red = Color(0xFFC4323A)
    val gray = Color(0xFF6B7280)
    val violet = Color(0xFFA855F7)
}

/**
 * Строка группы: плитка, заголовок с подписью, значение и стрелка или свой элемент справа.
 * Нажатие подсвечивает строку и сразу гаснет — как в системных настройках.
 */
@Composable
fun DshRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    valueColor: Color = Dsh.colors.t3,
    titleColor: Color = Dsh.colors.t1,
    subtitleColor: Color = Dsh.colors.t3,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth().heightIn(min = DshSpace.rowMin)
    val clickableModifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(
        modifier = clickableModifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = Dsh.type.body, color = titleColor)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = Dsh.type.footnote, color = subtitleColor)
        }
        if (value != null) {
            Text(
                value,
                style = Dsh.type.callout,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke()
        if (chevron) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Dsh.colors.t3, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Переключатель с пружинным бегунком и лёгким тактильным откликом.
 */
@Composable
fun DshSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentDescription: String? = null) {
    val haptics = LocalHapticFeedback.current
    val track by animateColorAsState(if (checked) Dsh.colors.accent else Dsh.colors.s3, DshMotion.smooth(), label = "track")
    val offset by animateDpAsState(if (checked) 15.dp else 0.dp, DshMotion.lively(), label = "thumb")
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 44.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCheckedChange(it) }
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 23.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                .clip(CircleShape)
                .background(track)
                .padding(2.5.dp),
        ) {
            Box(
                Modifier
                    .offset(x = offset)
                    .size(18.dp)
                    .shadow(1.5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * Сегменты одинаковой ширины с бегунком, который переезжает пружиной.
 */
@Composable
fun DshSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshSpace.controlRadius))
            .background(Dsh.colors.s2)
            .padding(3.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, DshMotion.snappy(), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .height(40.dp)
                .shadow(1.dp, RoundedCornerShape(11.dp))
                .clip(RoundedCornerShape(11.dp))
                .background(Dsh.colors.s1),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (on) Dsh.type.callout.copy(fontWeight = FontWeight.Medium) else Dsh.type.callout,
                        color = if (on) Dsh.colors.t1 else Dsh.colors.t2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

enum class DshButtonKind { Primary, Ghost, Danger, Text }

/** Кнопка 44 dp (большая — 52 dp на всю ширину), пружинное нажатие, как `.btn` в прототипе. */
@Composable
fun DshButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DshButtonKind = DshButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    big: Boolean = false,
) {
    val c = Dsh.colors
    val (fill, ink) = when {
        !enabled -> c.s3 to c.t3
        kind == DshButtonKind.Primary -> c.accent to c.onAccent
        kind == DshButtonKind.Danger -> c.dangerFill to Color.White
        kind == DshButtonKind.Ghost -> c.s2 to c.t1
        else -> Color.Transparent to c.accent
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = if (big) 52.dp else 44.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(if (big) 16.dp else DshSpace.controlRadius))
            .background(fill)
            .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(text, style = Dsh.type.bodyMedium.copy(fontSize = if (big) 16.sp else 15.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Карточка-контейнер с крупным радиусом для сводок и героев экранов. */
@Composable
fun DshCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshSpace.cardRadius))
            .background(Dsh.colors.s1)
            .border(BorderStroke(1.dp, Dsh.colors.line), RoundedCornerShape(DshSpace.cardRadius))
            .padding(padding),
        content = content,
    )
}
