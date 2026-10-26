package com.deepseek.dsh.feature.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshButton
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.DshSwitch
import com.deepseek.dsh.core.designsystem.pressScale
import kotlinx.coroutines.delay

/** Чей API подключается на шаге «Подключите модель». */
enum class OnboardingProvider { DEEPSEEK, CUSTOM }

/** Итог проверки ключа. */
sealed interface KeyCheck {
    /** Ключ работает; [balance] — остаток DeepSeek, [models] — модели своего API. */
    data class Ok(val balance: String? = null, val models: List<String> = emptyList()) : KeyCheck
    data object Bad : KeyCheck
    data object Offline : KeyCheck
}

/** Ключ, который нужно сохранить, когда Host будет готов. */
data class OnboardingKey(
    val provider: OnboardingProvider,
    val baseUrl: String,
    val key: String,
    val models: List<String>,
)

/**
 * Состояние установки Linux для шага 3.
 *
 * @property progress общий прогресс 0..1.
 * @property stage текущий этап: 0 — загрузка, 1 — распаковка, 2 — проверка (запуск Host).
 * @property downloadPercent скачано, %; [downloadedBytes] из [totalBytes] — если размер известен.
 * @property unpackPercent распаковано, %.
 * @property etaSeconds сколько осталось, null — пока неизвестно.
 * @property done Linux установлен и Host отвечает.
 * @property error понятная причина ошибки; null — ошибок нет.
 * @property errorDetail техническая подробность мелким шрифтом.
 */
@Immutable
data class LinuxInstall(
    val progress: Float = 0f,
    val stage: Int = 0,
    val downloadPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val unpackPercent: Int = 0,
    val etaSeconds: Int? = null,
    val done: Boolean = false,
    val error: String? = null,
    val errorDetail: String? = null,
)

/** Языки интерфейса в переключателе сверху: тег и самоназвание. */
private val Languages = listOf("ru" to "Русский", "en" to "English")

/**
 * Первый запуск DSH — точная копия экрана `#onboard` из прототипа:
 * приветствие → «Подключите модель» → «Где хранить проекты» → «Устанавливаю Linux».
 *
 * Экран ничего не сохраняет сам: ключ, папку и установку ведёт приложение через колбэки.
 *
 * @param install ход установки Linux, идёт в фоне с первой секунды.
 * @param sharedAccessGranted дан ли доступ ко всем файлам (для варианта «В памяти телефона»).
 * @param checkKey проверка ключа у провайдера (сеть), вызывается после паузы в наборе.
 * @param onWhereKey открыть страницу получения ключа.
 * @param onChoosePrivate выбрана приватная папка внутри DSH.
 * @param onRequestShared запросить доступ ко всем файлам.
 * @param onRetryInstall повторить установку после ошибки.
 * @param notify включено ли уведомление о готовности; [onNotifyChange] — переключение.
 * @param language текущий язык (`ru`/`en`); [onLanguage] — выбор другого.
 * @param onRestoreBackup восстановление из резервной копии; null — кнопки нет.
 * @param onFinish «Начать работу»: ключ (или null, если пропущен).
 */
