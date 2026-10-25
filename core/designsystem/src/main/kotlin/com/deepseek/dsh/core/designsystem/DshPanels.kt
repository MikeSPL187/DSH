package com.deepseek.dsh.core.designsystem

import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.ui.text.style.TextAlign
import android.view.Gravity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Shared panel system that mirrors the DeepSeek Harness web client.
 *
 * Every dropdown menu, context menu, bottom sheet and dialog surface in the app
 * uses these primitives so popups share one material (translucent menu fill,
 * hairline elevation stroke, soft shadow), one radius scale and one row rhythm
 * (menu rows 34dp / 13sp, section labels 11sp, dialogs from the Modal).
 *
 * The menu card is a translucent material with a backdrop blur; Android
 * cannot blur content behind an anchored popup window, so the exact fill,
 * stroke, radius, shadow, row and typography metrics are reproduced without the
 * blur layer.
 */
object DshPanelDefaults {
    /** Menu card radius (`--dsw-radius-lg`). */
    val Shape: Shape = RoundedCornerShape(16.dp)

    /** Compact menu card radius (`--dsw-radius-md`). */
    val CompactShape: Shape = RoundedCornerShape(12.dp)

    /** Gap between the anchor and the menu card (`.list` top offset). */
    val AnchorGap = 4.dp

    /** Menu card padding (`.list` 4px). */
    val ContentPadding = 4.dp

    /** Menu row min height (`.item` 34px). */
    val RowMinHeight = 34.dp

    /** Menu row horizontal padding (`.item` 8px). */
    val RowHorizontalPadding = 8.dp

    /** Menu row vertical padding (`.item` 6px). */
    val RowVerticalPadding = 6.dp

    /** Menu row corner radius (`--dsw-radius-md`). */
    val RowShape: Shape = RoundedCornerShape(12.dp)

    /** Gap between a row icon and its label (6px). */
    val RowGap = 6.dp

    /** Menu list minimum width (`.list` 144px). */
    val MenuMinWidth = 144.dp

    /** Menu list maximum width (`.list` 360px). */
    val MenuMaxWidth = 360.dp

    /** Menu list default maximum height used on a portrait phone. */
    val MenuMaxHeight = 440.dp

    /** Menu popup keeps this air around the card for its soft shadow. */
    val PopupShadowPadding = 12.dp

    /** Dialog card radius (`--dsw-radius-panel`). */
    val DialogShape: Shape = RoundedCornerShape(28.dp)

    /** Dialog card width (`min(380px, 100%)`). */
    val DialogWidth = 380.dp

    /** Wide dialog width used by the risk confirmation (`min(440px, 100%)`). */
    val WideDialogWidth = 440.dp

    /** Dialog air around the card (root 24px). */
    val DialogInset = 24.dp

    /** Dialog close control size (28x28). */
    val DialogCloseSize = 28.dp

    /** Dialog action height (Button md 36px). */
    val DialogButtonHeight = 36.dp
}
















/**
 * Menu placement: below the anchor when it fits, otherwise above;
 * clamped inside the viewport with a 12dp margin.
 */
private class DshMenuPositionProvider(
    private val gapPx: Int,
    private val shadowPx: Int,
    private val marginPx: Int,
    private val alignEnd: Boolean,
    private val preferredAbove: Boolean?,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cardWidth = (popupContentSize.width - 2 * shadowPx).coerceAtLeast(0)
        val cardHeight = (popupContentSize.height - 2 * shadowPx).coerceAtLeast(0)
        val desiredX = if (alignEnd) anchorBounds.right - cardWidth else anchorBounds.left
        val maxX = (windowSize.width - cardWidth - marginPx).coerceAtLeast(marginPx)
        val cardX = desiredX.coerceIn(marginPx, maxX)
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - cardHeight - gapPx
        // Do not clamp an above-anchor card to the window's bottom: edge-to-edge
        // activity anchors sit in decor coordinates while the popup window excludes
        // system bars, so a bottom clamp would push the card away from its trigger.
        val cardY = when (preferredAbove) {
            true -> above.coerceAtLeast(marginPx)
            false -> below.coerceAtLeast(marginPx)
            null -> when {
                below + cardHeight <= windowSize.height - marginPx -> below
                above >= marginPx -> above
                else -> below
            }.coerceAtLeast(marginPx)
        }
        return IntOffset(cardX - shadowPx, cardY - shadowPx)
    }
}

/**
 * Лист снизу для панелей плагинов (surface): тот же системный лист, что у окон.
 *
 * @param onDismiss закрыть.
 * @param content содержимое листа.
 */
@Composable
fun DshSheetPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DshModalSheet(onDismiss, content)

/** Ряд кнопок внизу листа: каждая кнопка [DshDialogButton] берёт равную долю ширины. */
private val LocalSheetFooter = androidx.compose.runtime.staticCompositionLocalOf<RowScope?> { null }

/**
 * Модальный лист системы (`.sheet` прототипа) поверх всего окна: затемнение, карточка
 * снизу (радиус 34, s1, «ручка»), въезд пружиной, смахивание вниз и «Назад» закрывают.
 */
