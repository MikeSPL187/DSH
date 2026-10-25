package com.deepseek.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deepseek.dsh.core.bridge.BridgeNativeCompanion
import com.deepseek.dsh.core.model.AppSettings
import com.deepseek.dsh.core.model.SurfaceItem

/**
 * Чисто интерфейсное состояние главного экрана: окна, плавающие панели, навигация плагинов.
 *
 * Это не данные, а то, какое окно открыто и какая панель текущая. Собранные здесь,
 * они не требуют от [MainAppHost] захвата на каждое состояние, а страницы получают
 * держатель вместо длинного списка колбэков.
 */
class AppUiState(
    initialSettings: AppSettings,
    initialWorkspace: java.io.File,
    initialAllFilesAccess: Boolean,
) {
    // Навигация и выбор сессии
    var activePage by mutableStateOf(ActivePage.CHAT)
    var activeHostId by mutableStateOf("local-host")
    var selectedSessionId by mutableStateOf<String?>(null)
    var currentAppSettings by mutableStateOf(initialSettings)
    var settingsInitialSection by mutableStateOf<com.deepseek.dsh.feature.settings.ui.StPage?>(null)
    var hasAllFilesAccess by mutableStateOf(initialAllFilesAccess)
    var filesDir by mutableStateOf(initialWorkspace)

    var showSignInDialog by mutableStateOf(false)
    var showApiKeyDialog by mutableStateOf(false)
    var apiKeyInput by mutableStateOf("")
    var signInPending by mutableStateOf(false)
    var signInOpenedUrl by mutableStateOf<String?>(null)

    var activeSheetSurface by mutableStateOf<SurfaceItem?>(null)
    var activeOverlaySurface by mutableStateOf<SurfaceItem?>(null)
    var nativeCompanionAvailable by mutableStateOf<BridgeNativeCompanion?>(null)

    var pluginChangeNotice by mutableStateOf<List<String>?>(null)
    var pluginRestartRunning by mutableStateOf(false)
    var pluginRestartError by mutableStateOf<String?>(null)

    /** Боковая панель чата: открыта ли, текущая вкладка и файл для просмотра. */
    var sessionInspectorOpen by mutableStateOf(false)
    var pendingAgentPreset by mutableStateOf<String?>(null)
    /** Запрос, который чат отправит сам, как только откроется (новая сессия). */
    var pendingAutoSend by mutableStateOf<String?>(null)
    /** Текст, который нужно подставить в поле ввода нового чата («Спросить», «Создать с агентом»). */
    var pendingComposerText by mutableStateOf<String?>(null)
    /** Открытый экран плагина («Открыть» в карточке плагина): id плагина. */
    var pluginUiOpen by mutableStateOf<String?>(null)
}

/** Создать состояние; начальные значения — из сохранённых настроек, рабочей папки и разрешений. */
@Composable
fun rememberAppUiState(
    initialSettings: AppSettings,
    initialWorkspace: java.io.File,
    initialAllFilesAccess: Boolean,
): AppUiState = remember { AppUiState(initialSettings, initialWorkspace, initialAllFilesAccess) }
