package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.data.AgentLimit
import com.dsh.core.data.AgentLimitValue
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.GLabel
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.SheetTitle
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.SubNote
import com.dsh.core.designsystem.kit.ToastState
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.display
import com.dsh.core.designsystem.kit.small
import com.dsh.feature.settings.R
import com.dsh.feature.settings.logic.AgentLimitSteps
import kotlinx.coroutines.launch

/** Тексты и плитка одного предела — по `LIM` прототипа. */
private data class LimitFace(val title: Int, val hint: Int, val unit: Int, val icon: ImageVector, val tile: androidx.compose.ui.graphics.Color)

private fun face(limit: AgentLimit): LimitFace = when (limit) {
    AgentLimit.COMMAND_TIME -> LimitFace(R.string.st_lim_time, R.string.st_lim_time_hint, R.string.st_lim_time_unit, DshGlyphs.Term, Si.c6)
    AgentLimit.COMMAND_OUTPUT -> LimitFace(R.string.st_lim_out, R.string.st_lim_out_hint, R.string.st_lim_out_unit, DshGlyphs.Box, Si.c2)
    AgentLimit.SUBAGENT_DEPTH -> LimitFace(R.string.st_lim_depth, R.string.st_lim_depth_hint, R.string.st_lim_depth_unit, DshGlyphs.Flow, Si.c1)
    AgentLimit.SUBAGENT_ACTIVE -> LimitFace(R.string.st_lim_active, R.string.st_lim_active_hint, R.string.st_lim_active_unit, DshGlyphs.Spark, Si.c8)
    AgentLimit.PARALLEL_CALLS -> LimitFace(R.string.st_lim_parallel, R.string.st_lim_parallel_hint, R.string.st_lim_parallel_unit, DshGlyphs.Bolt, Si.c3)
    AgentLimit.WEB_RESULTS -> LimitFace(R.string.st_lim_web_results, R.string.st_lim_web_results_hint, R.string.st_lim_web_results_unit, DshGlyphs.Search, Si.c2)
    AgentLimit.WEB_SEARCHES -> LimitFace(R.string.st_lim_web_searches, R.string.st_lim_web_searches_hint, R.string.st_lim_web_searches_unit, DshGlyphs.Search, Si.c2)
}

/** Значение предела словами: «2 мин», «1 мин 30 с», «64 КБ», «1 МБ», «8». */
@Composable
internal fun limitText(limit: AgentLimit, value: Long): String = when (limit) {
    AgentLimit.COMMAND_TIME -> {
        val sec = (value / 1000).toInt()
        when {
            sec < 60 -> stringResource(R.string.st_u_sec, sec)
            sec % 60 == 0 -> stringResource(R.string.st_u_min, sec / 60)
            else -> stringResource(R.string.st_u_min_sec, sec / 60, sec % 60)
        }
    }
    AgentLimit.COMMAND_OUTPUT -> when {
        value < 1000 -> stringResource(R.string.st_u_b, value.toInt())
        value < 1_000_000 -> stringResource(R.string.st_u_kb, (value / 1000).toInt())
        else -> stringResource(R.string.st_u_mb, java.text.DecimalFormat("0.#").format(value / 1_000_000.0))
    }
    else -> value.toString()
}

/**
 * Группа «Пределы агента» страницы «Агент»: строки со значением и пометкой «изменено» (пределы веб-поиска — в своей группе).
 * Пусто — Host не работает или не отдал настройки: тогда только пояснение, без строк-пустышек.
 */
@Composable
internal fun AgentLimitsGroup(limits: List<AgentLimitValue>, onOpen: (AgentLimit) -> Unit) {
    GLabel(stringResource(R.string.st_limits))
    val agent = limits.filterNot { it.limit.web }
    if (agent.isEmpty()) {
        SubNote(stringResource(R.string.st_limits_off))
        return
    }
    Gl { agent.forEach { v -> row { LimitRow(v, onOpen) } } }
}

/** Строка предела: название, значение, «изменено», если пользователь задал не стандартное. */
@Composable
private fun LimitRow(v: AgentLimitValue, onOpen: (AgentLimit) -> Unit) {
    val f = face(v.limit)
    StRow(
        stringResource(f.title), icon = f.icon, tile = f.tile, value = limitText(v.limit, v.value), chevron = true,
        badge = if (v.user && v.value != v.base) stringResource(R.string.st_limit_modified) else null, onClick = { onOpen(v.limit) },
    )
}

/**
 * Группа «Веб-поиск»: переключатель Search API (как в официальном `ui-settings-web-search`), предел выбранного
 * способа, «Передавать ваш вопрос» (только Search API) и ключ — тот же ключ DeepSeek, лишь последние 4 символа,
 * переход на «Модели и ключи». Без Host — пояснение, как у пределов.
 */
