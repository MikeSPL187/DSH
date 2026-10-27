package com.deepseek.dsh

import android.os.Build


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.designsystem.DshTheme
import com.deepseek.dsh.feature.files.WorkspaceManager
import com.deepseek.dsh.plugin.surface.SurfaceCatalogStore
import com.deepseek.dsh.runtime.host.HostProcessRunner
import com.deepseek.dsh.runtime.host.HostRuntimeManager
import java.io.File

class MainActivity : ComponentActivity() {
    /** Внешняя папка приложения: proot на Android 11+ надёжно читает и пишет её (без MANAGE_EXTERNAL_STORAGE). */
    private val visibleWorkspaceDir: File by lazy {
        StorageAccess.workspaceRoot(this)
    }



    private val app: DshApplication get() = application as DshApplication
    private val runtimeManager: HostRuntimeManager get() = app.runtimeManager
    private val runner: HostProcessRunner get() = app.hostRunner
    private val surfaceCatalogStore = SurfaceCatalogStore()
    private val deepseekOAuthManager = com.deepseek.dsh.core.protocol.DeepSeekOAuthManager()
    private lateinit var workspaceManager: WorkspaceManager

    /** Сигнал пересканировать APK-компаньоны: растёт в onResume (после установки или удаления). */
    private val nativePluginRescanFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var nativePackageReceiver: android.content.BroadcastReceiver? = null

    override fun onUserInteraction() {
        super.onUserInteraction()
        UserActivity.touch()
    }

    override fun onPause() {
        UserActivity.isForeground = false
        BiometricLock.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        UserActivity.isForeground = true
        BiometricLock.onResume()
        UserActivity.touch()
        nativePluginRescanFlow.value += 1
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Всегда edge-to-edge: клавиатура приходит только через WindowInsets.ime, окно система
        // не сжимает. Иначе некоторые прошивки (например, MIUI) и сжимают, и сообщают отступ IME —
        // поле ввода чата поднималось дважды. На старых системах enableEdgeToEdge совместим.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        workspaceManager = WorkspaceManager(
            preferredPublicDir = visibleWorkspaceDir,
            appPrivateDir = filesDir
        )
        nativePackageReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                nativePluginRescanFlow.value += 1
            }
        }
        val packageFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            nativePackageReceiver,
            packageFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Сначала язык интерфейса, потом интерфейс — чтобы первый кадр был на нужном языке.
        AppLanguage.applySaved(this)

        // Плагинов заранее нет: список — только из Host и установок пользователя.
        setContent {
            val app = application as DshApplication
            // Светлая или тёмная тема — по настройке ui-theme в Host (та же, что в браузере),
            // без Host — как в системе.
            val effectiveDark = when (com.deepseek.dsh.feature.settings.HostTheme.preference) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // Хост нативных плагинов и снимок темы создаются внутри DshTheme: PluginTheme
            // строится из MaterialTheme и цветов панели, и снаружи темы всегда получал бы
            // светлые токены (из-за этого текст тёмных страниц плагинов был нечитаем).
            DshTheme(darkTheme = effectiveDark) {
                val nativeTheme = com.deepseek.dsh.plugin.nativehost.rememberPluginTheme()
                val nativeHost = com.deepseek.dsh.plugin.nativehost.rememberNativeHostApi(
                    dispatcher = { componentId, event, payload ->
                        app.dshRepository.plugins.bridge()?.dispatchUiEvent(componentId, event, payload)
                    },
                )
                androidx.compose.runtime.CompositionLocalProvider(
                    com.deepseek.dsh.plugin.nativehost.NativeHost.LocalHost provides nativeHost,
                    com.deepseek.dsh.plugin.nativehost.NativeHost.LocalTheme provides nativeTheme,
                    com.deepseek.dsh.core.designsystem.LocalConversationFontSize provides
                        (com.deepseek.dsh.feature.settings.HostTheme.fontSize ?: 16),
                    // «Настройки → Оформление»: размер шрифта кода и вибрация при действиях.
                    com.deepseek.dsh.core.designsystem.LocalCodeFontSize provides com.deepseek.dsh.feature.settings.logic.DshUserPrefs.codeSize,
                    androidx.compose.ui.platform.LocalHapticFeedback provides
                        (if (com.deepseek.dsh.feature.settings.logic.DshUserPrefs.haptics) androidx.compose.ui.platform.LocalHapticFeedback.current else SilentHaptics),
                ) {
                    // Значки системных панелей — по теме приложения: тёмному экрану нужны светлые.
                    androidx.compose.runtime.SideEffect {
                        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !effectiveDark
                            isAppearanceLightNavigationBars = !effectiveDark
                        }
                    }
                    val locked = biometricGateActive()
                    // Миниатюра в «Недавних» при включённой блокировке не показывает переписку (Android 13+).
                    androidx.compose.runtime.SideEffect {
                        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!com.deepseek.dsh.feature.settings.logic.DshUserPrefs.biometric)
                    }
                    LockedContent(locked) {
                    MainAppHost(
                        context = applicationContext,
                        runtimeManager = runtimeManager,
                        runner = runner,
                        surfaceStore = surfaceCatalogStore,
                        oauthManager = deepseekOAuthManager,
                        workspaceManager = workspaceManager,
                        layout = app.runtimeLayout,
                        dshRepository = app.dshRepository,
                        settingsRepository = app.settingsRepository,
                        nativePluginRescanFlow = nativePluginRescanFlow,
                    )
                    // Отстающий bridge-плагин — окно после запуска; необязательное обновление rootfs — только в настройках.
                    BridgeUpdatePrompt()
                    }
                    BiometricGate()
                }
            }
        }
    }

    override fun onDestroy() {
        nativePackageReceiver?.let { runCatching { unregisterReceiver(it) } }
        nativePackageReceiver = null
        if (isFinishing) {
            val app = application as DshApplication
            if (com.deepseek.dsh.runtime.host.HostService.shouldKeepHostInBackground(this)) {
                // Идёт задача агента и включена «защита долгих задач»: не обрываем её,
                // сервис остановит Host сам, когда задачи закончатся.
                runCatching {
                    startService(
                        android.content.Intent(this, com.deepseek.dsh.runtime.host.HostService::class.java)
                            .setAction(com.deepseek.dsh.runtime.host.HostService.ACTION_KEEP_WHILE_TASKS),
                    )
                }
            } else {
                Thread {
                    kotlinx.coroutines.runBlocking {
                        runCatching { app.runtimeManager.stop() }
                    }
                }.start()
            }
        }
        super.onDestroy()
    }
}

internal fun wireProtocolFor(providerId: String): String = when {
    providerId.contains("anthropic", ignoreCase = true) -> "anthropic-messages"
    providerId.contains("google", ignoreCase = true) -> "google-generative-ai"
    providerId.contains("openai-responses", ignoreCase = true) -> "openai-responses"
    else -> "openai-completions"
}

/** Вибрация выключена в настройках: отклики касания ничего не делают. */
private object SilentHaptics : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Unit
}
