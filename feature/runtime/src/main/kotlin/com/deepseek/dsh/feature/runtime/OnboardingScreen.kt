package com.deepseek.dsh.feature.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshButton
import com.deepseek.dsh.core.designsystem.DshButtonKind
import com.deepseek.dsh.core.designsystem.DshDialog
import com.deepseek.dsh.core.designsystem.DshDialogButton
import com.deepseek.dsh.core.designsystem.DshDivider
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.core.designsystem.DshGroup
import com.deepseek.dsh.core.designsystem.DshIconButton
import com.deepseek.dsh.core.designsystem.DshIconTile
import com.deepseek.dsh.core.designsystem.DshMotion
import com.deepseek.dsh.core.designsystem.DshPanelDefaults
import com.deepseek.dsh.core.designsystem.DshRow
import com.deepseek.dsh.core.designsystem.DshTileColors
import com.deepseek.dsh.core.designsystem.LocalDshPanelColors
import com.deepseek.dsh.core.designsystem.pressScale
import kotlinx.coroutines.delay

/**
 * Первый запуск DSH на телефоне: вход → приветствие → кредиты → задачи → подробность.
 *
 * Логика шагов и смысл настроек взяты из настольного клиента (DesktopOnboarding):
 * те же переходы, диалоги подтверждения и результат [OnboardingResult]. Вид — из
 * прототипа DSH: шапка «Назад» с индикатором шагов, «Шаг N из 3», карточки выбора
 * и кнопка действия внизу экрана, всё на токенах дизайн-системы.
 *
 * @param onComplete вызывается, когда пользователь закончил или пропустил настройку.
 * @param onRecharge открывает пополнение; null отключает все кнопки пополнения.
 * @param funded есть ли на счёте кредиты — задаёт порядок кнопок на шаге кредитов.
 * @param authenticated без входа и без API-ключа сначала показывается страница входа.
 * @param modifier внешний модификатор.
 */