@Composable
fun OnboardingScreen(
    install: LinuxInstall,
    sharedAccessGranted: Boolean,
    checkKey: suspend (OnboardingProvider, String, String) -> KeyCheck,
    onWhereKey: () -> Unit,
    onChoosePrivate: () -> Unit,
    onRequestShared: () -> Unit,
    onRetryInstall: () -> Unit,
    notify: Boolean,
    onNotifyChange: (Boolean) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onFinish: (OnboardingKey?) -> Unit,
    modifier: Modifier = Modifier,
    onRestoreBackup: (() -> Unit)? = null,
) {
    val c = Dsh.colors
    var step by rememberSaveable { mutableStateOf(0) }
    var dir by remember { mutableStateOf(1) }
    // Ключ в сохранённое состояние не кладём: оно может попасть на диск.
    var provider by rememberSaveable { mutableStateOf(OnboardingProvider.DEEPSEEK) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<KeyCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var shared by rememberSaveable { mutableStateOf(false) }
    var waitingShared by rememberSaveable { mutableStateOf(false) }

    fun go(to: Int) {
        dir = if (to > step) 1 else -1
        step = to
    }

    val urlOk = provider == OnboardingProvider.DEEPSEEK || baseUrl.trim().startsWith("https://")
    // Адрес своего API, введённый до конца (поле адреса потеряло фокус или нажато «Готово»).
    // Ключ уходит только на него: пауза в наборе `https://openai.co…` отправила бы ключ на чужой домен.
    var committedUrl by rememberSaveable { mutableStateOf("") }
    // Проверка ключа: через 400 мс после последнего изменения, как в прототипе.
    val checkFn by rememberUpdatedState(checkKey)
    LaunchedEffect(provider, baseUrl, committedUrl, key) {
        check = null
        checking = false
        if (key.isBlank() || !urlOk || (provider == OnboardingProvider.CUSTOM && baseUrl.isBlank())) return@LaunchedEffect
        if (provider == OnboardingProvider.CUSTOM && baseUrl.trim() != committedUrl) return@LaunchedEffect
        delay(400)
        checking = true
        check = runCatching { checkFn(provider, baseUrl.trim(), key.trim()) }.getOrDefault(KeyCheck.Offline)
        checking = false
    }
    val keyAccepted = !checking && (check is KeyCheck.Ok || (check == KeyCheck.Offline && key.trim().length >= 16))

    // Доступ ко всем файлам выдаётся в системных настройках: вернулись с ним — идём дальше.
    LaunchedEffect(sharedAccessGranted, waitingShared) {
        if (waitingShared && sharedAccessGranted && step == 2) {
            waitingShared = false
            go(3)
        }
    }

    BackHandler(enabled = step > 0) { go(step - 1) }

    val primaryText: String
    val primaryEnabled: Boolean
    val onPrimary: () -> Unit
    when (step) {
        0 -> { primaryText = stringResource(R.string.ob_start); primaryEnabled = true; onPrimary = { go(1) } }
        1 -> { primaryText = stringResource(R.string.ob_continue); primaryEnabled = keyAccepted; onPrimary = { go(2) } }
        2 -> {
            val ask = shared && !sharedAccessGranted
            primaryText = stringResource(if (ask) R.string.ob_allow else R.string.ob_continue)
            primaryEnabled = true
            onPrimary = {
                when {
                    !shared -> { onChoosePrivate(); go(3) }
                    sharedAccessGranted -> go(3)
                    else -> { waitingShared = true; onRequestShared() }
                }
            }
        }
        else -> {
            primaryText = stringResource(
                when {
                    install.error != null -> R.string.ob_retry
                    install.done -> R.string.ob_begin
                    else -> R.string.ob_installing
                },
            )
            primaryEnabled = install.done || install.error != null
            onPrimary = {
                if (install.error != null) {
                    onRetryInstall()
                } else {
                    val saved = key.trim().takeIf { it.isNotEmpty() && keyAccepted }?.let {
                        OnboardingKey(provider, baseUrl.trim(), it, (check as? KeyCheck.Ok)?.models.orEmpty())
                    }
                    onFinish(saved)
                }
            }
        }
    }

    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar(step, language, onLanguage, onBack = { go(step - 1) })
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = dir > 0
                (slideInHorizontally(DshMotion.smooth()) { w -> if (forward) w else -w }) togetherWith
                    (slideOutHorizontally(DshMotion.smooth()) { w -> if (forward) -w * 3 / 10 else w * 3 / 10 } + fadeOut(DshMotion.smooth()))
            },
            label = "ob-step",
        ) { current ->
            StepPage(current) {
                when (current) {
                    0 -> Hero()
                    1 -> ModelStep(
                        provider = provider,
                        onProvider = { provider = it },
                        baseUrl = baseUrl,
                        onBaseUrl = { baseUrl = it },
                        onBaseUrlCommit = { committedUrl = baseUrl.trim() },
                        key = key,
                        onKey = { key = it },
                        urlOk = urlOk,
                        checking = checking,
                        check = check,
                        onWhereKey = onWhereKey,
                        dir = dir,
                    )
                    2 -> WorkspaceStep(shared, { shared = it; waitingShared = false }, sharedAccessGranted, dir)
                    else -> LinuxStep(install, notify, onNotifyChange, dir)
                }
            }
        }
        Bottom(
            primaryText = primaryText,
            primaryEnabled = primaryEnabled,
            onPrimary = onPrimary,
            altText = when (step) {
                0 -> if (onRestoreBackup != null) stringResource(R.string.ob_restore) else null
                1 -> stringResource(R.string.ob_skip_key)
                else -> null
            },
            onAlt = { if (step == 0) onRestoreBackup?.invoke() else { key = ""; go(2) } },
        )
    }
}

// ---------------------------------------------------------------------------
// Шапка и низ
// ---------------------------------------------------------------------------

