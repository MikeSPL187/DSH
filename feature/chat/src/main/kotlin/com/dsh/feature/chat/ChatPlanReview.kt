package com.dsh.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs

/**
 * План агента на проверке (`exit_plan_mode` Host: вопрос с `intent.kind = plan-review`).
 * @param markdown план целиком, начинается с заголовка `# …`.
 * @param approveLabel вариант ответа «одобрить» (из `intent.approve`).
 * @param keepLabel вариант «доработать»; замечания уходят как свой ответ.
 */
data class PlanReviewInfo(
    val eventId: String,
    val questionId: String,
    val markdown: String,
    val approveLabel: String,
    val keepLabel: String,
) {
    /** Заголовок плана — первая строка `# …`. */
    val title: String get() = markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim().orEmpty()

    /** План без первого заголовка (он в шапке экрана). */
    val body: String get() {
        val lines = markdown.lines()
        val i = lines.indexOfFirst { it.trim().startsWith("#") }
        return (if (i >= 0) lines.drop(i + 1) else lines).joinToString("\n").trim()
    }

    /** Число пунктов нумерованных списков — шаги плана. */
    val steps: Int get() = markdown.lines().count { Regex("""^\s*\d+[.)]\s+\S""").containsMatchIn(it) }

    /** Разные пути файлов в `код` плана (есть `/` или расширение). */
    val files: Int get() = Regex("""`([^`\s]+)`""").findAll(markdown).map { it.groupValues[1] }
        .filter { p -> (p.contains('/') && !p.startsWith(":")) || Regex("""\.[A-Za-z0-9]{1,6}$""").containsMatchIn(p) }
        .filterNot { it.contains("://") }
        .toSet().size
}

/**
 * Экран «План агента» (`#screen-plan`): шапка «Назад», знак, заголовок, пояснение и счётчики, документ плана;
 * внизу «Доработать» (поле замечаний, «Отправить») и «Выполнить план». «Назад» не отвечает Host:
 * план остаётся ждать решения, в ленте — карточка «Проверить».
 */
@Composable
fun PlanReviewScreen(
    review: PlanReviewInfo,
    session: String?,
    onBack: () -> Unit,
    onApprove: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val c = Dsh.colors
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var feedback by remember(review.eventId) { mutableStateOf(false) }
    var text by remember(review.eventId) { mutableStateOf("") }
    var sent by remember(review.eventId) { mutableStateOf(false) }
    BackHandler { if (feedback) feedback = false else onBack() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(feedback) { if (feedback) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChatIconBtn(DshGlyphs.Left, stringResource(R.string.ck_back), onClick = onBack)
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(stringResource(R.string.plan_review_title), style = ckDisplay(16.sp, 19.2.sp), color = c.t1)
                if (!session.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(c.ok))
                    Text(session, style = ckBody(12.sp, 14.4.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val copied = stringResource(R.string.plan_review_copied)
            ChatIconBtn(DshGlyphs.Copy, stringResource(R.string.plan_review_copy), tint = c.t2, iconSize = 18.dp) {
                clipboard.setText(AnnotatedString(review.markdown))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Tasks, null, tint = c.accent, modifier = Modifier.size(22.dp))
                }
                Text(
                    review.title.ifBlank { stringResource(R.string.plan_review_title) },
                    style = ckDisplay(23.sp, 27.6.sp).copy(letterSpacing = (-0.015).em), color = c.t1,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                Text(stringResource(R.string.plan_review_lead), style = ckBody(15.sp, 22.5.sp), color = c.t2)
                val steps = review.steps
                val files = review.files
                if (steps > 0 || files > 0) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (steps > 0) MetaPill(DshGlyphs.Tasks, pluralStringResource(R.plurals.plan_review_steps, steps, steps))
                    if (files > 0) MetaPill(DshGlyphs.File, pluralStringResource(R.plurals.plan_review_files, files, files))
                }
            }
            Column(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s1)
                    .border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            ) { MarkdownMessageText(review.body.ifBlank { review.markdown }) }
        }
        Column(
            Modifier.fillMaxWidth().background(c.bg).drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        ) {
            if (feedback) {
                val desc = stringResource(R.string.plan_review_feedback)
                Box(
                    Modifier.padding(bottom = 10.dp).fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(14.dp)).background(c.s1)
                        .border(1.dp, c.accent, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (text.isEmpty()) Text(stringResource(R.string.plan_review_feedback_hint), style = ckBody(15.sp, 21.sp), color = c.t3)
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = ckBody(15.sp, 21.sp).copy(color = c.t1), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = desc },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(
                    stringResource(if (feedback) R.string.action_cancel else R.string.plan_review_more),
                    BtnStyle.Ghost, Modifier.weight(1f), height = 52.dp, radius = 16.dp, fontSize = 16f,
                ) { feedback = !feedback }
                Btn(
                    stringResource(if (feedback) R.string.plan_review_send else R.string.plan_review_go),
                    BtnStyle.Primary, Modifier.weight(1.35f), height = 52.dp, radius = 16.dp, fontSize = 16f,
                ) {
                    if (sent) return@Btn
                    if (feedback) {
                        if (text.isBlank()) { runCatching { focus.requestFocus() }; return@Btn }
                        sent = true; onFeedback(text.trim())
                    } else { sent = true; onApprove() }
                }
            }
            Text(
                stringResource(if (feedback) R.string.plan_review_feedback_note else R.string.plan_review_note),
                style = ckBody(12.sp, 15.sp), color = c.t3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MetaPill(icon: ImageVector, text: String) {
    val c = Dsh.colors
    Row(
        Modifier.height(26.dp).clip(CircleShape).background(c.s2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = c.t2, modifier = Modifier.size(13.dp))
        Text(text, style = ckBody(12.5.sp, 16.sp), color = c.t2, maxLines = 1)
    }
}

/** Карточка `.plan-card` в ленте: план ждёт решения, «Проверить» открывает экран. */
@Composable
internal fun PlanReviewCard(review: PlanReviewInfo, onOpen: () -> Unit) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(top = 6.dp, bottom = 4.dp).fillMaxWidth().clip(shape).background(c.s1)
            .border(1.dp, c.accent.copy(alpha = 0.3f), shape).clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Tasks, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.plan_review_card, review.title.ifBlank { stringResource(R.string.plan_review_title) }),
                style = ckBody(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (review.steps > 0) pluralStringResource(R.plurals.plan_review_card_steps, review.steps, review.steps)
                else stringResource(R.string.plan_review_waiting),
                style = ckBody(12.5.sp, 16.sp), color = c.t3,
            )
        }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.plan_review_open), style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.onAccent) }
    }
}
