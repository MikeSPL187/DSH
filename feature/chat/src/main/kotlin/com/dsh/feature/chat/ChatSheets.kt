package com.dsh.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshMotion
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.core.designsystem.pressScale

// Листы чата: модель и рассуждение (`#sheet-model`), режим разрешений, пресет агента.

/** Строка выбора `.model`: радио 20, название 15,5/500, описание 13; выбранная — на accent-soft. */
@Composable
internal fun ModelRadio(title: String, sub: String?, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    val c = Dsh.colors
    val bg by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, tween(200), label = "model")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(1.75.dp, if (selected) c.accent else c.t3, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent)) }
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(title)
                    if (tag != null) {
                        append("  ")
                        withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = lerp(c.t1, c.accent, 0.75f), background = c.accentSoft)) { append(" $tag ") }
                    }
                },
                style = ckBody(15.5.sp, 20.sp, FontWeight.Medium), color = c.t1,
            )
            if (!sub.isNullOrBlank()) Text(sub, style = ckBody(13.sp, 18.2.sp), color = c.t2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Лист «Модель»: модели из каталога Host (по провайдерам), уровни рассуждения модели
 * переключателем `.segm` с полосками и описание выбранного уровня.
 */
@Composable
fun ColumnScope.ModelSheetBody(
    groups: List<com.dsh.core.model.ModelProviderGroup>,
    flatModels: List<String>,
    selectedModel: String,
    reasoningOptions: List<ReasoningOption>,
    selectedReasoningId: String,
    onModel: (provider: String, modelId: String, name: String) -> Unit,
    onReasoning: (ReasoningOption) -> Unit,
    defaultModelId: String? = null,
) {
    val c = Dsh.colors
    DshSheetTitle(stringResource(R.string.model_menu_model))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (groups.isEmpty() && flatModels.isEmpty()) {
            Text(stringResource(R.string.model_none_available), style = ckBody(14.sp), color = c.t3, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        if (groups.isEmpty()) flatModels.forEach { name ->
            ModelRadio(name, null, name == selectedModel) { onModel("", name, name) }
        } else groups.forEach { g ->
            if (groups.size > 1) Text(
                g.name.uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = c.t3,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
            )
            g.models.forEach { m ->
                ModelRadio(
                    m.name, m.description, m.name == selectedModel,
                    tag = if (m.id == defaultModelId) stringResource(R.string.ck_model_default) else null,
                ) { onModel(g.id, m.id, m.name) }
            }
        }
    }
    if (reasoningOptions.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        DshSheetTitle(stringResource(R.string.ck_reasoning))
        val sel = reasoningOptions.indexOfFirst { it.id == selectedReasoningId }.coerceAtLeast(0)
        if (reasoningOptions.size <= 3) EffortSegm(reasoningOptions, sel, onReasoning)
        else Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reasoningOptions.forEachIndexed { i, o -> ModelRadio(o.label, o.description, i == sel) { onReasoning(o) } }
        }
        val cur = reasoningOptions[sel]
        if (reasoningOptions.size <= 3) Column(
            Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.s2)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(cur.label, style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = c.t1)
            if (!cur.description.isNullOrBlank()) Text(cur.description, style = ckBody(13.sp, 18.85.sp), color = c.t2)
        }
    }
}

/** `.segm`: три равные кнопки 40 dp, «бегунок» s1 пружиной, полоски уровня `.bars`. */
@Composable
private fun EffortSegm(options: List<ReasoningOption>, selected: Int, onSelect: (ReasoningOption) -> Unit) {
    val c = Dsh.colors
    BoxWithConstraints(
        Modifier.padding(top = 6.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s2).padding(3.dp).selectableGroup(),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, DshMotion.snappy(), label = "thumb")
        Box(
            Modifier.offset(x = x).width(w).height(40.dp)
                .shadow(2.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(RoundedCornerShape(11.dp)).background(c.s1),
        )
        Row {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Row(
                    Modifier.width(w).height(40.dp).clip(RoundedCornerShape(11.dp))
                        .selectable(on, role = Role.RadioButton) { onSelect(o) },
                    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Bars(level = if (options.size == 3) i + 1 else ((i + 1) * 3 / options.size).coerceIn(1, 3), color = if (on) c.accent else c.t2)
                    Text(o.label, style = ckBody(14.sp, 18.sp, if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) c.t1 else c.t2, maxLines = 1)
                }
            }
        }
    }
}

