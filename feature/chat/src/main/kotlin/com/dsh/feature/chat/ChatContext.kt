package com.dsh.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshMotion
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.GLabel
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.model.ComposerStats
import kotlin.math.roundToLong

/** Заполненность контекста в подзаголовке шапки; нажатие на заголовок открывает лист «Контекст и расход». */
data class ChatContextChip(val percent: Int, val onClick: () -> Unit)

/** Уровень заполненности: от 80 % — жёлтый, от 95 % — красный (как `.ctx.warn` / `.ctx.danger`). */
internal enum class ContextLevel { OK, WARN, DANGER }

internal fun contextLevel(percent: Int): ContextLevel = when {
    percent >= 95 -> ContextLevel.DANGER
    percent >= 80 -> ContextLevel.WARN
    else -> ContextLevel.OK
}

@Composable
private fun levelColor(level: ContextLevel): Color = when (level) {
    ContextLevel.OK -> Dsh.colors.accent
    ContextLevel.WARN -> Dsh.colors.warn
    ContextLevel.DANGER -> Dsh.colors.danger
}

/** Кольцо `.ring`: дорожка t3 на 32 %, дуга по проценту с круглым концом, плавная смена. */
@Composable
internal fun ContextRing(percent: Int, color: Color, size: Dp = 12.dp) {
    val track = Dsh.colors.t3.copy(alpha = 0.32f)
    val sweep by animateFloatAsState(percent.coerceIn(2, 100) / 100f * 360f, DshMotion.smooth(), label = "ring")
    Canvas(Modifier.size(size)) {
        val stroke = 2.4.dp.toPx() * size.value / 12f
        val inset = stroke / 2
        val arc = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke))
        drawArc(color, -90f, sweep, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Подпись в подзаголовке шапки: кольцо и «41%» в цвете уровня. */
@Composable
internal fun ContextMini(percent: Int) {
    val level = contextLevel(percent)
    val color = if (level == ContextLevel.OK) Dsh.colors.t3 else levelColor(level)
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ContextRing(percent, levelColor(level))
        Text("$percent%", style = ckBody(12.sp, 14.4.sp), color = color, maxLines = 1)
    }
}

/** «412 тыс.», «1 млн», «640» — компактное число токенов по-русски и по-английски. */
@Composable
internal fun tokens(n: Long): String {
    fun one(v: Double): String = if (v >= 100) v.roundToLong().toString() else java.text.DecimalFormat("0.#").format(v)
    return when {
        n >= 1_000_000 -> stringResource(R.string.ck_tokens_m, one(n / 1_000_000.0))
        n >= 1_000 -> stringResource(R.string.ck_tokens_k, (n / 1000.0).roundToLong().toString())
        else -> n.toString()
    }
}

/** Цвета состава: инструкции — c4, инструменты — c7, разговор — акцент (`.k1`–`.k3`). */
@Composable
private fun partColors(): List<Color> = listOf(Si.c4, Si.c7, Dsh.colors.accent)

/**
 * Лист `#sheet-ctx`: заполненность крупно, полоса из трёх частей (длина — точный процент, части — эвристические доли),
 * легенда «≈», предупреждение от 80 %, расход за сессию, реальная трата за сегодня по балансу и «Сжать сейчас».
 *
 * @param spentToday трата за сегодня по балансу DeepSeek; null — баланс ещё не читали.
 */
