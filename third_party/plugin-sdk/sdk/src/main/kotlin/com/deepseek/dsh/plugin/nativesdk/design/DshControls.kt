package com.deepseek.dsh.plugin.nativesdk.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme

/** 按钮视觉层级，与 App 对话框/设置页一致。 */
enum class DshButtonVariant {
    /** 主操作：实心深色底。 */
    PRIMARY,

    /** 次操作：透明底 + 发丝边。 */
    SECONDARY,

    /** 文字动作：无底色。 */
    GHOST,

    /** 危险操作：错误色实心底。 */
    DANGER,
}

/**
 * 标准按钮：40dp 高、12dp 圆角、14sp 文字，颜色全部来自 [PluginTheme]。
 *
 * 设置页的保存/连接/取消等动作优先用它，而不是 Material `Button`。
 */
@Composable
fun DshButton(
    text: String,
    theme: PluginTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: DshButtonVariant = DshButtonVariant.PRIMARY,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val fill = when (variant) {
        DshButtonVariant.PRIMARY -> if (pressed && enabled) theme.colorButtonPrimaryHover else theme.colorButtonPrimary
        DshButtonVariant.DANGER -> theme.colorButtonDanger
        DshButtonVariant.SECONDARY, DshButtonVariant.GHOST -> Color.Transparent
    }
    val textColor = when (variant) {
        DshButtonVariant.PRIMARY -> theme.colorButtonTextOnPrimary
        DshButtonVariant.DANGER -> theme.colorOnError
        DshButtonVariant.SECONDARY, DshButtonVariant.GHOST -> theme.colorButtonSecondaryText
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.4f)
            .background(fill)
            .then(
                if (variant == DshButtonVariant.SECONDARY) {
                    Modifier.border(0.5.dp, theme.colorButtonSecondaryBorder, shape)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 文字动作按钮（无底色、品牌色文字）。 */
@Composable
fun DshTextButton(
    text: String,
    theme: PluginTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = theme.colorLink,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )
}

/** 设置页开关：36x20 轨道、16dp 滑块，与 App `DshSwitch` 同尺寸。 */
@Composable
fun DshSwitch(
    checked: Boolean,
    theme: PluginTheme,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        label = "dshSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) theme.colorButtonPrimary else theme.colorSwitchTrackOff)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(theme.colorSwitchThumb),
        )
    }
}

/** 设置页开关行：标题/说明 + 右侧开关。 */
@Composable
fun DshSwitchRow(
    theme: PluginTheme,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = theme.textBody, color = theme.colorLabelPrimary)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = theme.textCaption, color = theme.colorLabelSecondary)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            DshSwitch(checked = checked, theme = theme, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (showDivider) DshPanelDivider(theme)
    }
}

/** 单行输入框（设置页/对话框里的文本输入）。 */
@Composable
fun DshTextField(
    value: String,
    theme: PluginTheme,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.colorInputBackground)
            .border(0.5.dp, theme.colorInputBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = theme.textBody, color = theme.colorLabelTertiary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = theme.colorLabelPrimary),
            cursorBrush = SolidColor(theme.colorLink),
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 小标签/胶囊（状态、数量、分类）。 */
@Composable
fun DshChip(
    text: String,
    theme: PluginTheme,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(theme.cornerChip))
            .background(theme.colorChipBackground)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = theme.colorChipText,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

/**
 * 对话框：面板材质 + 标题/正文 + 取消/确认按钮。
 *
 * 用 Compose Dialog 而不是 Material AlertDialog，颜色与圆角和 App 的
 * `DshDialog` 一致。
 */
@Composable
fun DshDialog(
    theme: PluginTheme,
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    body: String? = null,
    cancelText: String? = null,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(theme.panelCorner))
                .background(theme.colorPanelElevated)
                .border(0.5.dp, theme.colorPanelBorder, RoundedCornerShape(theme.panelCorner))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = theme.textTitle.copy(fontWeight = FontWeight.SemiBold),
                color = theme.colorLabelPrimary,
            )
            body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = theme.textBody, color = theme.colorLabelSecondary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                cancelText?.let {
                    DshButton(
                        text = it,
                        theme = theme,
                        onClick = onDismiss,
                        variant = DshButtonVariant.SECONDARY,
                    )
                }
                DshButton(
                    text = confirmText,
                    theme = theme,
                    onClick = onConfirm,
                    variant = if (danger) DshButtonVariant.DANGER else DshButtonVariant.PRIMARY,
                    enabled = confirmEnabled,
                )
            }
        }
    }
}