/** `.bars`: три столбика 5 / 8,5 / 12, горят первые [level]. */
@Composable
private fun Bars(level: Int, color: Color) {
    Row(Modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5f, 8.5f, 12f).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.5.dp)).background(color.copy(alpha = if (i < level) 1f else 0.25f)))
        }
    }
}

/** Лист «Режим работы»: что агенту можно без вопроса. */
@Composable
fun ColumnScope.PermissionSheetBody(current: PermissionPreset, onSelect: (PermissionPreset) -> Unit) {
    DshSheetTitle(stringResource(R.string.ck_permission_title))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PermissionPreset.entries.forEach { p ->
            ModelRadio(
                stringResource(p.titleResId), stringResource(p.descResId), p == current,
                tag = p.badgeResId?.let { stringResource(it) },
            ) { onSelect(p) }
        }
    }
}

/** Лист «Пресет агента»: стандартный или творческий. */
@Composable
fun ColumnScope.PresetSheetBody(currentId: String, onSelect: (String) -> Unit) {
    DshSheetTitle(stringResource(R.string.ck_preset_title))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple("standard", R.string.preset_standard_title, R.string.preset_standard_desc),
            Triple("cordis", R.string.preset_creative_title, R.string.preset_creative_desc),
        ).forEach { (id, t, d) -> ModelRadio(stringResource(t), stringResource(d), id == currentId) { onSelect(id) } }
    }
}

/** Вывод последней команды терминала с ошибкой — можно приложить к сообщению. */
data class ChatTerminalContext(val command: String, val code: Int?, val output: String)

/** Строка листа «+»: команда или навык. */
data class PlusCommand(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val sub: String?, val onClick: () -> Unit)

/**
 * Лист «+» `#sheet-plus`: камера и фото, источники (файл, проект, вставить, ссылка),
 * вывод терминала, режимы и команды агента.
 */
