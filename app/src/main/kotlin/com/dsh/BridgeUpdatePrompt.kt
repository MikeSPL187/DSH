package com.dsh

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dsh.feature.settings.R as SettingsR
import com.dsh.runtime.host.HostRuntimeHolder
import com.dsh.runtime.host.UpdateAssessment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Окно обновления bridge-плагина при запуске.
 *
 * Только если bridge-плагин отстаёт: он — вторая сторона протокола клиента и Host,
 * и расхождение версий ломает всё, что идёт через мост. Необязательное обновление rootfs
 * показывается только в настройках и при запуске не мешает.
 */
@Composable
internal fun BridgeUpdatePrompt() {
    val scope = rememberCoroutineScope()
    var assessment by remember { mutableStateOf<UpdateAssessment?>(null) }
    var dismissedVersion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Ждём, пока runtime создан и версия прочитана; не дольше 2 минут, иначе молча выходим.
        repeat(24) {
            val manager = HostRuntimeHolder.manager
            if (manager != null) {
                val result = runCatching { manager.assessUpdates() }.getOrNull()
                if (result != null) {
                    assessment = result
                    return@LaunchedEffect
                }
            }
            delay(5_000)
        }
    }

    val current = assessment ?: return
    if (!current.pluginOutdated) return
    if (dismissedVersion == current.latestPlugin) return

    com.dsh.core.designsystem.DshDialog(
        onDismiss = { dismissedVersion = current.latestPlugin },
        title = stringResource(SettingsR.string.agent_update_plugin_popup_title),
        closeLabel = stringResource(SettingsR.string.agent_update_plugin_popup_later),
        description = stringResource(SettingsR.string.agent_update_plugin_popup_desc),
        content = {
            Text(
                stringResource(
                    SettingsR.string.agent_update_plugin_versions,
                    current.installedPlugin ?: "?",
                    current.latestPlugin ?: "?",
                ),
            )
            status?.let {
                Spacer(androidx.compose.ui.Modifier.height(8.dp))
                Text(it)
            }
            error?.let {
                Spacer(androidx.compose.ui.Modifier.height(8.dp))
                Text(it)
            }
        },
        footer = {
            com.dsh.core.designsystem.DshDialogButton(
                text = stringResource(SettingsR.string.agent_update_plugin_popup_later),
                primary = false,
                enabled = !busy,
                onClick = { dismissedVersion = current.latestPlugin },
            )
            Spacer(androidx.compose.ui.Modifier.width(8.dp))
            com.dsh.core.designsystem.DshDialogButton(
                text = stringResource(SettingsR.string.agent_update_action_update_plugin),
                primary = true,
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val manager = HostRuntimeHolder.manager
                        val target = current.latestPlugin
                        if (manager == null || target == null) {
                            error = "Host is not ready"
                            busy = false
                            return@launch
                        }
                        val result = manager.updateBridgePlugin(target) { _, step -> status = step }
                        result.fold(
                            onSuccess = { version ->
                                status = version
                                HostRuntimeHolder.restartHost?.invoke()
                                delay(1_500)
                                dismissedVersion = current.latestPlugin
                            },
                            onFailure = { failure ->
                                error = failure.message
                            },
                        )
                        busy = false
                    }
                },
            )
        },
    )
}