/** `.ob-top`: «Назад», индикатор шагов и язык. */
@Composable
private fun TopBar(step: Int, language: String, onLanguage: (String) -> Unit, onBack: () -> Unit) {
    val c = Dsh.colors
    val backAlpha by animateFloatAsState(if (step > 0) 1f else 0f, tween(250), label = "ob-back")
    val dotsAlpha by animateFloatAsState(if (step > 0) 1f else 0f, tween(300), label = "ob-dots")
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val backInteraction = remember { MutableInteractionSource() }
        val backLabel = stringResource(R.string.ob_back)
        Box(
            Modifier
                .size(44.dp)
                .graphicsLayer { alpha = backAlpha }
                .pressScale(backInteraction, 0.9f)
                .clip(CircleShape)
                .clickable(backInteraction, ripple(), enabled = step > 0, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(DshGlyphs.Left, contentDescription = null, tint = c.t1, modifier = Modifier.size(22.dp))
        }
        Row(
            Modifier.weight(1f).graphicsLayer { alpha = dotsAlpha },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            for (i in 0 until 3) {
                val on = i == step - 1
                val width by animateDpAsState(if (on) 34.dp else 22.dp, DshMotion.snappy(), label = "ob-dot")
                val color by animateColorAsState(
                    when {
                        on -> c.accent
                        i < step - 1 -> lerp(c.s3, c.accent, 0.45f)
                        else -> c.s3
                    },
                    tween(350),
                    label = "ob-dot-c",
                )
                Box(Modifier.size(width = width, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
            }
        }
        LanguagePill(language, onLanguage)
    }
}

/** `.ob-lang`: текущий язык, по нажатию — список. */
@Composable
private fun LanguagePill(language: String, onLanguage: (String) -> Unit) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val name = Languages.firstOrNull { it.first == language }?.second ?: Languages.first().second
    Box(Modifier.padding(end = 6.dp)) {
        Row(
            Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(c.s2)
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp), color = c.t2)
            Icon(DshGlyphs.xs("chev"), contentDescription = null, tint = c.t2, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.s1,
            shape = RoundedCornerShape(16.dp),
        ) {
            Languages.forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = Dsh.type.body, color = c.t1) },
                    trailingIcon = if (tag == language) {
                        { Icon(DshGlyphs.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { open = false; if (tag != language) onLanguage(tag) },
                )
            }
        }
    }
}

/** `.ob-bottom`: главная кнопка и вторая строкой под ней (место держится всегда). */
@Composable
private fun Bottom(primaryText: String, primaryEnabled: Boolean, onPrimary: () -> Unit, altText: String?, onAlt: () -> Unit) {
    val c = Dsh.colors
    // Текст второй кнопки запоминаем, чтобы он не пропадал раньше, чем погаснет.
    var lastAlt by remember { mutableStateOf(altText.orEmpty()) }
    if (altText != null) lastAlt = altText
    val altAlpha by animateFloatAsState(if (altText != null) 1f else 0f, tween(250), label = "ob-alt")
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(primaryEnabled) {
        if (primaryEnabled) {
            pulse.snapTo(0.97f)
            pulse.animateTo(1f, DshMotion.lively())
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 10.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        DshButton(
            primaryText,
            onPrimary,
            Modifier.fillMaxWidth().graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
            enabled = primaryEnabled,
            big = true,
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer { alpha = altAlpha }
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = altText != null, role = Role.Button, onClick = onAlt),
            contentAlignment = Alignment.Center,
        ) {
            Text(lastAlt, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.5.sp), color = c.t2)
        }
    }
}

// ---------------------------------------------------------------------------
// Шаги
// ---------------------------------------------------------------------------

/** `.ob-step`: прокрутка, поля 18/22/20. */
@Composable
private fun StepPage(step: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 20.dp),
        content = content,
    )
}

/** Появление элемента шага: сдвиг на 24 dp по направлению и проявление, по очереди (60 + 35·k мс). */
@Composable
private fun Modifier.enter(order: Int, dir: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + order * 35L)
        p.animateTo(1f, DshMotion.smooth())
    }
    val shift = with(LocalDensity.current) { 24.dp.toPx() } * dir
    return graphicsLayer { alpha = p.value; translationX = (1f - p.value) * shift }
}

private val H1 = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em)
private val H2 = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 31.sp, letterSpacing = (-0.02).em)
private val Lead = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 22.5.sp)
private val Small = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, lineHeight = 19.6.sp)