@Composable
internal fun ColumnScope.ContextSheetBody(stats: ComposerStats, spentToday: String?, onMoney: () -> Unit, onCompact: () -> Unit) {
    val c = Dsh.colors
    val level = contextLevel(stats.contextPercent)
    DshSheetTitleText(stringResource(R.string.ctx_title))
    // `.cx-top`: margin −4 / 4 / 10 — число подтянуто к заголовку.
    Row(Modifier.offset(y = (-4).dp).padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "${stats.contextPercent}%", style = ckDisplay(32.sp, 35.sp),
            color = if (level == ContextLevel.OK) c.t1 else levelColor(level),
        )
        Text(
            stringResource(R.string.ctx_of, tokens(stats.contextUsed), tokens(stats.contextWindow)),
            style = ckBody(14.sp, 18.sp), color = c.t2, modifier = Modifier.padding(bottom = 5.dp),
        )
    }
    val parts = listOf(stats.systemTokens, stats.toolsTokens, stats.messageTokens)
    val sum = parts.sum()
    val shares = if (sum > 0) parts.map { it.toFloat() / sum } else listOf(0f, 0f, 1f)
    val colors = partColors()
    val bar = stringResource(R.string.ctx_bar, stats.contextPercent)
    Row(
        Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(c.s2)
            .semantics { contentDescription = bar },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Длина полосы — точный процент; части делят её по эвристическим долям. Крошечная часть остаётся видимой.
        val total = stats.contextPercent / 100f
        shares.forEachIndexed { i, share ->
            if (share > 0f) Box(Modifier.weight((total * share).coerceAtLeast(0.008f)).fillMaxHeight().background(colors[i]))
        }
        if (total < 0.999f) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f - total))
    }
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
        listOf(R.string.ctx_part_system, R.string.ctx_part_tools, R.string.ctx_part_messages).forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[i]))
                Text(stringResource(label), style = ckBody(14.sp, 18.sp), color = c.t2, modifier = Modifier.weight(1f))
                Text(
                    "≈ " + tokens((stats.contextUsed * shares[i]).roundToLong()),
                    style = ckBody(14.sp, 18.sp, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), color = c.t1,
                )
            }
        }
    }
    if (level != ContextLevel.OK) {
        Text(
            stringResource(if (level == ContextLevel.DANGER) R.string.ctx_note_danger else R.string.ctx_note_warn),
            style = ckBody(13.5.sp, 19.5.sp), color = c.warn,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warnSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    GLabel(stringResource(R.string.ctx_usage))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 2.dp)) {
        val input = stats.uncachedInputTokens + stats.cacheReadTokens + stats.cacheWriteTokens
        val hit = if (input > 0) kotlin.math.round(stats.cacheReadTokens * 100.0 / input).toInt() else 0
        UsageRow(stringResource(R.string.ctx_in_new), null, tokens(stats.uncachedInputTokens + stats.cacheWriteTokens))
        HorizontalDivider(color = c.line)
        UsageRow(stringResource(R.string.ctx_in_cache), stringResource(R.string.ctx_in_cache_sub), "${tokens(stats.cacheReadTokens)} · $hit%")
        HorizontalDivider(color = c.line)
        UsageRow(stringResource(R.string.ctx_out), null, tokens(stats.outputTokens))
        HorizontalDivider(color = c.line)
        UsageRow(stringResource(R.string.ctx_total), null, tokens(stats.totalTokens))
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp)).background(c.s2)
            .clickable(role = Role.Button, onClick = onMoney).padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Si.c3), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Key, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (spentToday != null) R.string.ctx_money else R.string.ctx_money_none), style = ckBody(14.5.sp, 19.sp), color = c.t1)
            Text(stringResource(R.string.ctx_money_sub), style = ckBody(12.5.sp, 16.sp), color = c.t3)
        }
        if (spentToday != null) Text(spentToday, style = ckBody(14.sp, 18.sp), color = c.t3, maxLines = 1)
        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
    }
    KBtn(
        stringResource(R.string.ctx_compact), onCompact, Modifier.fillMaxWidth().padding(top = 14.dp),
        kind = BtnKind.Primary, icon = DshGlyphs.Box, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = stats.contextPercent >= 5,
    )
    Text(
        stringResource(R.string.ctx_compact_hint), style = ckBody(12.5.sp, 18.sp), color = c.t3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp),
    )
}

@Composable
private fun UsageRow(label: String, sub: String?, value: String) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = ckBody(14.5.sp, 19.sp), color = c.t1)
            if (sub != null) Text(sub, style = ckBody(12.5.sp, 16.sp), color = c.t3)
        }
        Text(value, style = ckBody(14.5.sp, 19.sp, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), color = c.t1, maxLines = 1)
    }
}
