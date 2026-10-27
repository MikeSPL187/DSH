package com.dsh

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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.feature.settings.logic.DshUserPrefs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.KBtn

/**
 * «Вход по отпечатку» из «Настройки → Безопасность»: при запуске и после минуты в фоне
 * DSH закрыт экраном блокировки, пока система не подтвердит отпечаток (или код телефона).
 */
object BiometricLock {
    private const val GRACE_MS = 60_000L

    /** Чем можно подтвердить вход: отпечаток (или лицо) либо код телефона. */
    private const val AUTHENTICATORS =
        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Есть ли на телефоне чем подтвердить вход. Без отпечатка и без кода телефона
     * блокировка не включается и не закрывает DSH — иначе из приложения не выйти.
     */
    fun available(context: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
        return runCatching { manager.canAuthenticate(AUTHENTICATORS) }.getOrNull() ==
            android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    internal fun prompt(context: android.content.Context, title: String, subtitle: String): android.hardware.biometrics.BiometricPrompt =
        android.hardware.biometrics.BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
    var locked by mutableStateOf(true)
    private var pausedAt = 0L

    fun onPause() { pausedAt = System.currentTimeMillis() }

    fun onResume() {
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > GRACE_MS) locked = true
    }
}

/** Закрыт ли сейчас DSH экраном блокировки. */
@Composable
fun biometricGateActive(): Boolean {
    val context = LocalContext.current
    val available = remember { BiometricLock.available(context) }
    return DshUserPrefs.biometric && BiometricLock.locked && available
}

/**
 * Основной интерфейс под замком (G-07): у него нет семантики — TalkBack и службы специальных возможностей не читают
 * чат и не нажимают «Разрешить» в одобрении, — ввод с клавиатуры и «Назад» до него не доходят, фокус снят
 * (клавиатура закрывается). Состояние экранов при этом сохраняется: после входа всё как было.
 */
@Composable
fun LockedContent(locked: Boolean, content: @Composable () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(locked) { if (locked) focus.clearFocus(force = true) }
    Box(
        if (locked) {
            Modifier.fillMaxSize()
                .clearAndSetSemantics { }
                .onPreviewKeyEvent { true }
        } else Modifier.fillMaxSize(),
    ) { content() }
}

@Composable
fun BiometricGate() {
    if (!biometricGateActive()) return
    val context = LocalContext.current
    // «Назад» под замком не закрывает листы под ним — DSH уходит в фон, как с экрана блокировки.
    androidx.activity.compose.BackHandler { (context as? android.app.Activity)?.moveTaskToBack(true) }
    var asking by remember { mutableStateOf(false) }
    val title = stringResource(R.string.bio_title)
    val sub = stringResource(R.string.bio_sub)
    fun ask() {
        if (asking || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prompt = BiometricLock.prompt(context, title, sub)
        asking = true
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
