package com.deepseek.dsh.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Экран с листом снизу, как `.sheet` в прототипе.
 *
 * Пока лист открыт, экран под ним уменьшается до 0,94 и скругляется (28 dp),
 * сверху ложится затемнение; лист въезжает пружиной, смахивается вниз,
 * закрывается затемнением и системной «Назад».
 *
 * @param sheet содержимое открытого листа; null — лист закрыт.
 * @param onDismiss закрыть лист.
 * @param content экран под листом.
 */
@Composable
fun DshSheetScaffold(
    sheet: (@Composable ColumnScope.() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Dsh.colors
    // Последний лист держим, пока он уезжает вниз.
    var shown by remember { mutableStateOf(sheet) }
    if (sheet != null) shown = sheet
    val open = sheet != null
    val progress by animateFloatAsState(if (open) 1f else 0f, DshMotion.smooth(), label = "sheet", finishedListener = { if (it == 0f) shown = null })
    BackHandler(enabled = open, onBack = onDismiss)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.06f * progress
                    scaleX = s
                    scaleY = s
                    shape = RoundedCornerShape((28 * progress).dp)
                    clip = progress > 0f
                }
                .background(c.bg),
            content = content,
        )
        val body = shown
        if (body != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
                    .background(Color(0x57080814))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                SheetCard(progress, maxHeight * 0.86f, onDismiss, body)
            }
        }
    }
}

@Composable
private fun BoxScope.SheetCard(progress: Float, maxHeight: androidx.compose.ui.unit.Dp, onDismiss: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val c = Dsh.colors
    var height by remember { mutableStateOf(1) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(progress == 1f) { if (progress == 1f) drag.snapTo(0f) }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = maxHeight)
            .onSizeChanged { height = it.height }
            .graphicsLayer { translationY = (1f - progress) * (height * 1.05f) + drag.value }
            .shadow(28.dp, RoundedCornerShape(34.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(34.dp))
            .background(c.s1)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (drag.value > height * 0.25f || v > 1800f) onDismiss() else drag.animateTo(0f, DshMotion.snappy())
                },
            )
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
    ) {
        // `.grab`
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 12.dp).size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.s3))
        body()
    }
}

/** `.sheet h3`: заголовок листа. */
@Composable
fun DshSheetTitle(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
        color = Dsh.colors.t1,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
    )
}

/** `.list`: группа строк на подложке s2. */
@Composable
fun DshSheetList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dsh.colors.s2), content = content)
}

/**
 * `.li` с плиткой `.lic`: иконка 32 dp на s1, текст 15 sp, необязательная подпись.
 * @param first первая строка в списке — без разделителя сверху.
 * @param danger красная строка (удаление).
 */
@Composable
fun DshSheetItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    first: Boolean = false,
    sub: String? = null,
    danger: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Dsh.colors
    val ink = if (danger) c.danger else c.t1
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.s1), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(text, style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 20.sp), color = ink)
                if (sub != null) {
                    Text(sub, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.sp), color = c.t3, modifier = Modifier.padding(top = 1.dp))
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** `.srcs` / `.src`: ряд плиток-источников 64 dp. */
@Composable
fun DshSourceTiles(items: List<Pair<ImageVector, String>>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (icon, label) ->
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.s2)
                    .clickable(interaction, ripple(), role = Role.Button) { onClick(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                Icon(icon, contentDescription = null, tint = c.t1, modifier = Modifier.size(18.dp))
                Text(label, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp), color = c.t2, maxLines = 1)
            }
        }
    }
}

/** `.mhint`: подсказка мелким текстом по центру. */
@Composable
fun DshSheetHint(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 17.sp),
        color = Dsh.colors.t3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}
