package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.data.ClaudeCodeBandSnapshot
import com.deepseek.dsh.core.protocol.HostJobInfo
import com.deepseek.dsh.core.protocol.SessionRawFrame
import com.deepseek.dsh.feature.sessions.SessionDisplayItem
import com.deepseek.dsh.feature.sessions.WorkspaceFolder
import com.deepseek.dsh.plugin.nativehost.NativePluginItem

/**
 * Состояние главного экрана во время работы: кэш каталога моделей, страница плагинов,
 * голос, терминал, установка rootfs, подсказки запуска Host, окна папок, переименования и вывода задач.
 *
 * Как и [AppUiState], держатель выносит состояние из MainAppHost: каждое состояние
 * внутри одной огромной функции стоит захвата и регистров.
 */
class AppRuntimeUiState(
    initialOnboarding: Boolean,
    initialRootfsProgress: Pair<Float, String>,
) {
    var showOnboarding by mutableStateOf(initialOnboarding)

    // Сырые данные каталога провайдеров моделей
    var modelProvidersRaw by mutableStateOf<String?>(null)
    var modelSettingsRaw by mutableStateOf<String?>(null)
    var modelCredentialsRaw by mutableStateOf<String?>(null)
    var registeredProviderIds by mutableStateOf<List<String>>(emptyList())
    var agentTeamLoading by mutableStateOf(false)

    // Страница плагинов и установка
    var managedPluginPackages by mutableStateOf(emptySet<String>())
    var nativePluginItems by mutableStateOf<List<NativePluginItem>>(emptyList())
    /** Нативные плагины, найденные при последнем сканировании, со статусом доверия. */
    var nativeDiscovered by mutableStateOf<List<com.deepseek.dsh.plugin.nativehost.InstalledNativePlugin>>(emptyList())

    // Полоса модов Claude Code
    var bandSnapshot by mutableStateOf<ClaudeCodeBandSnapshot?>(null)
    var bandPressing by mutableStateOf(false)

    // Голосовой ввод
    var voiceRecording by mutableStateOf(false)
    var voiceSeconds by mutableStateOf(0)
    var voiceLevel by mutableStateOf(0f)
    var voiceBusy by mutableStateOf(false)
    var voiceError by mutableStateOf<String?>(null)
    var voiceTranscript by mutableStateOf<String?>(null)

    // Сессия и отправка
    var isSending by mutableStateOf(false)
    var chatPlanMode by mutableStateOf(false)
    var wasSessionRunning by mutableStateOf(false)
    var didInitialResume by mutableStateOf(false)
    var rawSessionFrames by mutableStateOf<List<SessionRawFrame>>(emptyList())

    // Установка и обновление rootfs
    var rootfsReady by mutableStateOf<Boolean?>(null)
    var rootfsSetupProgress by mutableStateOf(initialRootfsProgress)
    var rootfsSetupError by mutableStateOf<String?>(null)
    var rootfsInstallAttempt by mutableStateOf(0)
    var rootfsUpdateAvailable by mutableStateOf(false)
    var installedRootfsVersion by mutableStateOf<String?>(null)
    var availableRootfsVersion by mutableStateOf<String?>(null)
    var showRootfsUpdatePrompt by mutableStateOf(false)
    var rootfsUpdateBannerDismissed by mutableStateOf(false)
    var forceRootfsInstall by mutableStateOf(false)

    // Подсказки запуска Host
    /**
     * Пройден ли экран запуска Host. Значение переживает пересоздание Activity (смена
     * языка) в пределах процесса: иначе после смены языка пользователь снова попадал
     * на экран запуска и должен был нажимать «Всё равно продолжить».
     */
    var hostProgressReady by mutableStateOf(StartupGateMemory.passed)
    var hostStartupError by mutableStateOf(false)
    var hostStartupDetail by mutableStateOf<String?>(null)
    var hostRetryKey by mutableStateOf(0)

    // Готовность данных
    var pluginsHydrated by mutableStateOf(false)
    var accountHydrated by mutableStateOf(false)
    var localHostBound by mutableStateOf(false)

    // Рабочие папки и окна
    var workspaceRevision by mutableStateOf(0)
    var activeWorkspaceName by mutableStateOf(DEFAULT_WORKSPACE_DIR_NAME)
    var showAddWorkspaceDialog by mutableStateOf(false)
    var newWorkspaceName by mutableStateOf("")
    var renameWorkspaceFolder by mutableStateOf<WorkspaceFolder?>(null)
    var renameWorkspaceName by mutableStateOf("")
    var renameSessionTarget by mutableStateOf<SessionDisplayItem?>(null)
    var renameSessionTitle by mutableStateOf("")
    var remoteSearchResults by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var jobOutputTarget by mutableStateOf<HostJobInfo?>(null)
    var jobOutputText by mutableStateOf("")
    var jobOutputStatus by mutableStateOf<String?>(null)
    var deleteWorkspaceFolder by mutableStateOf<WorkspaceFolder?>(null)

    // Счётчики обновления файлов и папок
    var filesRevision by mutableStateOf(0)
}

/** Создать состояние; начальные значения зависят от context и флага первого запуска. */
@Composable
fun rememberAppRuntimeUiState(
    initialOnboarding: Boolean,
    initialRootfsProgress: Pair<Float, String>,
): AppRuntimeUiState = remember { AppRuntimeUiState(initialOnboarding, initialRootfsProgress) }

/** Память о пройденном экране запуска на время жизни процесса. */
internal object StartupGateMemory {
    @Volatile
    var passed: Boolean = false
}
