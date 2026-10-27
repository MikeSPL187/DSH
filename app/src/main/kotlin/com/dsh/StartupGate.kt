package com.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dsh.runtime.host.HostRuntimeManager
import com.dsh.runtime.host.RuntimePhase
import com.dsh.runtime.host.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ворота запуска приложения. Пока что-то не готово, показывает свой экран и возвращает false:
 * проверка Linux, установка или обязательная переустановка, доступ к папке проектов,
 * ожидание Host (до 90 с, при ошибке — причина и «Повторить»), загрузка аккаунта и плагинов
 * (до 6 с). Первый запуск (онбординг) ворота пропускают — у него свои шаги.
 *
 * @param pluginsReady снимок плагинов от Host уже получен.
 */
@Composable
internal fun StartupGate(
    runtimeUi: AppRuntimeUiState,
    ui: AppUiState,
    runtimeManager: HostRuntimeManager,
    status: RuntimeStatus,
    activeWorkspace: File,
    pluginsReady: Boolean,
    openAllFilesSettings: () -> Unit,
    scope: CoroutineScope,
): Boolean {
    val context = LocalContext.current
    /**
     * Подсказка при запуске — только если образ **обязательно** нужно переустановить.
     *
     * Сравнивает `rootfsUpdateKind` по частям версии: MANDATORY — только при смене части rootfs
     * или dsh. Отстающие плагины — дело настроек: их можно обновить на месте,
     * прерывать запуск ради них не нужно.
     */
    LaunchedEffect(runtimeUi.rootfsReady) {
        if (runtimeUi.rootfsReady != true) return@LaunchedEffect
        val installed = runtimeManager.installedRootfsVersion()
        val available = runtimeManager.availableRootfsVersion()
        runtimeUi.installedRootfsVersion = installed
        runtimeUi.availableRootfsVersion = available
        val kind = runCatching { runtimeManager.rootfsUpdateKind() }
            .getOrDefault(com.dsh.runtime.host.RootfsUpdateKind.NONE)
        val mandatory = kind == com.dsh.runtime.host.RootfsUpdateKind.MANDATORY
        runtimeUi.rootfsUpdateAvailable = mandatory
        runtimeUi.showRootfsUpdatePrompt = mandatory
    }

    if (runtimeUi.showRootfsUpdatePrompt) {
        com.dsh.core.designsystem.DshDialog(
            onDismiss = { runtimeUi.showRootfsUpdatePrompt = false },
            title = stringResource(R.string.runtime_update_title),
            closeLabel = stringResource(R.string.runtime_update_later),
            description = stringResource(R.string.runtime_update_desc, runtimeUi.availableRootfsVersion ?: "", runtimeUi.installedRootfsVersion ?: ""),
            footer = {
                com.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.runtime_update_later),
                    primary = false,
                    enabled = true,
                    onClick = { runtimeUi.showRootfsUpdatePrompt = false },
                )
                com.dsh.core.designsystem.DshDialogButton(
                    text = stringResource(R.string.runtime_update_now),
                    primary = true,
                    enabled = true,
                    onClick = {
                        runtimeUi.showRootfsUpdatePrompt = false
                        runtimeUi.forceRootfsInstall = true
                        runtimeUi.rootfsReady = false
                        scope.launch {
                            runCatching { runtimeManager.stop() }
                            runtimeUi.rootfsInstallAttempt += 1
                        }
                    },
                )
            },
        )
    }


    LaunchedEffect(runtimeUi.rootfsInstallAttempt) {
        if (runtimeManager.isRootfsInstalled()) {
            runtimeUi.forceRootfsInstall = false
            runtimeUi.rootfsReady = true
            return@LaunchedEffect
        }
        // Рабочий старый rootfs сам не перезаписывается: подсказка — только при **обязательной**
        // переустановке и с подтверждением; отстающие плагины запуск не держат (обновляются в настройках).
        val installedVersion = runtimeManager.installedRootfsVersion()
        val availableVersion = runtimeManager.availableRootfsVersion()
        val rootfsFilesPresent = java.io.File(context.filesDir, "rootfs/etc/os-release").isFile ||
            java.io.File(context.filesDir, "rootfs/usr/bin/env").isFile
        val kind = runCatching { runtimeManager.rootfsUpdateKind() }
            .getOrDefault(com.dsh.runtime.host.RootfsUpdateKind.NONE)
        if (!runtimeUi.forceRootfsInstall && rootfsFilesPresent &&
            kind == com.dsh.runtime.host.RootfsUpdateKind.MANDATORY
        ) {
            runtimeUi.installedRootfsVersion = installedVersion
            runtimeUi.availableRootfsVersion = availableVersion
            runtimeUi.rootfsUpdateAvailable = true
            runtimeUi.showRootfsUpdatePrompt = true
            runtimeUi.rootfsReady = true
            return@LaunchedEffect
        }
        runtimeUi.rootfsReady = false
        runtimeUi.rootfsSetupError = null
        val result = runtimeManager.installRootfs { progress, message ->
            runtimeUi.rootfsSetupProgress = progress to message
        }
        result.exceptionOrNull()?.let { error ->
            runtimeUi.rootfsSetupError = error.message ?: context.getString(R.string.rootfs_install_failed)
        }
        if (result.isSuccess) runtimeUi.rootfsReady = true
    }

    // Локальный Host — только после готовности rootfs, иначе ошибка запуска без установки.
    LaunchedEffect(runtimeUi.rootfsReady, ui.hasAllFilesAccess) {
        // На первом запуске Host ждёт выбора папки: он монтирует её в контейнер.
        if (runtimeUi.rootfsReady == true && ui.hasAllFilesAccess &&
            (status.phase == RuntimePhase.STOPPED ||
                status.phase == RuntimePhase.UNINSTALLED ||
                status.phase == RuntimePhase.ERROR)
        ) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runtimeManager.warmUp(activeWorkspace.absolutePath)
            }
        }
    }

    if (!runtimeUi.showOnboarding && runtimeUi.rootfsReady == null) {
        com.dsh.feature.runtime.HostStartScreen(status = stringResource(R.string.runtime_checking))
        return false
    }

    if (!runtimeUi.showOnboarding && runtimeUi.rootfsReady != true) {
        // Переустановка Linux (обязательное обновление): тот же блок, что на шаге 3 первого запуска.
        com.dsh.feature.runtime.LinuxSetupScreen(
            install = rememberFriendlyLinuxInstall(runtimeUi),
            onRetry = { runtimeUi.rootfsInstallAttempt++ },
        )
        return false
    }

    // При первом входе нужен «Доступ ко всем файлам», без него дальше нельзя.
    if (!runtimeUi.showOnboarding && !ui.hasAllFilesAccess) {
        val gateActivity = androidx.activity.compose.LocalActivity.current
        com.dsh.feature.runtime.StorageAccessScreen(
            onAllow = openAllFilesSettings,
            onPrivate = {
                StorageAccess.choosePrivateWorkspace(context)
                ui.hasAllFilesAccess = true
                // Пути рабочей папки вычисляются при создании экрана: пересоздаём его.
                gateActivity?.recreate()
            },
        )
        return false
    }

    // При каждом следующем открытии — ждём настоящей готовности Host (markReady / RUNNING).
    // Порта мало: DSH слушает порт ещё до того, как напечатает Web URL, а модели,
    // плагины и сессии ещё не готовы — главный экран был бы пустым или с «Не готово».
    LaunchedEffect(runtimeUi.rootfsReady, runtimeUi.hostRetryKey, ui.hasAllFilesAccess) {
        if (runtimeUi.rootfsReady != true || !ui.hasAllFilesAccess) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (!runtimeUi.hostProgressReady) {
            if (System.currentTimeMillis() - startedAt > 90_000L) {
                runtimeUi.hostStartupError = true
                runtimeUi.hostStartupDetail = context.getString(R.string.host_startup_timeout)
                break
            }
            val current = runCatching { runtimeManager.getStatus() }.getOrNull()
            if (current == null) {
                kotlinx.coroutines.delay(300)
                continue
            }
            when {
                current.phase == RuntimePhase.RUNNING -> {
                    runtimeUi.hostProgressReady = true
                    StartupGateMemory.passed = true
                    runtimeUi.hostStartupError = false
                    runtimeUi.hostStartupDetail = null
                }
                current.phase == RuntimePhase.ERROR -> {
                    runtimeUi.hostStartupError = true
                    runtimeUi.hostStartupDetail = null
                    break
                }
                else -> kotlinx.coroutines.delay(300)
            }
        }
    }
    if (!runtimeUi.showOnboarding && runtimeUi.rootfsReady == true && !runtimeUi.hostProgressReady) {
        val failed = runtimeUi.hostStartupError
        val failDetail = runtimeUi.hostStartupDetail ?: if (failed) status.lastError else null
        val copiedLabel = stringResource(R.string.host_startup_copied)
        com.dsh.feature.runtime.HostStartScreen(
            status = stringResource(R.string.host_starting),
            failed = failed,
            // Причина и последний вывод — здесь: это единственный вход, без них нечего исправить и некуда отправить.
            detail = failDetail,
            logTail = if (failed) status.logTail else emptyList(),
            onCopyReport = {
                // Вывод уже очищен от секретов (runner), в отчёт идёт целиком: главное часто в конце.
                val payload = buildString {
                    appendLine("DSH Host startup failed")
                    appendLine("device: ${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                    appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                    failDetail?.takeIf { it.isNotBlank() }?.let { appendLine("error: $it") }
                    status.logTail.forEach { appendLine(it) }
                }
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("DSH host startup", payload))
                android.widget.Toast.makeText(context, copiedLabel, android.widget.Toast.LENGTH_SHORT).show()
            },
            onRetry = {
                runtimeUi.hostStartupError = false
                runtimeUi.hostStartupDetail = null
                runtimeUi.hostRetryKey += 1
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { runtimeManager.start(activeWorkspace.absolutePath) }
                    }
                }
            },
            // Даже если Host не запустился, войти можно: в «Настройки → Linux» полный журнал
            // и выгрузка диагностики без секретов.
            onContinue = {
                runtimeUi.hostProgressReady = true
                StartupGateMemory.passed = true
            },
        )
        return false
    }

    // Порт готов — не значит данные готовы: аккаунт и каталог плагинов — ещё по RPC. Первый
    // экран ждёт на загрузке до 6 секунд, пока не придут аккаунт и снимок плагинов, —
    // иначе интерфейс несколько секунд пуст и потом перескакивает.
    var startupHydrated by remember { mutableStateOf(false) }
    LaunchedEffect(runtimeUi.hostProgressReady, status.port, runtimeUi.accountHydrated, runtimeUi.pluginsHydrated, pluginsReady) {
        if (!runtimeUi.hostProgressReady) {
            startupHydrated = false
            return@LaunchedEffect
        }
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            if (runtimeUi.accountHydrated && runtimeUi.pluginsHydrated && pluginsReady) break
            kotlinx.coroutines.delay(120)
        }
        startupHydrated = true
    }
    if (!runtimeUi.showOnboarding && runtimeUi.hostProgressReady && !startupHydrated) {
        com.dsh.feature.runtime.HostStartScreen(status = stringResource(R.string.host_loading_data))
        return false
    }
    return true
}