/** Шаг 0: знак, название, назначение и три возможности. */
@Composable
private fun ColumnScope.Hero() {
    val c = Dsh.colors
    val density = LocalDensity.current
    // Знак: из 0,6 с поворотом −12° в обычный вид пружиной с отскоком (задержка 120 мс).
    val mark = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(120); mark.animateTo(1f, DshMotion.lively()) }
    @Composable
    fun Modifier.rise(k: Int): Modifier {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) { delay(260L + k * 70L); p.animateTo(1f, DshMotion.smooth()) }
        val dy = with(density) { 14.dp.toPx() }
        return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * dy }
    }
    Spacer(Modifier.height(34.dp))
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .graphicsLayer {
                val v = mark.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
                rotationZ = -12f * (1f - v)
            }
            .shadow(28.dp, RoundedCornerShape(26.dp), ambientColor = c.accent.copy(alpha = 0.4f), spotColor = c.accent.copy(alpha = 0.4f))
            .size(84.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(c.accent2, c.accent), start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), endY = 6f), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DshGlyphs.Dsh, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(22.dp))
    Text(
        "DSH",
        style = H1,
        color = c.t1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() }.rise(0),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.ob_tagline),
        style = TextStyle(fontFamily = DshFonts.body, fontSize = 16.sp, lineHeight = 24.sp),
        color = c.t2,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp).rise(1),
    )
    Spacer(Modifier.height(38.dp))
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Feature(DshGlyphs.Term, R.string.ob_feat_linux, R.string.ob_feat_linux_sub, Modifier.rise(2))
        Feature(DshGlyphs.Spark, R.string.ob_feat_agent, R.string.ob_feat_agent_sub, Modifier.rise(3))
        Feature(DshGlyphs.Shield, R.string.ob_feat_control, R.string.ob_feat_control_sub, Modifier.rise(4))
    }
}

/** `.ob-feat`: плитка 38 и две строки. */
@Composable
private fun Feature(icon: ImageVector, title: Int, sub: Int, modifier: Modifier) {
    val c = Dsh.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 20.sp), color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(sub), style = Small, color = c.t2)
        }
    }
}

/** «Шаг N из 3», заголовок и вводный абзац. */
@Composable
private fun StepHead(n: Int, title: String, lead: String, dir: Int) {
    val c = Dsh.colors
    Text(
        stringResource(R.string.ob_step, n, 3),
        style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp),
        color = c.accent,
        modifier = Modifier.padding(top = 6.dp).enter(0, dir),
    )
    Spacer(Modifier.height(6.dp))
    Text(title, style = H2, color = c.t1, modifier = Modifier.semantics { heading() }.enter(1, dir))
    Spacer(Modifier.height(8.dp))
    Text(lead, style = Lead, color = c.t2, modifier = Modifier.enter(2, dir))
    Spacer(Modifier.height(22.dp))
}

/** Шаг 1: провайдер, ключ скрыт точками, «Показать» и «Вставить», проверка ключа. */
@Composable
private fun ModelStep(
    provider: OnboardingProvider,
    onProvider: (OnboardingProvider) -> Unit,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    onBaseUrlCommit: () -> Unit,
    key: String,
    onKey: (String) -> Unit,
    urlOk: Boolean,
    checking: Boolean,
    check: KeyCheck?,
    onWhereKey: () -> Unit,
    dir: Int,
) {
    val c = Dsh.colors
    val clipboard = LocalClipboardManager.current
    StepHead(1, stringResource(R.string.ob_model_title), stringResource(R.string.ob_model_lead), dir)
    // `.ob-prov`: два варианта на подложке s2.
    Row(
        Modifier
            .enter(3, dir)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.s2)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(OnboardingProvider.DEEPSEEK to "DeepSeek", OnboardingProvider.CUSTOM to stringResource(R.string.ob_prov_custom)).forEach { (p, label) ->
            val on = p == provider
            val bg by animateColorAsState(if (on) c.s1 else Color.Transparent, tween(250), label = "ob-prov")
            val ink by animateColorAsState(if (on) c.t1 else c.t2, tween(250), label = "ob-prov-t")
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f)) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .selectable(on, role = Role.RadioButton) { onProvider(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal), color = ink)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    val custom = provider == OnboardingProvider.CUSTOM
    Column(Modifier.enter(4, dir)) {
        Text(
            stringResource(if (custom) R.string.ob_key_label_custom else R.string.ob_key_label),
            style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 17.sp),
            color = c.t2,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
        )
        if (custom) {
            Field(
                value = baseUrl,
                onValue = onBaseUrl,
                placeholder = stringResource(R.string.ob_url_ph),
                icon = DshGlyphs.Globe,
                secret = false,
                ok = false,
                keyboard = KeyboardType.Uri,
                onCommit = onBaseUrlCommit,
            )
            Spacer(Modifier.height(8.dp))
        }
        Field(
            value = key,
            onValue = onKey,
            placeholder = if (custom) stringResource(R.string.ob_key_ph_custom) else "sk-…",
            icon = DshGlyphs.Key,
            secret = true,
            ok = check is KeyCheck.Ok,
            keyboard = KeyboardType.Password,
            onPaste = {
                // Вставленный ключ не остаётся в буфере обмена: его читают клавиатура и другие приложения.
                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { onKey(it); clipboard.setText(androidx.compose.ui.text.AnnotatedString("")) }
            },
        )
    }
    // `.ob-state`: где хранится ключ, проверка, итог.
    val bad = check == KeyCheck.Bad || (custom && baseUrl.isNotBlank() && !urlOk)
    val stateColor = when {
        bad -> c.danger
        check is KeyCheck.Ok -> c.ok
        checking -> c.t2
        else -> c.t3
    }
    Row(
        Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp).heightIn(min = 20.dp).enter(5, dir),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when {
            checking -> Spinner(13.dp, 1.75.dp, stateColor)
            bad -> Icon(DshGlyphs.xs("alert"), null, tint = stateColor, modifier = Modifier.size(15.dp))
            check is KeyCheck.Ok -> Icon(DshGlyphs.xs("check"), null, tint = stateColor, modifier = Modifier.size(15.dp))
            else -> Icon(DshGlyphs.xs("lock"), null, tint = stateColor, modifier = Modifier.size(15.dp))
        }
        val text = when {
            custom && baseUrl.isNotBlank() && !urlOk -> stringResource(R.string.ob_url_bad)
            checking -> stringResource(R.string.ob_key_busy)
            check == KeyCheck.Bad -> stringResource(R.string.ob_key_bad)
            check == KeyCheck.Offline -> stringResource(R.string.ob_key_offline)
            check is KeyCheck.Ok && check.balance != null -> stringResource(R.string.ob_key_ok_balance, check.balance)
            check is KeyCheck.Ok && check.models.isNotEmpty() -> stringResource(R.string.ob_key_ok_models, check.models.size)
            check is KeyCheck.Ok -> stringResource(R.string.ob_key_ok)
            else -> stringResource(R.string.ob_key_idle)
        }
        Text(text, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.sp, lineHeight = 17.sp), color = stateColor)
    }
    if (!custom) {
        // `.ob-link`: где взять ключ.
        Row(
            Modifier
                .padding(top = 12.dp)
                .enter(6, dir)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onWhereKey)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.ob_where), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.5.sp), color = c.accent)
            Icon(DshGlyphs.xs("right"), null, tint = c.accent, modifier = Modifier.size(15.dp))
        }
    }
}

