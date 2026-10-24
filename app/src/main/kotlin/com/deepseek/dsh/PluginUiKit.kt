package com.deepseek.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.model.PluginSettingsDocument
import com.deepseek.dsh.feature.settings.ui.BtnKind
import com.deepseek.dsh.feature.settings.ui.GLabel
import com.deepseek.dsh.feature.settings.ui.Gl
import com.deepseek.dsh.feature.settings.ui.KBtn
import com.deepseek.dsh.feature.settings.ui.SubNote
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 插件原生 UI 里一个字段的控件形态。 */
internal enum class PluginFieldKind {
    /** 单行文本。 */
    TEXT,

    /** 数字。 */
    NUMBER,

    /** 几个固定选项（官方页面里的下拉/单选）。 */
    CHOICE,
}

/**
 * 插件原生 UI 的一个字段。
 *
 * @param key 设置文档里的字段名（官方 `Config` schema 的键）。
 * @param label 中文标签，取自官方 locale。
 * @param hint 官方页面里的说明文字，可为空。
 * @param kind 控件形态。
 * @param choices CHOICE 的候选项：值 → 显示文案。
 * @param group 小节标题；只有同一小节的第一项需要写。
 */
internal data class PluginField(
    val key: String,
    val label: String,
    val hint: String? = null,
    val kind: PluginFieldKind = PluginFieldKind.TEXT,
    val choices: List<Pair<String, String>> = emptyList(),
    val group: String? = null,
)

/**
 * Форма одного пространства настроек Host в стиле DSH: группа строк, варианты — «таблетками»,
 * текст и числа — полем; «Сохранить» пишет изменения с revision (чужие правки не затираются).
 *
 * @param namespace пространство настроек Host.
 * @param hooks чтение и запись Host.
 * @param fields поля в порядке показа.
 * @param notices пояснения под формой.
 * @param emptyHint текст, если документ не читается.
 */
@Composable
internal fun PluginSettingsSection(
    namespace: String,
    hooks: PluginUiHooks,
    fields: List<PluginField>,
    notices: List<String> = emptyList(),
    emptyHint: String = "",
) {
    var document by remember(namespace) { mutableStateOf<PluginSettingsDocument?>(null) }
    var loaded by remember(namespace) { mutableStateOf(false) }
    val edits = remember(namespace) { mutableStateMapOf<String, String>() }
    var saving by remember(namespace) { mutableStateOf(false) }
    var status by remember(namespace) { mutableStateOf<String?>(null) }
    var reload by remember(namespace) { mutableStateOf(0) }
    val numberRequiredLabel = stringResource(R.string.settings_number_required, "")
    val savedLabel = stringResource(R.string.settings_saved)
    val rejectedLabel = stringResource(R.string.settings_deployment_rejected)

    LaunchedEffect(namespace, reload) {
        loaded = false
        // Только что включённый плагин может ещё не отдать документ: несколько попыток с паузой.
        repeat(20) {
            document = runCatching { hooks.loadDocument(namespace) }.getOrNull()
            loaded = true
            if (document != null) return@LaunchedEffect
            kotlinx.coroutines.delay(700)
        }
    }

    fun valueOf(field: PluginField): String {
        edits[field.key]?.let { return it }
        return primitiveText(document?.value?.get(field.key))
    }

    val doc = document
    when {
        !loaded -> SubNote(stringResource(R.string.settings_loading), top = 0.dp)
        doc == null -> SubNote(emptyHint, top = 0.dp)
        else -> {
            fields.groupBy { it.group }.forEach { (group, items) ->
                if (group != null) GLabel(group)
                Gl {
                    items.forEach { field ->
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Text(field.label, style = Dsh.type.body, color = Dsh.colors.t1)
                                if (!field.hint.isNullOrBlank()) Text(field.hint, style = Dsh.type.footnote, color = Dsh.colors.t3)
                                Spacer(Modifier.height(8.dp))
                                when (field.kind) {
                                    PluginFieldKind.CHOICE -> ChoiceChipFlow(field.choices, valueOf(field)) { edits[field.key] = it; status = null }
                                    else -> InputField(valueOf(field), numeric = field.kind == PluginFieldKind.NUMBER) { edits[field.key] = it; status = null }
                                }
                            }
                        }
                    }
                }
            }
            notices.forEach { SubNote(it) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KBtn(
                    stringResource(if (saving) R.string.settings_saving else R.string.settings_save), { saving = true },
                    kind = BtnKind.Primary, height = 44.dp, enabled = edits.isNotEmpty(), busy = saving,
                )
                Text(
                    status ?: if (edits.isEmpty()) savedLabel else stringResource(R.string.settings_unsaved_changes),
                    style = Dsh.type.footnote, color = Dsh.colors.t3,
                )
            }
            LaunchedEffect(saving) {
                if (!saving) return@LaunchedEffect
                val patch = mutableMapOf<String, JsonElement>()
                fields.forEach { field ->
                    val text = edits[field.key] ?: return@forEach
                    patch[field.key] = when (field.kind) {
                        PluginFieldKind.NUMBER -> {
                            val number = text.trim().toDoubleOrNull()
                            if (number == null) {
                                status = numberRequiredLabel + field.label
                                saving = false
                                return@LaunchedEffect
                            }
                            JsonPrimitive(if (number == number.toLong().toDouble()) number.toLong() else number)
                        }
                        PluginFieldKind.TEXT, PluginFieldKind.CHOICE -> JsonPrimitive(text)
                    }
                }
                val ok = runCatching { hooks.saveDocument(namespace, JsonObject(patch), doc.revision) }.getOrDefault(false)
                saving = false
                status = if (ok) { edits.clear(); reload += 1; savedLabel } else rejectedLabel
            }
        }
    }
}

/**
 * Варианты «таблетками» (как фильтры `.pl-filters`): переносятся по ширине,
 * выбранный — тёмный. Длинные подписи вроде «DeepSeek Flash · официальный API» помещаются целиком.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceChipFlow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val c = Dsh.colors
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) c.t1 else c.s1)
                    .then(if (active) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(20.dp)))
                    .selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Dsh.type.callout.copy(fontSize = 13.5.sp), color = if (active) c.bg else c.t2)
            }
        }
    }
}

/** Поле текста или числа: 44 dp, фон s2, радиус 12. */
@Composable
private fun InputField(value: String, numeric: Boolean, onChange: (String) -> Unit) {
    val c = Dsh.colors
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = (if (numeric) Dsh.type.code else Dsh.type.body).copy(color = c.t1),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.s2).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** Значение из документа как текст: строка как есть, логическое — true/false, число без лишнего `.0`. */
private fun primitiveText(value: JsonElement?): String = when (value) {
    null -> ""
    is JsonPrimitive -> value.booleanOrNull?.toString() ?: value.contentOrNull.orEmpty()
    else -> value.toString()
}
