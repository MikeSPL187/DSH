package com.deepseek.dsh.plugin.nativesdk.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 插件可见的主题只读快照。
 *
 * 深色模式与系统字体缩放由 App 统一映射进快照：插件不写 `isSystemInDarkTheme()`
 * 分支，读到的就是当前正确取值。快照变化触发宿主容器重组，插件 UI 随之更新。
 *
 * 插件**禁止**硬编码颜色/字号——这是商店审核的一条红线，也是两个前端（Web 与
 * 原生）视觉一致的前提。
 */
data class PluginTheme(
    // 颜色
    val colorPrimary: Color,
    val colorOnPrimary: Color,
    val colorSurface: Color,
    val colorOnSurface: Color,
    val colorSurfaceVariant: Color,
    val colorOnSurfaceVariant: Color,
    val colorError: Color,
    val colorOnError: Color,
    val colorOutline: Color,
    // 浮层（与 App 的 DshMenuCard 同一套材质：表面色 + 发丝边 + 大圆角）
    val colorPopupSurface: Color = colorSurface,
    val colorPopupBorder: Color = colorOutline.copy(alpha = 0.35f),
    val cornerPopup: Dp = 16.dp,
    // 文字
    val textTitle: TextStyle,
    val textBody: TextStyle,
    val textCaption: TextStyle,
    val textPopupTitle: TextStyle = textCaption,
    val textPopupBody: TextStyle = textBody,
    // 形状与间距
    val cornerCard: Dp = 16.dp,
    val cornerChip: Dp = 8.dp,
    val spacingUnit: Dp = 8.dp,
    val isDark: Boolean,
    // 设置页/面板（与 App 的 DshPanel 同一套 token：隔层底色、发丝边、
    // 三级文字色、44dp 行高）。插件设置页用 design/DshPanel.kt 里的组件，
    // 这里的分组标题/分隔线/动作色随 App 主题变化。
    val colorPanel: Color = colorSurface,
    val colorPanelBorder: Color = colorOutline.copy(alpha = 0.25f),
    val colorPanelElevated: Color = colorSurfaceVariant,
    val colorLabelPrimary: Color = colorOnSurface,
    val colorLabelSecondary: Color = colorOnSurfaceVariant,
    val colorLabelTertiary: Color = colorOutline,
    val colorLink: Color = colorPrimary,
    val panelCorner: Dp = cornerCard,
    val rowMinHeight: Dp = 44.dp,
    // 控件（与 App DshDialogButton / DshSwitch / 输入框同一套 token）
    val colorButtonPrimary: Color = colorPrimary,
    val colorButtonPrimaryHover: Color = colorPrimary,
    val colorButtonTextOnPrimary: Color = colorOnPrimary,
    val colorButtonSecondaryBorder: Color = colorOutline.copy(alpha = 0.25f),
    val colorButtonSecondaryText: Color = colorOnSurface,
    val colorButtonDanger: Color = colorError,
    val colorSwitchTrackOff: Color = colorOutline.copy(alpha = 0.3f),
    val colorSwitchThumb: Color = colorOnPrimary,
    val colorInputBackground: Color = colorSurfaceVariant,
    val colorInputBorder: Color = colorOutline.copy(alpha = 0.25f),
    val colorChipBackground: Color = colorSurfaceVariant,
    val colorChipText: Color = colorOnSurfaceVariant,
) {
    /** 卡片圆角形状，直接用。 */
    val shapeCard get() = RoundedCornerShape(cornerCard)

    /** 胶囊/标签圆角形状。 */
    val shapeChip get() = RoundedCornerShape(cornerChip)

    /** 浮层圆角形状（与 App 菜单一致）。 */
    val shapePopup get() = RoundedCornerShape(cornerPopup)

    companion object {
        /**
         * 官方浅色主题预置：与 App 的 DshLight 调色板同源。
         *
         * 宿主在线时永远以宿主推来的 [PluginTheme] 为准；这个预置只用于预览、
         * 单元测试和独立渲染插件页，避免插件自己写死一套浅色。
         */
        fun light(): PluginTheme = PluginTheme(
            colorPrimary = Color(0xFF1D5DE7),
            colorOnPrimary = Color.White,
            colorSurface = Color(0xFFFFFFFF),
            colorOnSurface = Color(0xFF191C20),
            colorSurfaceVariant = Color(0xFFE0E2EC),
            colorOnSurfaceVariant = Color(0xFF44474F),
            colorError = Color(0xFFEC1313),
            colorOnError = Color.White,
            colorOutline = Color(0xFF74777F),
            colorPopupSurface = Color(0xFFF8F9FA),
            colorPopupBorder = Color(0x1F000000),
            textTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
            textBody = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            textCaption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            isDark = false,
            colorPanel = Color.White,
            colorPanelBorder = Color(0x1A000000),
            colorPanelElevated = Color.White,
            colorLabelPrimary = Color(0xFF0F1115),
            colorLabelSecondary = Color(0xFF61666B),
            colorLabelTertiary = Color(0xFF81858C),
            colorLink = Color(0xFF1D5DE7),
            colorButtonPrimary = Color(0xFF0F1115),
            colorButtonPrimaryHover = Color(0xFF43454A),
            colorButtonTextOnPrimary = Color.White,
            colorButtonSecondaryBorder = Color(0x1F000000),
            colorButtonSecondaryText = Color(0xFF0F1115),
            colorButtonDanger = Color(0xFFEC1313),
            colorSwitchTrackOff = Color(0x1F000000),
            colorSwitchThumb = Color.White,
            colorInputBackground = Color.White,
            colorInputBorder = Color(0x1A000000),
            colorChipBackground = Color(0xFFF5F6F7),
            colorChipText = Color(0xFF61666B),
        )

        /**
         * 官方深色主题预置：与 App 的 DshDark 调色板同源（背景 #151517、三级文字、
         * 发丝边、深色按钮）。插件在深色宿主下读到的是这个取值；预览/测试直接用
         * 它可以在深色下检查对比度，不必自己维护 `isDark` 分支。
         */
        fun dark(): PluginTheme = PluginTheme(
            colorPrimary = Color(0xFFB4C5FF),
            colorOnPrimary = Color(0xFF002A78),
            colorSurface = Color(0xFF111318),
            colorOnSurface = Color(0xFFE2E2E9),
            colorSurfaceVariant = Color(0xFF353638),
            colorOnSurfaceVariant = Color(0xFFC4C6D0),
            colorError = Color(0xFFF25A5A),
            colorOnError = Color(0xFF3A0A0A),
            colorOutline = Color(0xFF8E9099),
            colorPopupSurface = Color(0xFF2A2B2E),
            colorPopupBorder = Color(0x29FFFFFF),
            textTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
            textBody = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            textCaption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            isDark = true,
            colorPanel = Color(0xFF2C2C2E),
            colorPanelBorder = Color(0x1FFFFFFF),
            colorPanelElevated = Color(0xFF353638),
            colorLabelPrimary = Color(0xFFF9FAFB),
            colorLabelSecondary = Color(0xFFCFD3D6),
            colorLabelTertiary = Color(0xFFADB2B8),
            colorLink = Color(0xFFB4C5FF),
            colorButtonPrimary = Color(0xFFF9FAFB),
            colorButtonPrimaryHover = Color(0xFFEBEEF2),
            colorButtonTextOnPrimary = Color(0xFF0F1115),
            colorButtonSecondaryBorder = Color(0x29FFFFFF),
            colorButtonSecondaryText = Color(0xFFF9FAFB),
            colorButtonDanger = Color(0xFFF25A5A),
            colorSwitchTrackOff = Color(0x29FFFFFF),
            colorSwitchThumb = Color(0xFFADB2B8),
            colorInputBackground = Color(0xFF353638),
            colorInputBorder = Color(0x1FFFFFFF),
            colorChipBackground = Color(0xFF353638),
            colorChipText = Color(0xFFCFD3D6),
        )
    }
}
