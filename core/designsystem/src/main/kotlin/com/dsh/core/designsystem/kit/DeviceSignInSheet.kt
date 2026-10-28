package com.dsh.core.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.R
import kotlinx.coroutines.delay

/** Код входа для пользователя: что ввести, где, и сколько секунд он действует. */
data class DeviceCodeView(val code: String, val url: String, val expiresInSec: Int)

/** Почему вход не удался: отказ на сайте, истёк код, вход не настроен в сборке или иная ошибка. */
sealed interface DeviceSignInError {
    data object Denied : DeviceSignInError
    data object Expired : DeviceSignInError
    data object NotConfigured : DeviceSignInError
    data class Other(val message: String) : DeviceSignInError
}

/**
 * Лист `#sheet-gh`: вход по одноразовому коду (OAuth device flow). Сам получает код ([begin]) и ждёт подтверждения
 * ([await]); «Открыть GitHub» копирует код и открывает страницу ввода. Ошибка — пояснение и «Новый код».
 *
 * @param await ждёт, пока пользователь подтвердит вход; успех — логин.
 */
@Composable
fun ColumnScope.DeviceSignInSheetBody(
    begin: suspend () -> Result<DeviceCodeView>,
    await: suspend () -> Result<String>,
    errorOf: (Throwable) -> DeviceSignInError,
    onSignedIn: (login: String) -> Unit,
    onToast: (String) -> Unit,
) {
    val c = Dsh.colors
    var attempt by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<DeviceCodeView?>(null) }
    var error by remember { mutableStateOf<DeviceSignInError?>(null) }
    var left by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val copied = stringResource(R.string.ds_gh_copied)
    LaunchedEffect(attempt) {
        code = null; error = null
        val got = begin().getOrElse { error = errorOf(it); return@LaunchedEffect }
        code = got
        left = got.expiresInSec
        await().onSuccess(onSignedIn).onFailure { error = errorOf(it) }
    }
    LaunchedEffect(code) { while (code != null && left > 0) { delay(1000); left-- } }

    SheetTitle(stringResource(R.string.ds_gh_title))
    // `.lim-hint`: −6 / 4 / 16.
    Text(
        stringResource(R.string.ds_gh_hint), style = body(14.sp, 20.sp), color = c.t2,
        modifier = Modifier.offset(y = (-6).dp).padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    )
    val shown = code?.code ?: "····-····"
    val codeLabel = stringResource(R.string.ds_gh_code)
    Box(
        Modifier.padding(top = 4.dp, bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.s2)
            .padding(vertical = 18.dp, horizontal = 10.dp).semantics { contentDescription = "$codeLabel: ${code?.code.orEmpty()}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shown, style = TextStyle(fontFamily = DshFonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = 0.12.em),
            color = if (code == null) c.t3 else c.t1, maxLines = 1,
        )
    }
    if (error == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KBtn(
                stringResource(R.string.ds_gh_copy), { code?.let { clipboard.setText(AnnotatedString(it.code)); onToast(copied) } },
                Modifier.weight(1f), kind = BtnKind.Ghost, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = code != null, horizontal = 10.dp,
            )
            KBtn(
                stringResource(R.string.ds_gh_open), {
                    code?.let { clipboard.setText(AnnotatedString(it.code)); onToast(copied); runCatching { uri.openUri(it.url) } }
                },
                Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp, enabled = code != null, horizontal = 10.dp,
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
            Text(
                when {
                    code == null -> stringResource(R.string.ds_gh_loading)
                    left >= 60 -> stringResource(R.string.ds_gh_wait, left / 60)
                    else -> stringResource(R.string.ds_gh_wait_short)
                },
                style = body(13.5.sp, 18.sp), color = c.t2,
            )
        }
    } else {
        val e = error!!
        Text(
            when (e) {
                DeviceSignInError.Denied -> stringResource(R.string.ds_gh_denied)
                DeviceSignInError.Expired -> stringResource(R.string.ds_gh_expired)
                DeviceSignInError.NotConfigured -> stringResource(R.string.ds_gh_not_configured)
                is DeviceSignInError.Other -> stringResource(R.string.ds_gh_failed, e.message)
            },
            style = body(14.sp, 20.sp), color = if (e == DeviceSignInError.NotConfigured) c.t2 else c.danger, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        if (e != DeviceSignInError.NotConfigured) {
            KBtn(
                stringResource(R.string.ds_gh_again), { attempt++ }, Modifier.fillMaxWidth().padding(top = 14.dp),
                kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp,
            )
        }
    }
}
