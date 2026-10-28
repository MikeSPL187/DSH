package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.FsView
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.SubNote
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.display
import com.dsh.feature.settings.R

/** Аккаунт GitHub в «Настройках»: логин (null — не вошли) и когда вошли. Токен сюда не попадает никогда. */
@Immutable
data class GitHubUi(val login: String? = null, val signedAt: String? = null)

/**
 * Страница «GitHub»: без входа — карточка `.gh-hero` и «Войти через GitHub» (лист с кодом); после входа — «@логин»,
 * доступ, пояснение, где отозвать права, и «Выйти из GitHub».
 */
@Composable
internal fun GitHubPage(ui: GitHubUi, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val c = Dsh.colors
    FsView {
        val login = ui.login
        if (login == null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Si.c6), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.get("git"), null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Text(stringResource(R.string.st_gh_title), style = display(19.sp, 24.sp), color = c.t1, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Text(
                    stringResource(R.string.st_gh_why), style = body(14.5.sp, 21.75.sp), color = c.t2, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 16.dp),
                )
                KBtn(stringResource(R.string.st_gh_sign_in), onSignIn, Modifier.fillMaxWidth(), kind = BtnKind.Primary, height = 50.dp, radius = 16.dp, fontSize = 16.sp)
            }
            SubNote(stringResource(R.string.st_gh_how), top = 10.dp)
        } else {
            Gl {
                row {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFB05A1A), Color(0xFFB4384C)))),
                            contentAlignment = Alignment.Center,
                        ) { Text(login.take(1).uppercase().ifEmpty { "G" }, style = body(14.sp, 18.sp, FontWeight.SemiBold), color = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(if (login.isEmpty()) stringResource(R.string.st_gh_signed_unknown) else "@$login", style = body(15.sp), color = c.t1)
                            ui.signedAt?.let { Text(stringResource(R.string.st_gh_since, it), style = body(12.5.sp, 16.sp), color = c.t3) }
                        }
                    }
                }
                row { StRow(stringResource(R.string.st_gh_access), icon = DshGlyphs.Lock, tile = Si.c3, value = stringResource(R.string.st_gh_access_repo)) }
            }
            SubNote(stringResource(R.string.st_gh_revoke))
            Gl(Modifier.padding(top = 16.dp)) {
                row {
                    StRow(
                        stringResource(R.string.st_gh_sign_out), icon = DshGlyphs.X, tile = Si.c5, titleColor = c.danger, onClick = onSignOut,
                    )
                }
            }
        }
    }
}
