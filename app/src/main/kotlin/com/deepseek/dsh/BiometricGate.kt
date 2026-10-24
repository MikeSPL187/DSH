package com.deepseek.dsh

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.feature.settings.logic.DshUserPrefs
import com.deepseek.dsh.feature.settings.ui.BtnKind
import com.deepseek.dsh.feature.settings.ui.KBtn

/**
 * «Вход по отпечатку» из «Настройки → Безопасность»: при запуске и после минуты в фоне
 * DSH закрыт экраном блокировки, пока система не подтвердит отпечаток (или код телефона).
 */
object BiometricLock {
    private const val GRACE_MS = 60_000L
    var locked by mutableStateOf(true)
    private var pausedAt = 0L

    fun onPause() { pausedAt = System.currentTimeMillis() }

    fun onResume() {
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > GRACE_MS) locked = true
    }
}

@Composable
fun BiometricGate() {
    if (!DshUserPrefs.biometric || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !BiometricLock.locked) return
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    val title = stringResource(R.string.bio_title)
    val sub = stringResource(R.string.bio_sub)
    fun ask() {
        if (asking || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        asking = true
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(sub)
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(
            android.os.CancellationSignal(),
            context.mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    asking = false
                    BiometricLock.locked = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { asking = false }
            },
        )
    }
    LaunchedEffect(Unit) { ask() }
    val c = Dsh.colors
    Box(
        Modifier.fillMaxSize().background(c.bg).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(24.dp)) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(c.accent2, c.accent))),
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Lock, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Text(title, style = Dsh.type.title, color = c.t1)
            Text(sub, style = Dsh.type.callout, color = c.t2)
            KBtn(stringResource(R.string.bio_unlock), { ask() }, kind = BtnKind.Primary, height = 50.dp, radius = 16.dp)
        }
    }
}