@Composable
fun OnboardingScreen(
    onComplete: (OnboardingResult) -> Unit,
    onRecharge: (() -> Unit)? = null,
    funded: Boolean = true,
    authenticated: Boolean = true,
    onSignIn: () -> Unit = {},
    onAddApiKey: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Dsh.colors
    // Пользователь с входом или ключом сразу видит приветствие, без страницы входа.
    var step by remember { mutableStateOf(if (authenticated) OnboardingStep.WELCOME else OnboardingStep.AUTH) }
    var purpose by remember { mutableStateOf<OnboardingPurpose?>(null) }
    var process by remember { mutableStateOf<OnboardingProcess?>(null) }
    var confirmation by remember { mutableStateOf<OnboardingConfirmation?>(null) }

    var creditFunded by remember { mutableStateOf<Boolean?>(null) }
    // Порядок кнопок на шаге кредитов фиксируется при первом входе: пополнение
    // баланса, пока шаг открыт, не должно менять кнопки местами.
    LaunchedEffect(step) {
        if (step == OnboardingStep.CREDIT && creditFunded == null) creditFunded = funded
    }
    val effectiveFunded = creditFunded ?: funded
    val canRecharge = onRecharge != null

    fun finish(skipped: Boolean) {
        onComplete(OnboardingResult.of(purpose, process, skipped))
    }

    // Вход и добавление ключа завершаются асинхронно: как только они готовы — приветствие.
    LaunchedEffect(authenticated) {
        if (authenticated && step == OnboardingStep.AUTH) step = OnboardingStep.WELCOME
    }

    fun goBack() {
        step = when (step) {
            OnboardingStep.AUTH, OnboardingStep.WELCOME -> OnboardingStep.WELCOME
            OnboardingStep.CREDIT -> OnboardingStep.WELCOME
            OnboardingStep.PURPOSE -> OnboardingStep.CREDIT
            OnboardingStep.PROCESS -> OnboardingStep.PURPOSE
        }
    }

    val inSteps = step.number > 0
    // Системный «Назад» листает шаги назад, а открытый диалог — закрывает.
    BackHandler(enabled = confirmation == null && inSteps) { goBack() }
    BackHandler(enabled = confirmation != null) { confirmation = null }

    Box(modifier = modifier.fillMaxSize().background(c.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(if (confirmation != null) Modifier.blur(10.dp) else Modifier),
        ) {
            OnboardingTopBar(
                step = step.number,
                visible = inSteps,
                onBack = { goBack() },
                onSkip = {
                    confirmation = if (step == OnboardingStep.CREDIT) {
                        OnboardingConfirmation.CREDIT_SKIP
                    } else {
                        OnboardingConfirmation.SKIP
                    }
                },
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    // Вперёд — новый шаг въезжает справа, назад — слева; пружина без отскока.
                    val forward = targetState.ordinal >= initialState.ordinal
                    val enter = slideInHorizontally(DshMotion.smooth()) { w -> if (forward) w / 4 else -w / 4 } +
                        fadeIn(DshMotion.smooth())
                    val exit = slideOutHorizontally(DshMotion.smooth()) { w -> if (forward) -w / 6 else w / 6 } +
                        fadeOut(DshMotion.snappy())
                    enter togetherWith exit
                },
                label = "onboarding-page",
            ) { current ->
                when (current) {
                    OnboardingStep.AUTH -> HeroPage {
                        DshButton(stringResource(R.string.onb_auth_login), onSignIn, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        DshButton(stringResource(R.string.onb_auth_add_key), onAddApiKey, Modifier.fillMaxWidth(), kind = DshButtonKind.Ghost)
                        FootNote(Icons.Rounded.Lock, stringResource(R.string.onb_auth_note))
                    }

                    OnboardingStep.WELCOME -> HeroPage {
                        DshButton(stringResource(R.string.onb_start), { step = OnboardingStep.CREDIT }, Modifier.fillMaxWidth())
                    }

                    OnboardingStep.CREDIT -> CreditPage(
                        funded = effectiveFunded,
                        canRecharge = canRecharge,
                        onPrimary = {
                            if (effectiveFunded) {
                                step = OnboardingStep.PURPOSE
                            } else {
                                confirmation = null
                                onRecharge?.invoke()
                            }
                        },
                        onSecondary = {
                            if (effectiveFunded) {
                                onRecharge?.invoke()
                            } else {
                                // «Далее» без кредитов сначала спрашивает подтверждение.
                                confirmation = OnboardingConfirmation.CREDIT
                            }
                        },
                    )

                    OnboardingStep.PURPOSE -> PurposePage(
                        purpose = purpose,
                        onPurposeChange = { purpose = it },
                        onContinue = {
                            // Только офисные задачи — настройка заканчивается здесь,
                            // шаг подробности им не нужен.
                            if (purpose == OnboardingPurpose.OFFICE) {
                                finish(skipped = false)
                            } else {
                                step = OnboardingStep.PROCESS
                            }
                        },
                    )

                    OnboardingStep.PROCESS -> ProcessPage(
                        process = process,
                        onProcessChange = { process = it },
                        onEnter = { finish(skipped = false) },
                    )
                }
            }
        }

        confirmation?.let { kind ->
            OnboardingConfirmationDialog(
                kind = kind,
                canRecharge = canRecharge,
                onClose = { confirmation = null },
                onContinueSetup = { confirmation = null },
                onUnderstood = {
                    confirmation = null
                    if (kind == OnboardingConfirmation.CREDIT) {
                        step = OnboardingStep.PURPOSE
                    } else {
                        finish(skipped = true)
                    }
                },
                onEnterApp = {
                    confirmation = null
                    finish(skipped = true)
                },
                onRecharge = {
                    confirmation = null
                    onRecharge?.invoke()
                },
            )
        }
    }
}

/** Шаги первого запуска; [number] — номер в индикаторе, 0 — до шагов. */
private enum class OnboardingStep(val number: Int) { AUTH(0), WELCOME(0), CREDIT(1), PURPOSE(2), PROCESS(3) }