/** `.ob-in`: поле 54 dp; секрет скрыт точками, есть «Показать» и «Вставить». */
@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    secret: Boolean,
    ok: Boolean,
    keyboard: KeyboardType,
    onPaste: (() -> Unit)? = null,
    onCommit: (() -> Unit)? = null,
) {
    val c = Dsh.colors
    var focused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val edge by animateColorAsState(
        when {
            focused -> c.accent
            ok -> lerp(c.line, c.ok, 0.6f)
            else -> c.line
        },
        tween(250),
        label = "ob-in-edge",
    )
    val ring by animateColorAsState(if (focused) c.accentSoft else Color.Transparent, tween(250), label = "ob-in-ring")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().outerRing(ring, 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(shape)
                .background(c.s1)
                .border(1.5.dp, edge, shape)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = c.t3, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val style = TextStyle(fontFamily = DshFonts.mono, fontSize = 15.sp, color = c.t1)
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.t3), maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = { onValue(it.replace("\n", "").replace(" ", "")) },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(c.accent),
                    visualTransformation = if (secret && !shown) PasswordVisualTransformation('•') else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit?.invoke(); defaultKeyboardAction(ImeAction.Done) }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (focused && !it.isFocused) onCommit?.invoke()
                        focused = it.isFocused
                    },
                )
            }
            if (secret) {
                val label = stringResource(if (shown) R.string.ob_hide_key else R.string.ob_show_key)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { shown = !shown }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DshGlyphs.Eye, null, tint = if (shown) c.accent else c.t3, modifier = Modifier.size(18.dp))
                }
            }
            if (onPaste != null) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .height(40.dp)
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accentSoft)
                        .clickable(interaction, ripple(), role = Role.Button, onClick = onPaste)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.ob_paste), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = c.accent)
                }
            }
        }
    }
}

/** Шаг 2: приватная папка или общая память телефона. */
@Composable
private fun WorkspaceStep(shared: Boolean, onShared: (Boolean) -> Unit, granted: Boolean, dir: Int) {
    StepHead(2, stringResource(R.string.ob_ws_title), stringResource(R.string.ob_ws_lead), dir)
    WorkspaceOptions(shared, onShared, granted, dir)
}