@Composable
fun ColumnScope.PlusSheetBody(
    onCamera: (() -> Unit)?,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onProject: () -> Unit,
    onPaste: () -> Unit,
    onLink: () -> Unit,
    terminal: ChatTerminalContext?,
    onTerminal: () -> Unit,
    planMode: Boolean,
    onPlan: () -> Unit,
    onGoal: () -> Unit,
    onCompact: () -> Unit,
    commands: List<PlusCommand>,
) {
    val c = Dsh.colors
    // `.recent`: камера и все фото (системный выбор — без доступа ко всей галерее).
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onCamera != null) PhotoTile(com.dsh.core.designsystem.DshGlyphs.Camera, stringResource(R.string.ck_camera), onCamera)
        PhotoTile(com.dsh.core.designsystem.DshGlyphs.Image, stringResource(R.string.ck_photos), onPhotos)
    }
    com.dsh.core.designsystem.DshSourceTiles(
        listOf(
            com.dsh.core.designsystem.DshGlyphs.File to stringResource(R.string.ck_src_file),
            com.dsh.core.designsystem.DshGlyphs.Folder to stringResource(R.string.ck_src_project),
            com.dsh.core.designsystem.DshGlyphs.Copy to stringResource(R.string.ck_src_paste),
            com.dsh.core.designsystem.DshGlyphs.Link to stringResource(R.string.ck_src_link),
        ),
        onClick = { i -> when (i) { 0 -> onFile(); 1 -> onProject(); 2 -> onPaste(); else -> onLink() } },
    )
    if (terminal != null) {
        // `.ctx` / `.crow2`
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(c.s2)
                .clickable(role = Role.Button, onClick = onTerminal).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(com.dsh.core.designsystem.DshGlyphs.Term, null, tint = c.t2, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.ck_terminal_output), style = ckBody(14.5.sp, 18.sp), color = c.t1, modifier = Modifier.weight(1f), maxLines = 1)
            if (terminal.code != null && terminal.code != 0) Text(
                stringResource(R.string.ck_code_n, terminal.code), style = ckBody(11.sp, 14.sp, FontWeight.Medium), color = c.warn,
                modifier = Modifier.clip(CircleShape).background(c.warnSoft).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
    // `.modes`
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModeChip(com.dsh.core.designsystem.DshGlyphs.Tasks, stringResource(R.string.ck_mode_plan), planMode, Modifier.weight(1f), onPlan)
        ModeChip(com.dsh.core.designsystem.DshGlyphs.Flow, stringResource(R.string.ck_mode_goal), false, Modifier.weight(1f), onGoal)
        ModeChip(com.dsh.core.designsystem.DshGlyphs.Box, stringResource(R.string.ck_mode_compact), false, Modifier.weight(1f), onCompact)
    }
    Text(
        stringResource(if (planMode) R.string.ck_plan_hint else R.string.ck_plus_hint),
        style = ckBody(12.5.sp, 17.sp), color = c.t3, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
    )
    if (commands.isNotEmpty()) {
        Text(
            stringResource(R.string.ck_commands).uppercase(), style = ckBody(12.sp, 15.sp).copy(letterSpacing = 0.06.em), color = c.t3,
            modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp),
        )
        com.dsh.core.designsystem.DshSheetList {
            commands.forEachIndexed { i, cmd ->
                com.dsh.core.designsystem.DshSheetItem(cmd.icon, cmd.title, cmd.onClick, first = i == 0, sub = cmd.sub)
            }
        }
    }
}

/** Плитка `.ph` 76 × 96: камера или все фото. */
@Composable
private fun PhotoTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier.size(width = 76.dp, height = 96.dp).pressScale(ia, 0.96f).clip(RoundedCornerShape(16.dp)).background(c.s2)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(ia, androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        androidx.compose.material3.Icon(icon, null, tint = c.t1, modifier = Modifier.size(22.dp))
        Text(label, style = ckBody(12.5.sp, 16.sp), color = c.t2)
    }
}

/** Режим `.mchip`: 40 dp, обводка; включённый — на accent-soft. */
@Composable
private fun ModeChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Dsh.colors
    val ia = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val bg by animateColorAsState(if (on) c.accentSoft else Color.Transparent, tween(250), label = "mc")
    Row(
        modifier.height(40.dp).pressScale(ia, 0.96f).clip(RoundedCornerShape(13.dp)).background(bg)
            .border(1.dp, if (on) Color.Transparent else c.line, RoundedCornerShape(13.dp))
            .clickable(ia, androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = if (on) c.accent else c.t2, modifier = Modifier.size(18.dp))
        Text(label, style = ckBody(13.5.sp, 17.sp, if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) c.accent else c.t2, maxLines = 1)
    }
}

/**
 * Лист подтверждения опасного режима (полный доступ, автопроверка): что это значит,
 * галочка «понимаю риск» и «Включить», доступная только после неё.
 */