private enum class OnboardingConfirmation { CREDIT, CREDIT_SKIP, SKIP }

private const val STEP_COUNT = 3

// ---------------------------------------------------------------------------
// Шапка и общий каркас страниц
// ---------------------------------------------------------------------------

/**
 * Шапка: «Назад» слева, индикатор шагов по центру, «Пропустить» справа.
 * На приветствии всё это скрыто, но место остаётся — экран не прыгает.
 */
@Composable
private fun OnboardingTopBar(step: Int, visible: Boolean, onBack: () -> Unit, onSkip: () -> Unit) {
    val c = Dsh.colors
    val alpha by animateFloatAsState(if (visible) 1f else 0f, DshMotion.smooth(), label = "onb-top")
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    ) {
        DshIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
            contentDescription = stringResource(R.string.onb_back),
            enabled = visible,
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 1..STEP_COUNT) {
                val current = i == step
                val width by animateDpAsState(if (current) 30.dp else 18.dp, DshMotion.snappy(), label = "onb-dot-w")
                val color by animateColorAsState(
                    when {
                        current -> c.accent
                        i < step -> c.accent.copy(alpha = 0.4f)
                        else -> c.s3
                    },
                    DshMotion.smooth(),
                    label = "onb-dot-c",
                )
                Box(Modifier.size(width = width, height = 4.dp).clip(CircleShape).background(color))
            }
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .heightIn(min = 44.dp)
                .pressScale(interaction)
                .clip(RoundedCornerShape(22.dp))
                .clickable(interaction, ripple(), enabled = visible, role = Role.Button, onClick = onSkip)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.onb_skip), style = Dsh.type.callout, color = c.t2)
        }
    }
}

/**
 * Каркас страницы: прокручиваемое содержимое и закреплённые внизу действия.
 * На маленьком экране текст прокручивается, а кнопка всегда под пальцем.
 */
@Composable
private fun OnboardingPage(
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val bg = Dsh.colors.bg
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 22.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                content = content,
            )
            // Если текст не поместился, нижний край мягко гаснет — видно, что можно прокрутить.
            val fade by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, DshMotion.smooth(), label = "onb-fade")
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .graphicsLayer { alpha = fade }
                    .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 16.dp),
            content = bottom,
        )
    }
}

/** Плавный подъём элемента при появлении страницы, с небольшой задержкой по порядку. */
@Composable
private fun Modifier.rise(order: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 45L)
        progress.animateTo(1f, DshMotion.smooth())
    }
    val offset = with(LocalDensity.current) { 14.dp.toPx() }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offset
    }
}

/** «Шаг N из 3», заголовок и вводный текст страницы-шага. */
@Composable
private fun StepHeader(step: Int, title: String, lead: String) {
    val c = Dsh.colors
    Text(
        stringResource(R.string.onb_step_of, step, STEP_COUNT),
        style = Dsh.type.footnote.copy(fontWeight = FontWeight.Medium),
        color = c.accent,
        modifier = Modifier.padding(top = 6.dp).rise(0),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        title,
        style = StepTitle,
        color = c.t1,
        modifier = Modifier.semantics { heading() }.rise(1),
    )
    Spacer(Modifier.height(8.dp))
    Text(lead, style = Dsh.type.body.copy(lineHeight = 22.sp), color = c.t2, modifier = Modifier.rise(2))
    Spacer(Modifier.height(22.dp))
}

private val StepTitle = TextStyle(
    fontFamily = DshFonts.display,
    fontWeight = FontWeight.SemiBold,
    fontSize = 27.sp,
    lineHeight = 31.sp,
    letterSpacing = (-0.02).em,
)