/** Два варианта папки и предупреждение о разрешении. */
@Composable
private fun WorkspaceOptions(shared: Boolean, onShared: (Boolean) -> Unit, granted: Boolean, dir: Int) {
    val c = Dsh.colors
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Option(
            title = stringResource(R.string.ob_ws_private),
            badge = stringResource(R.string.ob_recommended),
            sub = stringResource(R.string.ob_ws_private_sub),
            icon = DshGlyphs.Lock,
            selected = !shared,
            onClick = { onShared(false) },
            modifier = Modifier.enter(3, dir),
        )
        Option(
            title = stringResource(R.string.ob_ws_shared),
            badge = null,
            sub = stringResource(R.string.ob_ws_shared_sub),
            icon = DshGlyphs.Folder,
            selected = shared,
            onClick = { onShared(true) },
            modifier = Modifier.enter(4, dir),
        )
    }
    // `.ob-note`: место под предупреждение держится, видно только при общей папке без доступа.
    val noteAlpha by animateFloatAsState(if (shared && !granted) 1f else 0f, tween(250), label = "ob-note")
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .graphicsLayer { alpha = noteAlpha }
            .clip(RoundedCornerShape(14.dp))
            .background(c.warnSoft)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DshGlyphs.xs("shield"), null, tint = c.warn, modifier = Modifier.size(15.dp))
        Text(stringResource(R.string.ob_ws_note), style = Small, color = c.warn)
    }
}

/** `.ob-opt`: радиокнопка, заголовок с плашкой, описание и плитка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Option(
    title: String,
    badge: String?,
    sub: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    val edge by animateColorAsState(if (selected) c.accent else c.line, tween(250), label = "ob-opt-edge")
    val ring by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, tween(250), label = "ob-opt-ring")
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth().pressScale(interaction, 0.98f).outerRing(ring, 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(c.s1)
                .border(1.5.dp, edge, RoundedCornerShape(20.dp))
                .selectable(selected, interaction, ripple(), role = Role.RadioButton, onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Radio(selected)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp), color = c.t1)
                    if (badge != null) {
                        Text(
                            badge,
                            style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 15.sp),
                            color = c.accent,
                            modifier = Modifier.align(Alignment.CenterVertically).clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(sub, style = Small, color = c.t2)
            }
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.s2), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.t2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val c = Dsh.colors
    val edge by animateColorAsState(if (selected) c.accent else c.t3, tween(200), label = "ob-radio")
    val dot by animateFloatAsState(if (selected) 1f else 0f, DshMotion.lively(), label = "ob-radio-dot")
    Box(
        Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape).border(1.75.dp, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(11.dp).graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot.coerceIn(0f, 1f) }.clip(CircleShape).background(c.accent))
    }
}

/** Шаг 3: ход установки Linux, этапы и уведомление о готовности. */
@Composable
private fun LinuxStep(install: LinuxInstall, notify: Boolean, onNotifyChange: (Boolean) -> Unit, dir: Int) {
    val c = Dsh.colors
    val title = when {
        install.error != null -> R.string.ob_env_failed
        install.done -> R.string.ob_env_done
        else -> R.string.ob_env_title
    }
    StepHead(3, stringResource(title), stringResource(R.string.ob_env_lead), dir)
    InstallCard(install, Modifier.enter(3, dir))
    NotifyRow(notify, onNotifyChange, Modifier.enter(4, dir))
}

/**
 * Отдельный экран установки Linux вне первого запуска (переустановка при обновлении):
 * тот же заголовок и блок хода установки, кнопка «Повторить» при ошибке.
 */
@Composable
fun LinuxSetupScreen(install: LinuxInstall, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 66.dp, bottom = 20.dp),
        ) {
            val title = when {
                install.error != null -> R.string.ob_env_failed
                install.done -> R.string.ob_env_done
                else -> R.string.ob_env_title
            }
            Text(stringResource(title), style = H2, color = c.t1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ob_env_lead), style = Lead, color = c.t2)
            Spacer(Modifier.height(22.dp))
            InstallCard(install, Modifier)
        }
        Bottom(
            primaryText = stringResource(if (install.error != null) R.string.ob_retry else R.string.ob_installing),
            primaryEnabled = install.error != null,
            onPrimary = onRetry,
            altText = null,
            onAlt = {},
        )
    }
}

