package com.dsh.plugin.nativehost

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.dshPanelColors
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme

/**
 * Тема приложения как снимок [PluginTheme] для плагинов (только чтение).
 *
 * Тёмная тема и масштаб шрифта системы учтены в теме приложения; снимок обновляется вместе с ней
 * и перекомпонует контейнеры. Плагин всегда получает актуальные значения и своих веток для тем не пишет.
 *
 * Поля плавающих панелей взяты из меню приложения (DshMenuCard): полупрозрачный фон меню поверх
 * основного, тонкая граница, большой радиус 16dp — popover плагина выглядит как меню модели и доступа,
 * а не как отдельная карточка Material.
 */
@Composable
fun rememberPluginTheme(): PluginTheme {
    val c = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    val panel = dshPanelColors()
    return PluginTheme(
        colorPrimary = c.primary,
        colorOnPrimary = c.onPrimary,
        colorSurface = c.surface,
        colorOnSurface = c.onSurface,
        colorSurfaceVariant = c.surfaceVariant,
        colorOnSurfaceVariant = c.onSurfaceVariant,
        colorError = c.error,
        colorOnError = c.onError,
        colorOutline = c.outline,
        colorPopupSurface = panel.menuSurfaceFill.compositeOver(panel.bgBase),
        colorPopupBorder = if (panel.isDark) panel.borderL3 else panel.borderL1,
        cornerPopup = 16.dp,
        textTitle = t.titleMedium,
        textBody = t.bodyMedium,
        textCaption = t.bodySmall,
        textPopupTitle = t.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        textPopupBody = t.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
        // Тему решает приложение и Host (ui-theme), а не тема системы;
        // dshPanelColors() уже отражает действующую тему.
        isDark = panel.isDark,
        // Настройки и панели — из того же источника, что DshPanel приложения: фон слоя, тонкая граница, три уровня текста.
        colorPanel = panel.bgLayer2,
        colorPanelBorder = panel.borderL2,
        colorPanelElevated = panel.bgLayer3,
        colorLabelPrimary = panel.labelPrimary,
        colorLabelSecondary = panel.labelSecondary,
        colorLabelTertiary = panel.labelTertiary,
        colorLink = c.primary,
        panelCorner = 16.dp,
        rowMinHeight = 44.dp,
        // Кнопки, переключатели и поля — те же, что DshDialogButton / DshSwitch приложения.
        colorButtonPrimary = panel.buttonPrimaryFill,
        colorButtonPrimaryHover = panel.buttonPrimaryHover,
        colorButtonTextOnPrimary = panel.labelPrimaryForeground,
        colorButtonSecondaryBorder = panel.borderL3,
        colorButtonSecondaryText = panel.labelPrimary,
        colorButtonDanger = panel.stateErrorPrimary,
        colorSwitchTrackOff = panel.borderL3,
        colorSwitchThumb = panel.switchThumb,
        colorInputBackground = panel.bgLayer3,
        colorInputBorder = panel.borderL2,
        colorChipBackground = panel.bgModulePlatform,
        colorChipText = panel.labelSecondary,
    )
}