/** Пояснение под кнопками: иконка и короткий текст по центру. */
@Composable
private fun FootNote(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Dsh.colors.t3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Dsh.type.footnote, color = Dsh.colors.t3, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------
// Страницы
// ---------------------------------------------------------------------------

/** Приветствие: знак DSH, назначение приложения и три главных возможности. */
@Composable
private fun HeroPage(bottom: @Composable ColumnScope.() -> Unit) {
    val c = Dsh.colors
    // На низком экране знак и отступы меньше, чтобы все три возможности были видны.
    val compact = LocalConfiguration.current.screenHeightDp < 720
    val markSize = if (compact) 64.dp else 84.dp
    OnboardingPage(bottom = bottom) {
        Spacer(Modifier.height(if (compact) 4.dp else 28.dp))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .rise(0)
                .size(markSize)
                .clip(RoundedCornerShape(markSize * 0.31f))
                .background(Brush.linearGradient(listOf(c.accent2, c.accent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = Color.White, modifier = Modifier.size(markSize * 0.48f))
        }
        Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
        Text(
            stringResource(R.string.onb_brand),
            style = TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 32.sp else 38.sp, lineHeight = if (compact) 36.sp else 42.sp, letterSpacing = (-0.03).em),
            color = c.t1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() }.rise(1),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.onb_tagline),
            style = Dsh.type.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = c.t2,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp).rise(2),
        )
        Spacer(Modifier.height(if (compact) 22.dp else 36.dp))
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)) {
            Feature(Icons.Rounded.Terminal, R.string.onb_feat_linux, R.string.onb_feat_linux_sub, 3)
            Feature(Icons.Rounded.AutoAwesome, R.string.onb_feat_agent, R.string.onb_feat_agent_sub, 4)
            Feature(Icons.Rounded.Shield, R.string.onb_feat_control, R.string.onb_feat_control_sub, 5)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, subtitle: Int, order: Int) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().rise(order), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = Dsh.type.bodyMedium, color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(subtitle), style = Dsh.type.callout, color = c.t2)
        }
    }
}

@Composable
private fun CreditPage(
    funded: Boolean,
    canRecharge: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    OnboardingPage(bottom = {
        DshButton(
            stringResource(if (funded) R.string.onb_continue else R.string.onb_top_up),
            onPrimary,
            Modifier.fillMaxWidth(),
            enabled = funded || canRecharge,
        )
        Spacer(Modifier.height(8.dp))
        DshButton(
            stringResource(if (funded) R.string.onb_funded_top_up else R.string.onb_later),
            onSecondary,
            Modifier.fillMaxWidth(),
            kind = DshButtonKind.Ghost,
            enabled = !(funded && !canRecharge),
        )
    }) {
        StepHeader(1, stringResource(R.string.onb_credit), stringResource(R.string.onb_credit_description))
        DshGroup(Modifier.rise(3)) {
            DshRow(
                stringResource(R.string.onb_credit_fact_tokens),
                subtitle = stringResource(R.string.onb_credit_fact_tokens_sub),
                leading = { DshIconTile(Icons.Rounded.Bolt, DshTileColors.indigo) },
            )
            DshDivider(inset = 56.dp)
            DshRow(
                stringResource(R.string.onb_credit_fact_idle),
                subtitle = stringResource(R.string.onb_credit_fact_idle_sub),
                leading = { DshIconTile(Icons.Rounded.Timer, DshTileColors.green) },
            )
            DshDivider(inset = 56.dp)
            DshRow(
                stringResource(R.string.onb_credit_fact_later),
                subtitle = stringResource(R.string.onb_credit_fact_later_sub),
                leading = { DshIconTile(Icons.Rounded.Settings, DshTileColors.gray) },
            )
        }
    }
}

