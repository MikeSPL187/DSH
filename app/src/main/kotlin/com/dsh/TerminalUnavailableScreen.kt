package com.dsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dsh.core.designsystem.kit.*
import kotlinx.coroutines.launch

@Composable
internal fun TerminalUnavailableScreen(onBack: () -> Unit, onEnable: suspend () -> Boolean) {
    var enabling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val failed = stringResource(R.string.terminal_enable_failed)
    Column(Modifier.fillMaxSize()) {
        SBar(stringResource(R.string.terminal_title), null, onBack, backDescription = stringResource(com.dsh.feature.settings.R.string.st_back))
        FsView {
            FsEmpty(stringResource(R.string.terminal_enable_description))
            KBtn(stringResource(R.string.terminal_enable), {
                enabling = true
                scope.launch {
                    try { if (!onEnable()) toast.show(failed) } finally { enabling = false }
                }
            }, Modifier.fillMaxWidth(), kind = BtnKind.Primary, busy = enabling, enabled = !enabling)
            ToastHost(toast)
        }
    }
}
