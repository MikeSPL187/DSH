package com.deepseek.dsh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshDialog
import com.deepseek.dsh.core.designsystem.DshDialogButton
import com.deepseek.dsh.core.designsystem.DshFonts
import com.deepseek.dsh.runtime.host.BackupSigner
import com.deepseek.dsh.runtime.host.RestorePlan
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Подпись резервной копии ключом HMAC-SHA256 этого телефона (G-05). Ключ живёт в Android Keystore
 * и не покидает устройство: копия с другого телефона или изменённая копия подпись не пройдёт.
 */
internal object KeystoreBackupSigner : BackupSigner {
    private const val ALIAS = "dsh_backup_hmac"

    override fun sign(data: ByteArray): ByteArray? = runCatching {
        Mac.getInstance("HmacSHA256").apply { init(key()) }.doFinal(data)
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }
}

/**
 * «Восстановить из копии?»: сколько файлов заменится и какие (первые шесть), для чужой или изменённой
 * копии — предупреждение, что вместе с ней придут настройки Host: включённые плагины, права, конфиги MCP.
 */
@Composable
internal fun RestoreConfirmDialog(plan: RestorePlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = Dsh.colors
    DshDialog(
        onDismiss = onCancel,
        title = stringResource(if (plan.trusted) R.string.restore_confirm_title else R.string.restore_foreign_title),
        closeLabel = stringResource(R.string.restore_cancel),
        description = stringResource(if (plan.trusted) R.string.restore_confirm_desc else R.string.restore_foreign_desc),
        content = {
            Text(
                stringResource(R.string.restore_files, plan.files.size, plan.replaced.size),
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 13.5.sp, lineHeight = 19.sp),
                color = c.t2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            plan.files.take(6).forEach { path ->
                Text(
                    path,
                    style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = if (path in plan.replaced) c.t1 else c.t3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (plan.files.size > 6) Text(
                stringResource(R.string.restore_more, plan.files.size - 6),
                style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.5.sp, lineHeight = 18.sp),
                color = c.t3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        },
        footer = {
            DshDialogButton(stringResource(R.string.restore_cancel), primary = false, enabled = true, onClick = onCancel)
            DshDialogButton(
                stringResource(R.string.restore_apply),
                primary = true,
                enabled = true,
                onClick = onConfirm,
                danger = !plan.trusted,
            )
        },
    )
}
