package com.deepseek.dsh.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DeepSeek Harness surface tokens consumed by menus, panels and dialogs.
 *
 * Values mirror the web client's `ui-theme` design platform
 * (`design-platform.css`): menu material fills, label inks, hairlines, hover
 * fills and the danger family. Theme selection follows the phone's dark mode,
 * exactly like the client's `data-ds-dark-theme` switch.
 *
 * @property isDark whether these values describe the dark palette.
 * @property labelPrimary primary text and menu row label ink.
 * @property labelSecondary secondary text (dialog descriptions, menu icons on some surfaces).
 * @property labelTertiary tertiary text (menu section labels and trailing values).
 * @property labelCaption caption ink (shortcut keys).
 * @property labelPrimaryForeground text/icon ink on a primary-filled control.
 * @property menuIcon leading icon ink inside menu rows.
 * @property bgBase page background.
 * @property bgLayer2 dialog/secondary layer background.
 * @property bgMask1 modal mask fill.
 * @property borderL1 menu elevation stroke (hairline).
 * @property borderL2 separators (menu separator, footer hairline).
 * @property borderL3 outlined controls.
 * @property borderL4 stronger outlines.
 * @property interactiveHover pointer/keyboard highlight fill.
 * @property interactiveHoverDanger destructive row highlight fill.
 * @property interactiveActive pressed fill.
 * @property stateErrorPrimary destructive label/icon ink.
 * @property menuSurfaceFill translucent menu material.
 * @property menuGroupHeaderFill sticky menu group heading fill.
 * @property skeleton loading skeleton fill.
 * @property buttonPrimaryFill primary button fill (brand primary).
 * @property buttonPrimaryHover primary button pressed/hover fill.
 * @property dialogFill dialog card fill (bg-layer-2).
 */
data class DshPanelColors(
    val isDark: Boolean,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelPrimaryForeground: Color,
    val menuIcon: Color,
    val bgBase: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgModulePlatform: Color,
    val bgMask1: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    val interactiveHover: Color,
    val interactiveHoverDanger: Color,
    val interactiveActive: Color,
    val stateErrorPrimary: Color,
    val menuSurfaceFill: Color,
    val menuGroupHeaderFill: Color,
    val skeleton: Color,
    val buttonPrimaryFill: Color,
    val buttonPrimaryHover: Color,
    val switchThumb: Color,
) {
    /** Dialog card fill: the secondary layer. */
    val dialogFill: Color get() = bgLayer2
}

/** Панельные токены, выведенные из палитры DSH (см. [DshColors]). */
private fun panelFrom(c: DshColors) = DshPanelColors(
    isDark = c.isDark,
    labelPrimary = c.t1,
    labelSecondary = c.t2,
    labelTertiary = c.t3,
    labelCaption = c.t3,
    labelPrimaryForeground = c.onAccent,
    menuIcon = c.t2,
    bgBase = c.bg,
    bgLayer2 = c.s1,
    bgLayer3 = c.s2,
    bgModulePlatform = c.s2,
    bgMask1 = c.scrim,
    borderL1 = c.line,
    borderL2 = c.line,
    borderL3 = c.s3,
    borderL4 = c.t3.copy(alpha = 0.4f),
    interactiveHover = c.t1.copy(alpha = 0.05f),
    interactiveHoverDanger = c.dangerSoft,
    interactiveActive = c.t1.copy(alpha = 0.09f),
    stateErrorPrimary = c.danger,
    menuSurfaceFill = c.glass,
    menuGroupHeaderFill = c.s1.copy(alpha = 0.94f),
    skeleton = c.s2,
    buttonPrimaryFill = c.accent,
    buttonPrimaryHover = c.accent2,
    switchThumb = androidx.compose.ui.graphics.Color.White,
)

/** Light-theme panel tokens. */
val DshLightPanelColors = panelFrom(DshPorcelain)

/** Dark-theme panel tokens. */
val DshDarkPanelColors = panelFrom(DshGraphite)

/** Resolve the panel tokens for the current light/dark mode. */
@Composable
fun dshPanelColors(): DshPanelColors {
    val provided = LocalDshPanelColors.current
    // DshTheme задаёт значение по фактической теме; без него (по умолчанию light) тёмная система
    // всё равно получает тёмные цвета — как в превью и старых местах вызова.
    return if (provided.isDark || !isSystemInDarkTheme()) {
        provided
    } else {
        DshDarkPanelColors
    }
}

/** Panel palette for descendants; defaults to light so previews stay deterministic. */
val LocalDshPanelColors = staticCompositionLocalOf { DshLightPanelColors }