@Composable
private fun PurposePage(
    purpose: OnboardingPurpose?,
    onPurposeChange: (OnboardingPurpose?) -> Unit,
    onContinue: () -> Unit,
) {
    val officeSelected = purpose == OnboardingPurpose.OFFICE || purpose == OnboardingPurpose.BOTH
    val developmentSelected = purpose == OnboardingPurpose.DEVELOPMENT || purpose == OnboardingPurpose.BOTH

    fun toggle(target: OnboardingPurpose) {
        val office = if (target == OnboardingPurpose.OFFICE) !officeSelected else officeSelected
        val development = if (target == OnboardingPurpose.DEVELOPMENT) !developmentSelected else developmentSelected
        onPurposeChange(
            when {
                office && development -> OnboardingPurpose.BOTH
                office -> OnboardingPurpose.OFFICE
                development -> OnboardingPurpose.DEVELOPMENT
                else -> null
            },
        )
    }

    OnboardingPage(bottom = { ContinueButton(R.string.onb_continue, purpose != null, onContinue) }) {
        StepHeader(2, stringResource(R.string.onb_purpose_title), stringResource(R.string.onb_purpose_description))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceCard(
                title = stringResource(R.string.onb_development),
                subtitle = stringResource(R.string.onb_development_description),
                icon = Icons.Rounded.Code,
                selected = developmentSelected,
                multiple = true,
                order = 3,
                onClick = { toggle(OnboardingPurpose.DEVELOPMENT) },
            )
            ChoiceCard(
                title = stringResource(R.string.onb_office),
                subtitle = stringResource(R.string.onb_office_description),
                icon = Icons.Rounded.Description,
                selected = officeSelected,
                multiple = true,
                order = 4,
                onClick = { toggle(OnboardingPurpose.OFFICE) },
            )
        }
    }
}

@Composable
private fun ProcessPage(
    process: OnboardingProcess?,
    onProcessChange: (OnboardingProcess) -> Unit,
    onEnter: () -> Unit,
) {
    OnboardingPage(bottom = { ContinueButton(R.string.onb_enter, process != null, onEnter) }) {
        StepHeader(3, stringResource(R.string.onb_process), stringResource(R.string.onb_process_description))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingProcess.entries.forEachIndexed { index, value ->
                ChoiceCard(
                    title = stringResource(
                        when (value) {
                            OnboardingProcess.COMPACT -> R.string.onb_compact
                            OnboardingProcess.STANDARD -> R.string.onb_standard
                            OnboardingProcess.DETAILED -> R.string.onb_detailed
                        },
                    ),
                    subtitle = stringResource(
                        when (value) {
                            OnboardingProcess.COMPACT -> R.string.onb_compact_description
                            OnboardingProcess.STANDARD -> R.string.onb_standard_description
                            OnboardingProcess.DETAILED -> R.string.onb_detailed_description
                        },
                    ),
                    icon = when (value) {
                        OnboardingProcess.COMPACT -> Icons.Rounded.TaskAlt
                        OnboardingProcess.STANDARD -> Icons.Rounded.Checklist
                        OnboardingProcess.DETAILED -> Icons.Rounded.BugReport
                    },
                    badge = if (value == OnboardingProcess.STANDARD) stringResource(R.string.onb_recommended) else null,
                    selected = process == value,
                    multiple = false,
                    order = 3 + index,
                    onClick = { onProcessChange(value) },
                )
            }
        }
    }
}

/** Главная кнопка шага; пока ничего не выбрано — неактивна и с подсказкой. */
@Composable
private fun ColumnScope.ContinueButton(text: Int, enabled: Boolean, onClick: () -> Unit) {
    DshButton(stringResource(text), onClick, Modifier.fillMaxWidth(), enabled = enabled)
    val hintAlpha by animateFloatAsState(if (enabled) 0f else 1f, DshMotion.smooth(), label = "onb-hint")
    Text(
        stringResource(R.string.onb_choose_one),
        style = Dsh.type.footnote,
        color = Dsh.colors.t3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).graphicsLayer { alpha = hintAlpha },
    )
}

// ---------------------------------------------------------------------------
// Карточка выбора
// ---------------------------------------------------------------------------

