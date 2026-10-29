package com.dsh.core.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.R

/** Почему факт нельзя сохранить (те же имена, что у `MemoryIssue` в core:data). */
enum class MemoryFieldIssue { EMPTY, TOO_LONG, SECRET, DUPLICATE }

/**
 * Лист `#sheet-mem`: факт памяти агента. Поле `.cm-msg` (мин. 112, до [maxChars] символов), под ним причина
 * отказа и счётчик; «Отмена» или «Удалить» (второе нажатие — подтверждение) и «Запомнить»/«Сохранить».
 *
 * @param initial текст факта; [isNew] — новый (из «Добавить факт» или «Запомнить» в чате), иначе правка.
 * @param issue причина отказа для текущего текста; null — можно сохранить.
 * @param onDelete удалить факт; null — у нового факта вместо «Удалить» — «Отмена» ([onCancel]).
 * @param scopes где помнить: «Все проекты» и проект (`.fv-seg` над полем); пусто — выбора нет.
 */
@Composable
fun ColumnScope.MemoryFactSheet(
    initial: String,
    isNew: Boolean,
    issue: (String, Int) -> MemoryFieldIssue?,
    onSave: (String, Int) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    maxChars: Int = 500,
    scopes: List<String> = emptyList(),
    initialScope: Int = 0,
) {
    val c = Dsh.colors
    var text by remember(initial) { mutableStateOf(initial.take(maxChars)) }
    var scope by remember(initial, initialScope) { mutableStateOf(initialScope) }
    var armed by remember(initial) { mutableStateOf(false) }
    val why = issue(text, scope)
    val canSave = why == null && (isNew || text.trim() != initial.trim() || scope != initialScope)
    SheetTitle(stringResource(if (isNew) R.string.ds_mem_new else R.string.ds_mem_fact))
    if (scopes.size > 1) Segm(scopes, scope, { scope = it }, Modifier.padding(bottom = 10.dp), fontSize = 13.5.sp)
    val bad = why == MemoryFieldIssue.SECRET
    val shape = RoundedCornerShape(16.dp)
    val style = body(15.sp, 22.5.sp).copy(color = c.t1)
    val label = stringResource(R.string.ds_mem_field)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(shape).background(c.s1)
            .border(1.5.dp, if (bad) lerp(c.line, c.danger, 0.6f) else c.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (text.isEmpty()) Text(stringResource(R.string.ds_mem_hint), style = style.copy(color = c.t3))
        BasicTextField(
            text, { text = it.take(maxChars) },
            textStyle = style, cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val msg = when (why) {
            MemoryFieldIssue.SECRET -> stringResource(R.string.ds_mem_err_secret)
            MemoryFieldIssue.DUPLICATE -> stringResource(R.string.ds_mem_err_dup)
            MemoryFieldIssue.TOO_LONG -> if (text.length <= maxChars) stringResource(R.string.ds_mem_err_full) else ""
            else -> ""
        }
        Text(msg, style = small(12.5.sp, 17.5.sp), color = c.danger, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Text("${text.length} / $maxChars", style = small(12.5.sp, 17.5.sp), color = c.t3)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onDelete == null) KBtn(stringResource(R.string.ds_mem_cancel), onCancel, Modifier.weight(1f), height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp)
        else KBtn(
            stringResource(if (armed) R.string.ds_mem_delete_sure else R.string.ds_mem_delete), { if (armed) onDelete() else armed = true },
            Modifier.weight(1f), ink = c.danger, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp,
        )
        KBtn(
            stringResource(if (isNew) R.string.ds_mem_remember else R.string.ds_mem_save), { onSave(text, scope) },
            Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = canSave,
        )
    }
}