@Composable
fun ColumnScope.RiskSheetBody(preset: PermissionPreset, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = Dsh.colors
    var ack by androidx.compose.runtime.remember(preset) { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp).size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.dangerSoft), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(com.dsh.core.designsystem.DshGlyphs.Alert, null, tint = c.danger, modifier = Modifier.size(22.dp))
    }
    DshSheetTitle(stringResource(preset.confirmTitleResId ?: preset.titleResId))
    Text(stringResource(preset.confirmDescResId ?: preset.descResId), style = ckBody(14.5.sp, 21.75.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.s2)
            .selectable(ack, role = Role.Checkbox) { ack = !ack }.padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.cb`: 24 dp, радиус 8; отмечено — акцентом с галочкой.
        val bg by animateColorAsState(if (ack) c.accent else Color.Transparent, tween(200), label = "cb")
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.75.dp, if (ack) c.accent else c.t3, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (ack) androidx.compose.material3.Icon(com.dsh.core.designsystem.DshGlyphs.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Text(stringResource(preset.confirmAckResId ?: preset.titleResId), style = ckBody(14.5.sp, 19.sp), color = c.t1, modifier = Modifier.weight(1f))
    }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(stringResource(R.string.action_cancel), BtnStyle.Ghost, Modifier.weight(1f), height = 52.dp, radius = 16.dp, fontSize = 16f, onClick = onCancel)
        Box(Modifier.weight(1.35f).graphicsLayer { alpha = if (ack) 1f else 0.45f }) {
            Btn(stringResource(preset.confirmEnableResId ?: preset.titleResId), BtnStyle.Danger, Modifier.fillMaxWidth(), height = 52.dp, radius = 16.dp, fontSize = 16f) { if (ack) onConfirm() }
        }
    }
}

/** Вопрос агента (user-questions/request): текст, пояснение, варианты; без вариантов — свой ответ. */
data class AgentQuestion(val id: String, val text: String, val detail: String?, val options: List<String>, val multi: Boolean)

/**
 * Лист «Вопрос агента» поверх всего приложения: метка, вопросы с вариантами
 * (один или несколько), поле для своего ответа; «Ответить» — когда на всё есть ответ.
 *
 * @param onSubmit выбранные варианты и свой ответ по id вопроса.
 */
@Composable
fun AgentQuestionSheet(
    questions: List<AgentQuestion>,
    onSubmit: (selected: Map<String, Set<String>>, custom: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Dsh.colors
    val selection = androidx.compose.runtime.remember(questions) { androidx.compose.runtime.mutableStateMapOf<String, Set<String>>() }
    val custom = androidx.compose.runtime.remember(questions) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val ready = questions.isNotEmpty() && questions.all { !selection[it.id].isNullOrEmpty() || !custom[it.id].isNullOrBlank() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.scrim).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss)) {
            Column(
                Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(8.dp).fillMaxWidth()
                    .heightIn(max = 640.dp).clip(RoundedCornerShape(34.dp)).background(c.s1)
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 12.dp).size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.s3))
                Box(Modifier.padding(start = 4.dp, bottom = 10.dp)) { Tier(stringResource(R.string.ck_agent_question), com.dsh.core.designsystem.DshGlyphs.Msg) }
                questions.forEach { q ->
                    Text(q.text, style = ckDisplay(17.sp, 22.sp), color = c.t1, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
                    q.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = ckBody(14.sp, 20.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)) }
                    val sel = selection[q.id].orEmpty()
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        q.options.forEach { label ->
                            ModelRadio(label, null, label in sel) {
                                selection[q.id] = when {
                                    q.multi && label in sel -> sel - label
                                    q.multi -> sel + label
                                    else -> setOf(label)
                                }
                            }
                        }
                    }
                    val draft = custom[q.id].orEmpty()
                    androidx.compose.foundation.text.BasicTextField(
                        draft, { custom[q.id] = it }, textStyle = ckBody(15.sp, 20.sp).copy(color = c.t1),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text(stringResource(if (q.options.isEmpty()) R.string.ck_your_answer else R.string.ck_or_your_answer), style = ckBody(15.sp, 20.sp), color = c.t3)
                            inner()
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(stringResource(R.string.action_cancel), BtnStyle.Ghost, Modifier.weight(1f), onClick = onDismiss)
                    Box(Modifier.weight(1f).graphicsLayer { alpha = if (ready) 1f else 0.45f }) {
                        Btn(stringResource(R.string.ck_answer), BtnStyle.Primary, Modifier.fillMaxWidth()) { if (ready) onSubmit(selection.toMap(), custom.toMap()) }
                    }
                }
            }
        }
    }
}