@Composable
internal fun WebSearchGroup(
    state: com.dsh.core.data.WebSearchState?,
    limits: List<AgentLimitValue>,
    keyLast4: String?,
    onApi: (Boolean) -> Unit,
    onSend: (Boolean) -> Unit,
    onOpen: (AgentLimit) -> Unit,
    onKey: () -> Unit,
) {
    GLabel(stringResource(R.string.st_web))
    if (state == null || (!state.apiAvailable && limits.none { it.limit.web })) {
        SubNote(stringResource(R.string.st_limits_off))
        return
    }
    val byLimit = limits.associateBy { it.limit }
    Gl {
        if (state.apiAvailable) row {
            com.dsh.core.designsystem.kit.StToggle(
                stringResource(R.string.st_web_api), state.useApi, onApi, DshGlyphs.Globe, Si.c7,
                sub = stringResource(if (state.useApi) R.string.st_web_api_on else R.string.st_web_api_off),
            )
        }
        val limit = byLimit[if (state.useApi) AgentLimit.WEB_RESULTS else AgentLimit.WEB_SEARCHES]
        if (limit != null) row { LimitRow(limit, onOpen) }
        val send = state.sendQuestion
        if (state.useApi && send != null) row {
            com.dsh.core.designsystem.kit.StToggle(
                stringResource(R.string.st_web_send), send, onSend, DshGlyphs.Eye, Si.c5,
                sub = stringResource(if (send) R.string.st_web_send_on else R.string.st_web_send_off),
            )
        }
        row {
            StRow(
                stringResource(R.string.st_web_key), icon = DshGlyphs.Key, tile = Si.c1, chevron = true, onClick = onKey,
                value = keyLast4?.let { stringResource(R.string.st_web_key_value, it) } ?: stringResource(R.string.st_web_key_none),
            )
        }
    }
    SubNote(stringResource(R.string.st_web_note))
}

/**
 * Лист `#sheet-limit`: заголовок, пояснение, степпер 52 dp на s2, «Стандартно: …», «Сбросить» и «Сохранить».
 * Сохранение стандартного значения — это `unset`, а не запись числа: тогда предел снова следует за Host.
 */
@Composable
internal fun ColumnScope.AgentLimitSheet(
    v: AgentLimitValue,
    save: suspend (AgentLimitValue, Long?) -> Boolean,
    toast: ToastState,
    close: () -> Unit,
) {
    val c = Dsh.colors
    val f = face(v.limit)
    val steps = remember(v) { AgentLimitSteps.of(v) }
    var draft by remember(v) { mutableStateOf(v.value) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val msgSaved = stringResource(R.string.st_limit_saved)
    val msgRestored = stringResource(R.string.st_limit_restored)
    val msgFailed = stringResource(R.string.st_limit_failed)
    fun commit(value: Long?, ok: String) {
        busy = true
        scope.launch {
            val done = save(v, value)
            busy = false
            toast.show(if (done) ok else msgFailed)
            close()
        }
    }
    SheetTitle(stringResource(f.title))
    // `.lim-hint`: margin −6 / 4 / 16 — пояснение подтянуто к заголовку.
    Text(stringResource(f.hint), style = body(14.sp, 20.sp), color = c.t2, modifier = Modifier.offset(y = (-6).dp).padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s2).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepButton("−", stringResource(R.string.st_limit_less), enabled = draft > steps.first()) { draft = AgentLimitSteps.next(steps, draft, -1) }
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(limitText(v.limit, draft), style = display(32.sp, 35.sp), color = c.t1, textAlign = TextAlign.Center, maxLines = 1)
            Text(stringResource(f.unit), style = small(13.sp, 17.sp), color = c.t3)
        }
        StepButton("+", stringResource(R.string.st_limit_more), enabled = draft < steps.last()) { draft = AgentLimitSteps.next(steps, draft, +1) }
    }
    Text(
        stringResource(R.string.st_limit_default, limitText(v.limit, v.base)), style = small(13.sp, 17.sp), color = c.t3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 16.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(
            stringResource(R.string.st_limit_reset), { commit(null, msgRestored) }, Modifier.weight(1f),
            kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = !busy && (v.user || draft != v.base),
        )
        KBtn(
            stringResource(R.string.st_limit_save), { if (draft == v.base) commit(null, msgSaved) else commit(draft, msgSaved) }, Modifier.weight(1.35f),
            kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = !busy,
        )
    }
}

/** Кнопка степпера 52 × 52, радиус 16, фон s1, знак 26/500; на краю — прозрачность 0,4. */
@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Dsh.colors
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.s1).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = body(26.sp, 30.sp).copy(fontWeight = FontWeight.Medium), color = c.t1)
    }
}