@Composable
private fun DshModalSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Dsh.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    var height by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var leaving by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { progress.animateTo(1f, DshMotion.smooth()) }
    fun close() {
        if (leaving) return
        leaving = true
        scope.launch { progress.animateTo(0f, DshMotion.snappy()); onDismiss() }
    }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogWindowDim()
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
                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                        state = androidx.compose.foundation.gestures.rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
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

/**
 * Окно приложения в виде листа системы: заголовок `.sheet h3`, пояснение, содержимое
 * и ряд кнопок на всю ширину. Закрывается затемнением, смахиванием и «Назад».
 *
 * @param onDismiss закрыть.
 * @param title заголовок листа.
 * @param closeLabel подпись для озвучивания закрытия (у листа нет крестика — закрывают «ручка» и фон).
 * @param description пояснение под заголовком.
 * @param width не используется: лист занимает ширину экрана (до 560 dp).
 * @param content содержимое.
 * @param footer кнопки [DshDialogButton] — поровну по ширине.
 */
@Composable
fun DshDialog(
    onDismiss: () -> Unit,
    title: String,
    closeLabel: String,
    description: String? = null,
    @Suppress("UNUSED_PARAMETER") width: Dp = DshPanelDefaults.DialogWidth,
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
 * Лист без готовых заголовка и кнопок — для окон со своей раскладкой.
 *
 * @param onDismiss закрыть.
 * @param width не используется: ширина листа — экран (до 560 dp).
 * @param content содержимое листа.
 */
@Composable
fun DshHeadlessModal(
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") width: Dp = DshPanelDefaults.DialogWidth,
    content: @Composable ColumnScope.() -> Unit,
) = DshModalSheet(onDismiss, content)


/**
 * Кнопка листа, как `.btn` прототипа: высота 48, радиус 14, главная — индиго,
 * вторая — на s2. В ряду [DshDialog] кнопки делят ширину поровну.
 *
 * @param height минимальная высота (не меньше 48).
 */
@Composable
fun DshDialogButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DshPanelDefaults.DialogButtonHeight,
    /** Необратимое действие (удаление): красная кнопка. */
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
            .heightIn(min = maxOf(height, 48.dp))
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
 * Official switch (ui-primitives Switch): 36x20 capsule track, 16dp thumb,
 * border-l3 off track / brand-primary on track, label-foreground thumb.
 *
 * @param checked current state.
 * @param onCheckedChange called with the next state.
 * @param modifier external modifier.
 * @param enabled whether the switch can be toggled.
 */
@Composable
fun DshSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = dshPanelColors()
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        label = "dshSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) colors.buttonPrimaryFill else colors.borderL3)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) { onCheckedChange(!checked) }
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (checked) colors.labelPrimaryForeground else colors.switchThumb),
        )
    }
}

/**
 * Single-line input used inside dialogs and panels: 40dp height, 12dp
 * radius, 0.5dp border-l4, 13sp/20 text and caption placeholder.
 *
 * @param value current text.
 * @param onValueChange text change callback.
 * @param placeholder hint shown while empty.
 * @param modifier external modifier.
 */
@Composable
fun DshTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /**
     * Поле для секрета (API-ключ, токен): клавиатура без автозамены/подсказок/обучения
     * и скрытые символы. Иначе клавиатура «исправляет» ключ (sk-test… → «sky test»)
     * и запоминает его в своём словаре.
     */
    secret: Boolean = false,
) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.s2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 20.sp), color = c.t3, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            keyboardOptions = if (secret) {
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    autoCorrectEnabled = false,
                )
            } else {
                androidx.compose.foundation.text.KeyboardOptions.Default
            },
            visualTransformation = if (secret) {
                androidx.compose.ui.text.input.PasswordVisualTransformation('•')
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            textStyle = TextStyle(
                fontFamily = if (secret) DshFonts.mono else DshFonts.body,
                color = c.t1,
                fontSize = if (secret) 14.sp else 15.sp,
                lineHeight = 20.sp,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

/**
 * Turn off the platform dialog window dim so the mask fill is the only scrim.
 * An optional top inset pins the dialog window below the status bar, keeping
 * the activity's own system-bar appearance untouched. Falls through silently
 * when the host is not a dialog window provider.
 */
@Composable
private fun DisableDialogWindowDim(topInsetPx: Int = 0, fullHeightPx: Int = 0) {
    val view = LocalView.current
    DisposableEffect(view, topInsetPx, fullHeightPx) {
        val apply = {
            val provider = view.parent as? DialogWindowProvider
            val window = provider?.window
            if (window != null) {
                window.setDimAmount(0f)
                if (topInsetPx > 0) {
                    val attrs = window.attributes
                    attrs.gravity = Gravity.TOP
                    attrs.y = topInsetPx
                    if (fullHeightPx > topInsetPx) {
                        attrs.height = fullHeightPx - topInsetPx
                    }
                    window.attributes = attrs
                }
            }
        }
        // The dialog window is attached after the first composition pass.
        view.post(apply)
        onDispose { }
    }
}