/** `.ob-inst`: процент, полоса и три этапа. */
@Composable
private fun InstallCard(install: LinuxInstall, modifier: Modifier) {
    val c = Dsh.colors
    val pct = (install.progress.coerceIn(0f, 1f) * 100).toInt()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.s1)
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    ) {
        val pop = remember { Animatable(1f) }
        LaunchedEffect(install.done) {
            if (install.done) {
                pop.animateTo(1.08f, tween(140))
                pop.animateTo(1f, DshMotion.lively())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "$pct%",
                style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
                color = c.t1,
                modifier = Modifier.weight(1f).graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
            )
            val eta = when {
                install.done -> stringResource(R.string.ob_ready)
                install.error != null -> ""
                install.etaSeconds == null -> ""
                install.etaSeconds >= 90 -> stringResource(R.string.ob_eta_min, (install.etaSeconds + 30) / 60)
                else -> stringResource(R.string.ob_eta_s, maxOf(5, install.etaSeconds))
            }
            Text(eta, style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, fontFeatureSettings = "tnum"), color = c.t3, modifier = Modifier.padding(bottom = 8.dp))
        }
        // `.ob-bar`
        val width by animateFloatAsState(install.progress.coerceIn(0f, 1f), DshMotion.smooth(), label = "ob-bar")
        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.s3)) {
            val fill = when {
                install.error != null -> Brush.horizontalGradient(listOf(c.danger, c.danger))
                install.done -> Brush.horizontalGradient(listOf(c.ok, c.ok))
                else -> Brush.horizontalGradient(listOf(c.accent, c.accent2))
            }
            Box(Modifier.fillMaxWidth(width).height(8.dp).clip(RoundedCornerShape(4.dp)).background(fill))
        }
        Stage(
            label = stringResource(R.string.ob_stage_download),
            state = stageState(0, install),
            note = sizeNote(install),
            first = true,
        )
        Stage(
            label = stringResource(R.string.ob_stage_unpack),
            state = stageState(1, install),
            note = if (install.stage == 1 && !install.done && install.error == null) "${install.unpackPercent}%" else "",
        )
        Stage(
            label = stringResource(R.string.ob_stage_check),
            state = stageState(2, install),
            note = "SHA-256",
        )
        if (install.error != null) {
            Text(
                install.error,
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, lineHeight = 19.sp),
                color = c.danger,
                modifier = Modifier.padding(top = 2.dp, bottom = if (install.errorDetail == null) 10.dp else 2.dp),
            )
            if (install.errorDetail != null) {
                Text(
                    install.errorDetail,
                    style = TextStyle(fontFamily = DshFonts.mono, fontSize = 11.sp, lineHeight = 15.sp),
                    color = c.t3,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
    }
}

/** `.ob-notif`: строка группы с переключателем. */
@Composable
private fun NotifyRow(notify: Boolean, onNotifyChange: (Boolean) -> Unit, modifier: Modifier) {
    val c = Dsh.colors
    Row(
        modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.s1)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Switch) { onNotifyChange(!notify) }
            .heightIn(min = 52.dp)
            .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.s2), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Bell, null, tint = c.t2, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ob_notify), style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 19.5.sp), color = c.t1)
            Text(stringResource(R.string.ob_notify_sub), style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 16.sp), color = c.t3)
        }
        DshSwitch(notify, onNotifyChange)
    }
}

private enum class StageState { WAIT, ACTIVE, DONE, FAILED }

private fun stageState(i: Int, s: LinuxInstall): StageState = when {
    s.done || i < s.stage -> StageState.DONE
    i == s.stage && s.error != null -> StageState.FAILED
    i == s.stage -> StageState.ACTIVE
    else -> StageState.WAIT
}

@Composable
private fun sizeNote(s: LinuxInstall): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (s.totalBytes <= 0) return if (s.stage == 0 && !s.done && s.error == null) "${s.downloadPercent.coerceIn(0, 100)}%" else ""
    val total = android.text.format.Formatter.formatShortFileSize(context, s.totalBytes)
    return if (s.stage == 0 && !s.done) {
        stringResource(R.string.ob_of, android.text.format.Formatter.formatShortFileSize(context, s.downloadedBytes), total)
    } else total
}

/** `.ob-st`: кружок этапа, название, мелкая подпись справа. */
@Composable
private fun Stage(label: String, state: StageState, note: String, first: Boolean = false) {
    val c = Dsh.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    StageState.WAIT -> Box(Modifier.size(18.dp).border(1.75.dp, c.s3, CircleShape))
                    StageState.ACTIVE -> Spinner(18.dp, 1.75.dp, c.accent)
                    StageState.FAILED -> Box(Modifier.size(18.dp).clip(CircleShape).background(c.danger), contentAlignment = Alignment.Center) {
                        Icon(DshGlyphs.get("x", 3f), null, tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                    StageState.DONE -> Box(Modifier.size(18.dp).clip(CircleShape).background(c.ok), contentAlignment = Alignment.Center) {
                        Icon(DshGlyphs.get("check", 3f), null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Text(
                label,
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, lineHeight = 19.sp),
                color = if (state == StageState.WAIT) c.t3 else c.t1,
                modifier = Modifier.weight(1f),
            )
            Text(note, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = c.t3)
        }
    }
}

/** Кольцо 4 dp снаружи элемента, как `box-shadow: 0 0 0 4px` — места не занимает. */
private fun Modifier.outerRing(color: Color, radius: Dp, width: Dp = 4.dp): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val w = width.toPx()
    val r = radius.toPx() + w / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(-w / 2, -w / 2),
        size = androidx.compose.ui.geometry.Size(size.width + w, size.height + w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = Stroke(width = w),
    )
}

