package com.deepseek.dsh.core.designsystem

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI

/**
 * Цвета дизайн-системы DSH: «Фарфор» (светлая) и «Графит» (тёмная), акцент индиго.
 *
 * Значения совпадают с прототипом `docs/design/dsh-prototype.html` и проверены
 * на контраст WCAG AA (текст 4,5:1).
 *
 * @property bg фон экрана.
 * @property s1 карточки и группы поверх фона.
 * @property s2 заливка элементов внутри карточек (поля, сегменты, плитки).
 * @property s3 треки переключателей и полос прогресса.
 * @property t1 основной текст.
 * @property t2 второстепенный текст.
 * @property t3 подписи и значения справа.
 * @property line разделители и обводки.
 * @property accent индиго: основные кнопки, выбранное, ссылки.
 * @property accentSoft мягкая заливка выбранного.
 * @property onAccent текст на акценте.
 * @property dangerFill заливка кнопки опасного действия (белый текст проходит контраст).
 * @property glass полупрозрачная заливка плавающих элементов (поле ввода, док).
 */
@Immutable
data class DshColors(
    val isDark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val line: Color,
    val accent: Color,
    val accent2: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerFill: Color,
    val glass: Color,
    val glassLine: Color,
    val codeBg: Color,
    val scrim: Color,
)

val DshPorcelain = DshColors(
    isDark = false,
    bg = Color(0xFFF6F6F9),
    s1 = Color(0xFFFFFFFF),
    s2 = Color(0xFFEFEFF4),
    s3 = Color(0xFFE4E4EC),
    t1 = Color(0xFF14141B),
    t2 = Color(0xFF4A4B58),
    t3 = Color(0xFF686977),
    line = Color(0x17181830),
    accent = Color(0xFF5A4CF0),
    accent2 = Color(0xFF7B70FF),
    accentSoft = Color(0xFFECEBFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF0E7A50),
    okSoft = Color(0xFFE2F5EC),
    warn = Color(0xFF955400),
    warnSoft = Color(0xFFFDF1DC),
    danger = Color(0xFFC4323A),
    dangerSoft = Color(0xFFFDE8E8),
    dangerFill = Color(0xFFA1292F),
    glass = Color(0xB8FFFFFF),
    glassLine = Color(0xE6FFFFFF),
    codeBg = Color(0xFFF3F3F8),
    scrim = Color(0x57080814),
)

val DshGraphite = DshColors(
    isDark = true,
    bg = Color(0xFF0E0F12),
    s1 = Color(0xFF16171B),
    s2 = Color(0xFF1E1F24),
    s3 = Color(0xFF282930),
    t1 = Color(0xFFEDEDF2),
    t2 = Color(0xFFA9AAB6),
    t3 = Color(0xFF8E8F9A),
    line = Color(0x13FFFFFF),
    accent = Color(0xFF8B80FF),
    accent2 = Color(0xFFA69DFF),
    accentSoft = Color(0x268B80FF),
    onAccent = Color(0xFF0E0D1F),
    ok = Color(0xFF41C48B),
    okSoft = Color(0x2141C48B),
    warn = Color(0xFFEEAC55),
    warnSoft = Color(0x21E9A447),
    danger = Color(0xFFFF6B6F),
    dangerSoft = Color(0x21FF6B6F),
    dangerFill = Color(0xFFC2474B),
    glass = Color(0xB81E1F25),
    glassLine = Color(0x14FFFFFF),
    codeBg = Color(0xFF121317),
    scrim = Color(0x8C000000),
)

val LocalDshColors = staticCompositionLocalOf { DshPorcelain }

/** Шрифты: Golos Text — текст, Geologica — заголовки, JetBrains Mono — код. */
object DshFonts {
    val body = FontFamily(
        Font(R.font.golos_text_regular, FontWeight.Normal),
        Font(R.font.golos_text_medium, FontWeight.Medium),
        Font(R.font.golos_text_semibold, FontWeight.SemiBold),
    )
    val display = FontFamily(
        Font(R.font.geologica_medium, FontWeight.Medium),
        Font(R.font.geologica_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

/** Шкала текста: 12/13/15/17/20/24/32, ответ в чате — 16 с межстрочным 1,55. */
@Immutable
data class DshType(
    val largeTitle: TextStyle = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-0.02).em),
    val title: TextStyle = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.015).em),
    val headline: TextStyle = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    val navTitle: TextStyle = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    val body: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    val bodyMedium: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    val message: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.8.sp),
    val callout: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val footnote: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 16.sp),
    val caption: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 14.sp),
    val sectionLabel: TextStyle = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
    val code: TextStyle = TextStyle(fontFamily = DshFonts.mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    val codeSmall: TextStyle = TextStyle(fontFamily = DshFonts.mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
)

val LocalDshType = staticCompositionLocalOf { DshType() }

/** Сетка 4 dp, радиусы 10/16/24 и размеры касаемых зон. */
object DshSpace {
    val gutter: Dp = 16.dp
    val rowMin: Dp = 52.dp
    val touch: Dp = 44.dp
    val groupRadius: Dp = 18.dp
    val cardRadius: Dp = 22.dp
    val controlRadius: Dp = 14.dp
    val tileRadius: Dp = 9.dp
}

/**
 * Пружины, как в прототипе: затухание и время отклика переводятся в жёсткость
 * k = (2π / response)².
 *
 * - [smooth] — экраны, листы, появление контента (без отскока);
 * - [snappy] — нажатия, переключатели, сегменты;
 * - [lively] — заметные моменты с лёгким отскоком.
 */
object DshMotion {
    private fun stiffness(response: Double) = ((2 * PI / response) * (2 * PI / response)).toFloat()

    fun <T> smooth(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = stiffness(0.45))
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = stiffness(0.32))
    fun <T> lively(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = stiffness(0.5))
}

/** Доступ к токенам: `Dsh.colors.accent`, `Dsh.type.body`. */
object Dsh {
    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshColors.current
    val type: DshType
        @Composable @ReadOnlyComposable get() = LocalDshType.current
}
