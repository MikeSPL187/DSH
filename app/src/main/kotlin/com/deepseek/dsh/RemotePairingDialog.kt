package com.deepseek.dsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.model.HostDescriptor
import com.deepseek.dsh.feature.hosts.RemotePairingManager
import kotlinx.coroutines.launch

/**
 * 远程网关配对：粘贴网关二维码的 JSON 内容（`{v,name,url,fp,pair,exp}`），
 * 由 [RemotePairingManager] 校验 TLS 指纹并换取真实的设备令牌。
 */
@Composable
fun RemotePairingDialog(
    onDismiss: () -> Unit,
    onPaired: (HostDescriptor.Remote) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val manager = remember { RemotePairingManager() }
    var payloadText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val deviceName = android.os.Build.MODEL ?: "Android"

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.remote_pairing_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.remote_pairing_desc),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = payloadText,
                    onValueChange = { payloadText = it; error = null },
                    enabled = !running,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && payloadText.isNotBlank(),
                onClick = {
                    val parsed = manager.parseQrCodePayload(payloadText.trim())
                    val payload = parsed.getOrElse {
                        error = it.message
                        return@TextButton
                    }
                    running = true
                    scope.launch {
                        val result = manager.executePairing(payload, deviceName)
                        running = false
                        result.fold(
                            onSuccess = onPaired,
                            onFailure = { error = it.message ?: it.javaClass.simpleName },
                        )
                    }
                },
            ) { Text(stringResource(R.string.remote_pairing_connect)) }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) {
                Text(stringResource(R.string.native_trust_deny))
            }
        },
    )
}
