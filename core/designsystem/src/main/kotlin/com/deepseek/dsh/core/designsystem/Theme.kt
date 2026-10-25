package com.deepseek.dsh.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Material-схема из токенов DSH: существующие экраны сразу получают новую палитру. */
private fun materialScheme(c: DshColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.t1,
        secondary = c.t2, onSecondary = c.bg, secondaryContainer = c.s2, onSecondaryContainer = c.t1,
        tertiary = c.ok, onTertiary = c.bg, tertiaryContainer = c.okSoft, onTertiaryContainer = c.t1,
        background = c.bg, onBackground = c.t1, surface = c.bg, onSurface = c.t1,
        surfaceVariant = c.s2, onSurfaceVariant = c.t2, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.s1, surfaceContainer = c.s1,
        surfaceContainerHigh = c.s2, surfaceContainerHighest = c.s3, surfaceBright = c.s2, surfaceDim = c.bg,
        outline = c.t3, outlineVariant = c.s3, error = c.danger, onError = Color.White,
        errorContainer = c.dangerSoft, onErrorContainer = c.t1, inverseSurface = c.t1, inverseOnSurface = c.bg,
        inversePrimary = c.accent2, scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.t1,
        secondary = c.t2, onSecondary = c.s1, secondaryContainer = c.s2, onSecondaryContainer = c.t1,
        tertiary = c.ok, onTertiary = Color.White, tertiaryContainer = c.okSoft, onTertiaryContainer = c.t1,
        background = c.bg, onBackground = c.t1, surface = c.bg, onSurface = c.t1,
        surfaceVariant = c.s2, onSurfaceVariant = c.t2, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.s1, surfaceContainerLow = c.s1, surfaceContainer = c.s1,
        surfaceContainerHigh = c.s2, surfaceContainerHighest = c.s3, surfaceBright = c.s1, surfaceDim = c.s2,
        outline = c.t3, outlineVariant = c.s3, error = c.danger, onError = Color.White,
        errorContainer = c.dangerSoft, onErrorContainer = c.t1, inverseSurface = c.t1, inverseOnSurface = c.bg,
        inversePrimary = c.accent2, scrim = Color.Black,
    )
}

/** Шрифты дизайн-системы для всех текстовых стилей Material. */
private fun materialTypography(): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = DshFonts.body)
    fun TextStyle.display() = copy(fontFamily = DshFonts.display)
    return base.copy(
        displayLarge = base.displayLarge.display(), displayMedium = base.displayMedium.display(), displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(), headlineMedium = base.headlineMedium.display(), headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.display(), titleMedium = base.titleMedium.body(), titleSmall = base.titleSmall.body(),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(), labelMedium = base.labelMedium.body(), labelSmall = base.labelSmall.body(),
    )
}

/**
 * Размер текста ленты сессии (sp) из `ui-theme.fontSize` Host.
 *
 * «Размер текста» в настройках меняет только содержимое диалога, а не весь интерфейс, поэтому
 * он передаётся тексту чата через CompositionLocal, а не через общую density.
 */
val LocalConversationFontSize = androidx.compose.runtime.compositionLocalOf { 14 }

/**
 * Тёмная ли тема сейчас (задаёт DshTheme).
 *
 * Раньше свои панели и настройки читали тему системы: при тёмной теме в Host и светлой
 * в телефоне цвета смешивались. Все собственные цвета берут это значение.
 */
val LocalDshDarkTheme = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // по умолчанию — тема системы
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DshGraphite else DshPorcelain
    // Панели и меню берут те же токены, чтобы старые и новые экраны не расходились по цвету.
    val panelColors = if (darkTheme) DshDarkPanelColors else DshLightPanelColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDshPanelColors provides panelColors,
        LocalDshDarkTheme provides darkTheme,
        LocalDshColors provides colors,
    ) {
        MaterialTheme(
            colorScheme = remember(darkTheme) { materialScheme(colors) },
            typography = remember { materialTypography() },
            content = content
        )
    }
}