/** `.spin-w`: кольцо с прорезью, оборот за 0,8 с. */
@Composable
private fun Spinner(size: Dp, stroke: Dp, color: Color) { com.deepseek.dsh.core.designsystem.DshSpinner(color, size, stroke, round = false) }

// ---------------------------------------------------------------------------
// Экраны запуска (в прототипе отдельно не нарисованы — собраны из частей первого запуска)
// ---------------------------------------------------------------------------

/** Знак DSH, как `.ob-mark`: плитка 84 с градиентом индиго. */
@Composable
private fun Mark(modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Box(
        modifier
            .shadow(28.dp, RoundedCornerShape(26.dp), ambientColor = c.accent.copy(alpha = 0.4f), spotColor = c.accent.copy(alpha = 0.4f))
            .size(84.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(c.accent2, c.accent), start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), endY = 6f), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DshGlyphs.Dsh, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

/**
 * Запуск Host при открытии приложения: знак, «DSH» и строка состояния с кольцом.
 * Если Host не поднялся — заголовок, пояснение, последний вывод (уже без секретов)
 * с копированием отчёта, «Повторить» и «Открыть без Host».
 *
 * @param status строка под названием, пока идёт запуск.
 * @param failed запуск не удался.
 * @param detail причина ошибки (без секретов).
 */
@Composable
fun HostStartScreen(
    status: String,
    failed: Boolean = false,
    detail: String? = null,
    logTail: List<String> = emptyList(),
    onRetry: () -> Unit = {},
    onCopyReport: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    if (!failed) {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) { p.animateTo(1f, DshMotion.smooth()) }
        Column(
            modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)
                .graphicsLayer { alpha = p.value; scaleX = 0.96f + 0.04f * p.value; scaleY = 0.96f + 0.04f * p.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Mark()
            Spacer(Modifier.height(22.dp))
            Text("DSH", style = H1, color = c.t1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spinner(15.dp, 2.dp, c.accent)
                Text(status, style = TextStyle(fontFamily = DshFonts.body, fontSize = 15.sp, lineHeight = 20.sp), color = c.t2, textAlign = TextAlign.Center)
            }
        }
        return
    }
    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 66.dp, bottom = 20.dp),
        ) {
            Text(stringResource(R.string.st_failed_title), style = H2, color = c.t1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.st_failed_lead), style = Lead, color = c.t2)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.dangerSoft).padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(DshGlyphs.Alert, null, tint = c.danger, modifier = Modifier.padding(top = 2.dp).size(15.dp))
                    Text(detail, style = Small, color = c.danger)
                }
            }
            val lines = logTail.takeLast(12)
            if (lines.isNotEmpty() || onCopyReport != null) {
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(22.dp))
                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.st_log), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp), color = c.t2, modifier = Modifier.weight(1f))
                        if (onCopyReport != null) {
                            val desc = stringResource(R.string.st_copy)
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onCopyReport).semantics { contentDescription = desc },
                                contentAlignment = Alignment.Center,
                            ) { Icon(DshGlyphs.Copy, null, tint = c.t2, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    lines.forEach { line ->
                        Text(
                            line,
                            style = TextStyle(fontFamily = DshFonts.mono, fontSize = 11.5.sp, lineHeight = 17.sp),
                            color = c.t2, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                }
            }
        }
        Bottom(
            primaryText = stringResource(R.string.ob_retry),
            primaryEnabled = true,
            onPrimary = onRetry,
            altText = onContinue?.let { stringResource(R.string.st_open_anyway) },
            onAlt = { onContinue?.invoke() },
        )
    }
}

/**
 * Доступ ко всем файлам пропал (выключен в системе): тот же выбор, что на шаге
 * «Где хранить проекты» — снова дать доступ или перейти в приватную папку DSH.
 */
@Composable
fun StorageAccessScreen(onAllow: () -> Unit, onPrivate: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    var shared by remember { mutableStateOf(true) }
    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 66.dp, bottom = 20.dp),
        ) {
            Text(stringResource(R.string.ob_ws_title), style = H2, color = c.t1, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.st_access_lead), style = Lead, color = c.t2)
            Spacer(Modifier.height(22.dp))
            WorkspaceOptions(shared, { shared = it }, granted = false, dir = 1)
        }
        Bottom(
            primaryText = stringResource(if (shared) R.string.ob_allow else R.string.ob_continue),
            primaryEnabled = true,
            onPrimary = { if (shared) onAllow() else onPrivate() },
            altText = null,
            onAlt = {},
        )
    }
}