/**
 * Вариант выбора: отметка слева, заголовок с описанием и плитка справа.
 * Выбранная карточка получает акцентную рамку и мягкое кольцо вокруг.
 *
 * @param multiple квадратная отметка (можно выбрать несколько) или круглая (один из).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    multiple: Boolean,
    order: Int,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val c = Dsh.colors
    val ring by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, DshMotion.smooth(), label = "onb-ring")
    val edge by animateColorAsState(if (selected) c.accent else c.line, DshMotion.smooth(), label = "onb-edge")
    val interaction = remember { MutableInteractionSource() }
    val outer = RoundedCornerShape(24.dp)
    val inner = RoundedCornerShape(20.dp)
    val selection = if (multiple) {
        Modifier.toggleable(selected, interaction, ripple(), role = Role.Checkbox) { onClick() }
    } else {
        Modifier.selectable(selected, interaction, ripple(), role = Role.RadioButton, onClick = onClick)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .rise(order)
            .pressScale(interaction, 0.98f)
            .border(4.dp, ring, outer)
            .padding(4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(inner)
                .background(c.s1)
                .border(1.5.dp, edge, inner)
                .then(selection)
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SelectionMark(selected, multiple)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, style = Dsh.type.bodyMedium.copy(fontSize = 16.sp), color = c.t1)
                    if (badge != null) {
                        Text(
                            badge,
                            style = Dsh.type.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.accent,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterVertically).clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = Dsh.type.callout, color = c.t2)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.s2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) c.accent else c.t2, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Отметка выбора с пружинным появлением галочки или точки. */
@Composable
private fun SelectionMark(selected: Boolean, multiple: Boolean) {
    val c = Dsh.colors
    val shape = if (multiple) RoundedCornerShape(7.dp) else CircleShape
    val fill by animateColorAsState(if (selected && multiple) c.accent else Color.Transparent, DshMotion.smooth(), label = "onb-mark-fill")
    val edge by animateColorAsState(if (selected) c.accent else c.t3, DshMotion.smooth(), label = "onb-mark-edge")
    val scale by animateFloatAsState(if (selected) 1f else 0f, DshMotion.lively(), label = "onb-mark-scale")
    Box(
        Modifier.padding(top = 1.dp).size(22.dp).clip(shape).background(fill).border(1.75.dp, edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        val inner = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0f, 1f) }
        if (multiple) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = c.onAccent, modifier = inner.size(16.dp))
        } else {
            Box(inner.size(11.dp).clip(CircleShape).background(c.accent))
        }
    }
}

// ---------------------------------------------------------------------------
// Диалоги подтверждения
// ---------------------------------------------------------------------------

@Composable
private fun OnboardingConfirmationDialog(
    kind: OnboardingConfirmation,
    canRecharge: Boolean,
    onClose: () -> Unit,
    onContinueSetup: () -> Unit,
    onUnderstood: () -> Unit,
    onEnterApp: () -> Unit,
    onRecharge: () -> Unit,
) {
    val credit = kind != OnboardingConfirmation.SKIP
    val title = stringResource(if (credit) R.string.onb_no_credit_title else R.string.onb_skip_title)
    val description = stringResource(
        if (credit) R.string.onb_no_credit_description else R.string.onb_skip_description,
    )
    val secondaryText = stringResource(if (credit) R.string.onb_understood else R.string.onb_keep_setting)
    val primaryText = stringResource(if (credit) R.string.onb_go_top_up else R.string.onb_enter)
    val secondaryAction = if (credit) onUnderstood else onContinueSetup
    val primaryAction = if (credit) onRecharge else onEnterApp
    DshDialog(
        onDismiss = onClose,
        title = title,
        closeLabel = stringResource(R.string.onb_close),
        width = DshPanelDefaults.DialogWidth,
        content = {
            Text(
                text = description,
                color = LocalDshPanelColors.current.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
            )
        },
        footer = {
            DshDialogButton(
                text = secondaryText,
                primary = false,
                enabled = true,
                onClick = secondaryAction,
                modifier = Modifier.widthIn(min = 72.dp),
            )
            DshDialogButton(
                text = primaryText,
                primary = true,
                enabled = !credit || canRecharge,
                onClick = primaryAction,
                modifier = Modifier.widthIn(min = 136.dp),
            )
        },
    )
}
