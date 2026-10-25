package com.deepseek.dsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.model.AccountBonusInfo

/**
 * 未读奖励提示（官方 `account/getUnnotifiedBonuses`）。
 *
 * 官方 Web 客户端在打开时查询未展示的奖励并弹窗；这里同逻辑：首次组合时拉一次，
 * 有奖励就展示，点「知道了」逐条 `ackBonusNotified`。逻辑放在独立 composable，
 * `MainAppHost` 只留一行调用。
 */
@Composable
fun AccountBonusHost(
    repository: DshRepository,
    hostId: String,
    hostBound: Boolean,
) {
    var accountId by remember { mutableStateOf<String?>(null) }
    var bonuses by remember { mutableStateOf<List<AccountBonusInfo>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(hostId, hostBound) {
        if (!hostBound) return@LaunchedEffect
        val result = runCatching { repository.fetchUnnotifiedBonuses(hostId) }.getOrNull()
        if (result != null) {
            accountId = result.first
            bonuses = result.second
        }
    }
    if (bonuses.isEmpty()) return
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    fun acknowledge() {
        val id = accountId
        val pending = bonuses
        bonuses = emptyList()
        if (id != null) {
            scope.launch {
                pending.forEach { bonus -> runCatching { repository.ackBonusNotified(hostId, id, bonus.orderId) } }
            }
        }
    }
    com.deepseek.dsh.core.designsystem.DshDialog(
        onDismiss = ::acknowledge,
        title = stringResource(R.string.account_bonus_title),
        closeLabel = stringResource(R.string.account_bonus_ok),
        content = {
            bonuses.forEach { bonus ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.Spark, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(bonus.title ?: bonus.message, style = com.deepseek.dsh.feature.chat.ckBody(15.sp, 20.sp, androidx.compose.ui.text.font.FontWeight.Medium), color = c.t1)
                        if (!bonus.title.isNullOrBlank() && bonus.message.isNotBlank()) {
                            Text(bonus.message, style = com.deepseek.dsh.feature.chat.ckBody(13.sp, 18.sp), color = c.t2, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        },
        footer = {
            com.deepseek.dsh.core.designsystem.DshDialogButton(stringResource(R.string.account_bonus_ok), primary = true, enabled = true, onClick = ::acknowledge)
        },
    )
}
