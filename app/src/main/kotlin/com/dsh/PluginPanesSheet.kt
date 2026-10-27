package com.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshHeadlessModal
import com.dsh.core.designsystem.DshSheetTitle
import com.dsh.feature.chat.ckBody
import com.dsh.plugin.nativehost.NativeGuideEntry
import com.dsh.plugin.nativehost.NativeRightPaneTab

/**
 * Панели, которые добавили нативные плагины (слоты `RIGHT_PANEL` и `RIGHT_PANEL_GUIDE_ENTRY`),
 * системным листом: вкладки-«таблетки», под ними — содержимое выбранной панели.
 * Кнопка в шапке чата видна, только если такие панели есть.
 */
@Composable
internal fun PluginPanesSheet(
    tabs: List<NativeRightPaneTab>,
    entries: List<NativeGuideEntry>,
    onDismiss: () -> Unit,
) {
    val c = Dsh.colors
    val panes: List<Pair<String, @Composable () -> Unit>> = tabs.map { it.title to it.render } + entries.map { it.title to it.render }
    if (panes.isEmpty()) return
    var selected by remember { mutableStateOf(0) }
    DshHeadlessModal(onDismiss = onDismiss) {
        DshSheetTitle(stringResource(R.string.plugin_panes_title))
        if (panes.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                panes.forEachIndexed { i, (title, _) ->
                    val on = i == selected
                    Box(
                        Modifier.height(40.dp).clip(CircleShape).background(if (on) c.t1 else c.s2)
                            .clickable(role = Role.Tab) { selected = i }.padding(horizontal = 14.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Text(title, style = ckBody(14.sp, 18.sp, FontWeight.Medium), color = if (on) c.bg else c.t1, maxLines = 1) }
                }
            }
        }
        entries.getOrNull(selected - tabs.size)?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = ckBody(14.sp, 20.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 560.dp).clip(RoundedCornerShape(18.dp)).background(c.s2)) {
            panes.getOrNull(selected)?.second?.invoke()
        }
    }
}
