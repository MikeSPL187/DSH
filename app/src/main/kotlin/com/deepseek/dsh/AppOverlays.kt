package com.deepseek.dsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepseek.dsh.core.bridge.BridgeNativeCompanion
import kotlinx.serialization.json.JsonObject

/** 「可安装原生伴侣」的底部提示卡。 */
@Composable
internal fun CompanionAvailableCard(
    companion: BridgeNativeCompanion,
    modifier: Modifier = Modifier,
    onGoInstall: () -> Unit,
    onIgnore: () -> Unit,
) {
    // Карточка в стиле DSH: у установленного плагина есть нативная часть (APK) со своими экранами.
    com.deepseek.dsh.feature.settings.ui.StCard(modifier.shadow(12.dp, RoundedCornerShape(22.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.deepseek.dsh.feature.settings.ui.Gic(com.deepseek.dsh.core.designsystem.DshGlyphs.Puzzle, com.deepseek.dsh.feature.settings.ui.Tone.Plain)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.companion_available_title, companion.name ?: companion.pluginId), style = com.deepseek.dsh.core.designsystem.Dsh.type.bodyMedium, color = com.deepseek.dsh.core.designsystem.Dsh.colors.t1)
                Text(stringResource(R.string.companion_available_desc), style = com.deepseek.dsh.core.designsystem.Dsh.type.footnote, color = com.deepseek.dsh.core.designsystem.Dsh.colors.t3)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.deepseek.dsh.feature.settings.ui.KBtn(stringResource(R.string.companion_ignore), onIgnore, Modifier.weight(1f))
            com.deepseek.dsh.feature.settings.ui.KBtn(stringResource(R.string.companion_go_install), onGoInstall, Modifier.weight(1f), kind = com.deepseek.dsh.feature.settings.ui.BtnKind.Primary)
        }
    }
}

/** 插件整岛 Web UI 与 companion 原生浮层。 */
@Composable
internal fun PluginIslandOverlays() {
    if (com.deepseek.dsh.plugin.nativesdk.slots.SlotRegistry
            .resolve(com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.WEB_ISLAND_FULL)
            .isNotEmpty()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            com.deepseek.dsh.plugin.nativehost.NativeSlot(
                slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.WEB_ISLAND_FULL,
                spec = JsonObject(emptyMap()),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        com.deepseek.dsh.plugin.nativehost.NativeSlot(
            slot = com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot.PLUGIN_OVERLAY,
            spec = JsonObject(emptyMap()),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 登录 / API Key 两个对话框。 */
@Composable
internal fun SignInDialogs(
    showSignIn: Boolean,
    onDismissSignIn: () -> Unit,
    onAddKey: () -> Unit,
    onSignIn: () -> Unit,
    showApiKey: Boolean,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    onDismissApiKey: () -> Unit,
    onSubmitApiKey: () -> Unit,
) {
    if (showSignIn) {
        com.deepseek.dsh.core.designsystem.DshDialog(
            onDismiss = onDismissSignIn,
            title = stringResource(R.string.onboarding_title),
            closeLabel = stringResource(R.string.action_close),
            description = stringResource(R.string.onboarding_desc),
            footer = {
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.onboarding_add_key),
                    primary = false,
                    enabled = true,
                    onClick = onAddKey,
                )
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.onboarding_login),
                    primary = true,
                    enabled = true,
                    onClick = onSignIn,
                )
            },
        )
    }
    if (showApiKey) {
        com.deepseek.dsh.core.designsystem.DshDialog(
            onDismiss = onDismissApiKey,
            title = stringResource(R.string.onboarding_key_title),
            closeLabel = stringResource(R.string.action_close),
            description = stringResource(R.string.onboarding_key_desc),
            footer = {
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.action_cancel),
                    primary = false,
                    enabled = true,
                    onClick = onDismissApiKey,
                )
                com.deepseek.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.onboarding_login),
                    primary = true,
                    enabled = apiKeyInput.isNotBlank(),
                    onClick = onSubmitApiKey,
                )
            },
            content = {
                com.deepseek.dsh.core.designsystem.DshTextInput(
                    value = apiKeyInput,
                    onValueChange = onApiKeyChange,
                    placeholder = stringResource(R.string.onboarding_key_placeholder),
                    secret = true,
                )
            },
        )
    }
}
