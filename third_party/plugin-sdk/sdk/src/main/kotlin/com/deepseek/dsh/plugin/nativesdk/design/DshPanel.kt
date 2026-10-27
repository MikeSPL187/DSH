package com.deepseek.dsh.plugin.nativesdk.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme

/**
 * SDK 的设计系统原语：与 App 的 DshPanel 同一套材质、圆角、行高与字号。
 *
 * 设置页 / 详情页 / popover 内容都优先用这些组件，而不是 Material `Card`：
 * 这样插件原生 UI 和 App 内置设置页的视觉才一致（同一层底色、0.5dp 发丝边、
 * 16dp 圆角、44dp 行高、11sp 分组标题）。
 *
 * 所有颜色都从 [PluginTheme] 读取，插件不硬编码色值。
 */
object DshPanelDefaults {
    /** 面板圆角（与 App `--dsw-radius-lg` 一致）。 */
    val Shape = RoundedCornerShape(16.dp)

    /** 紧凑面板圆角（菜单、卡片内嵌）。 */
    val CompactShape = RoundedCornerShape(12.dp)

    /** 面板内容内边距。 */
    val ContentPadding = 16.dp

    /** 面板行最小高度（App 设置行）。 */
    val RowMinHeight = 44.dp

    /** 面板行左右内边距。 */
    val RowHorizontalPadding = 16.dp

    /** 分组标题字号。 */
    val SectionLabelSize = 11.sp
}

/**
 * 带标题的面板容器：底层色 + 0.5dp 发丝边 + 大圆角。
 *
 * @param theme 宿主下发的主题快照。
 * @param title 面板标题；null 表示无标题。
 * @param subtitle 标题下的说明；null 表示无说明。
 * @param content 面板内容，垂直排列。
 */
@Composable
fun DshPanel(
    theme: PluginTheme,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.panelCorner))
            .background(theme.colorPanel)
            .border(0.5.dp, theme.colorPanelBorder, RoundedCornerShape(theme.panelCorner))
            .padding(DshPanelDefaults.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = theme.textBody.copy(fontWeight = FontWeight.SemiBold),
                color = theme.colorLabelPrimary,
            )
        }
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = theme.textCaption,
                color = theme.colorLabelSecondary,
            )
        }
        content()
    }
}

/** 分组标题：设置页的小节标签（如「账号」「模型」）。 */
@Composable
fun DshSectionLabel(
    text: String,
    theme: PluginTheme,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        fontSize = DshPanelDefaults.SectionLabelSize,
        lineHeight = 16.sp,
        color = theme.colorLabelTertiary,
    )
}

/** 面板内的一行：标题 + 可选副标题 + 右侧值/尾部内容。 */
@Composable
fun DshPanelRow(
    theme: PluginTheme,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier
                    .clip(RoundedCornerShape(theme.cornerChip))
                    .clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(vertical = 10.dp)
    Column {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = theme.textBody,
                    color = theme.colorLabelPrimary,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = theme.textCaption,
                        color = theme.colorLabelSecondary,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = theme.textBody,
                    color = theme.colorLabelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
        if (showDivider) {
            DshPanelDivider(theme)
        }
    }
}

/** 面板内的发丝分割线。 */
@Composable
fun DshPanelDivider(theme: PluginTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(theme.colorPanelBorder),
    )
}

/** 面板右上角的文字动作（如「编辑」「查看全部」）。 */
@Composable
fun DshPanelAction(
    text: String,
    theme: PluginTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = theme.textBody.copy(fontWeight = FontWeight.Medium),
        color = theme.colorLink,
    )
}

/** 面板级空态/说明文字。 */
@Composable
fun DshPanelCaption(
    text: String,
    theme: PluginTheme,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = theme.textCaption,
        color = theme.colorLabelSecondary,
    )
}
