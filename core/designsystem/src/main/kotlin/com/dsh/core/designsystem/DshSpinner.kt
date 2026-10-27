package com.dsh.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Кольцо загрузки `.spin-w` прототипа: дуга 270°, оборот за 0,8 с.
 * Одно на всё приложение — экраны не рисуют своё.
 */
@Composable
fun DshSpinner(color: Color = Dsh.colors.accent, size: Dp = 16.dp, stroke: Dp = 2.dp, modifier: Modifier = Modifier, round: Boolean = true) {
    val t = rememberInfiniteTransition(label = "spin")
    val deg by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "spin-deg")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = deg }) {
        val w = stroke.toPx()
        drawArc(
            color, 0f, 270f, false,
            topLeft = Offset(w / 2, w / 2),
            size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = if (round) StrokeCap.Round else StrokeCap.Butt),
        )
    }
}
