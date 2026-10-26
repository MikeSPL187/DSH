package com.deepseek.dsh.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

// Окна приложения — один системный лист снизу (`.sheet` прототипа), отдельным окном Android,
// чтобы перекрывать всё, включая другие листы.

/** Ряд кнопок внизу листа: каждая [DshDialogButton] берёт равную долю ширины. */
private val LocalSheetFooter = staticCompositionLocalOf<RowScope?> { null }

/**
 * Лист поверх всего окна: затемнение, карточка снизу (радиус 34, s1, «ручка»), въезд пружиной.
 * Закрывают затемнение, смахивание вниз и «Назад» — с анимацией ухода.
 */
@Composable
private fun DshModalSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(1) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { progress.animateTo(1f, DshMotion.smooth()) }
    fun close() {
        if (leaving) return
        leaving = true
        scope.launch { progress.animateTo(0f, DshMotion.snappy()); onDismiss() }
    }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        NoWindowDim()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(c.scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::close),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight * 0.9f)
                    .onSizeChanged { height = it.height }
                    .graphicsLayer { translationY = (1f - progress.value) * height * 1.05f + drag.value }
                    .shadow(28.dp, RoundedCornerShape(34.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                    .clip(RoundedCornerShape(34.dp))
                    .background(c.s1)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        onDragStopped = { v -> if (drag.value > height * 0.25f || v > 1800f) close() else drag.animateTo(0f, DshMotion.snappy()) },
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 12.dp).size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.s3))
                CompositionLocalProvider(LocalContentColor provides c.t1) { content() }
            }
        }
    }
}

/** Лист для панелей плагинов (surface) — тот же системный лист. */
@Composable
fun DshSheetPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DshModalSheet(onDismiss, content)

/** Лист без готовых заголовка и кнопок — для окон со своей раскладкой. */
@Composable
fun DshHeadlessModal(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DshModalSheet(onDismiss, content)

/**
 * Окно приложения листом: заголовок `.sheet h3`, пояснение, содержимое и ряд кнопок на всю ширину.
 *
 * @param closeLabel как закрыть — для озвучивания (крестика у листа нет: закрывают фон и «ручка»).
 * @param footer кнопки [DshDialogButton] — делят ширину поровну.
 */
@Composable
fun DshDialog(
    onDismiss: () -> Unit,
    title: String,
    closeLabel: String,
    description: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Dsh.colors
    DshModalSheet(onDismiss) {
        Box(Modifier.semantics { paneTitle = title; contentDescription = closeLabel }) { DshSheetTitle(title) }
        if (description != null) {
            Text(
                description,
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, lineHeight = 21.sp),
                color = c.t2,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp).offset(y = (-6).dp),
            )
        }
        if (content != null) Column(Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { CompositionLocalProvider(LocalSheetFooter provides this) { footer() } }
        }
    }
}

/**
 * Кнопка листа, как `.btn`: высота 48, радиус 14; главная — индиго, вторая — s2,
 * [danger] — красная (необратимое действие). В ряду [DshDialog] кнопки делят ширину поровну.
 */
@Composable
fun DshDialogButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val c = Dsh.colors
    val interaction = remember { MutableInteractionSource() }
    val row = LocalSheetFooter.current
    val share = if (row != null) with(row) { Modifier.weight(1f) } else Modifier
    val (fill, ink) = when {
        !enabled -> c.s3 to c.t3
        danger -> c.dangerFill to Color.White
        primary -> c.accent to c.onAccent
        else -> c.s2 to c.t1
    }
    Box(
        modifier
            .then(share)
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * Поле ввода в листе: s2, радиус 16, высота от 52.
 *
 *
 * Для ключей и токенов не подходит: у поля ключа должны быть точки и кнопка «Показать»
 * (см. поля ключа в «Настройках» и на первом запуске).
 */
@Composable
fun DshTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 20.sp), color = c.t3, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            textStyle = TextStyle(
                fontFamily = DshFonts.body,
                color = c.t1,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

/** Убрать затемнение окна диалога: фон рисует сам лист. Окно появляется после первой композиции. */
@Composable
private fun NoWindowDim() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.post { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        onDispose { }
    }
}
