package com.deepseek.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.deepseek.dsh.core.designsystem.DshTheme
import com.deepseek.dsh.core.model.AppSettings
import com.deepseek.dsh.core.model.HostDescriptor
import com.deepseek.dsh.feature.files.FileItem
import com.deepseek.dsh.feature.files.FilesScreen
import com.deepseek.dsh.feature.hosts.HostsScreen
import com.deepseek.dsh.feature.runtime.OnboardingScreen
import com.deepseek.dsh.feature.sessions.ModernNavigationDrawerContent
import com.deepseek.dsh.feature.sessions.SessionDisplayItem
import com.deepseek.dsh.feature.sessions.WorkspaceFolder
import com.deepseek.dsh.feature.settings.SettingsRootScreen
import com.deepseek.dsh.feature.settings.SettingsSectionTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Галерея экранов на JVM: каждый экран отрисовывается в теме приложения на русском,
 * сохраняется скриншот и текстовый дамп (app/build/ui-screens). Тест падает, если
 * экран бросает исключение при композиции/отрисовке.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class ScreensGalleryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { content() } }
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    private val sessions = listOf(
        SessionDisplayItem("s1", "Рефакторинг установщика плагинов", "5 мин", isRunning = true),
        SessionDisplayItem("s2", "Аудит безопасности APK", "2 ч", pinned = true),
        SessionDisplayItem("s3", "Старый эксперимент", "3 д", archived = true),
    )

    @Test
    fun drawer() = render("20_drawer") {
        ModernNavigationDrawerContent(
            workspaces = listOf(
                WorkspaceFolder("w1", "DSH-Workspace", sessions = sessions),
                WorkspaceFolder("w2", "проект-андроид"),
            ),
            selectedSessionId = "s1",
            onSelectSession = {},
            onNewChatClick = {},
            onNavigatePlugins = {},
            onNavigateFiles = {},
            updateVersion = "1.0.7",
        )
    }

    @Test
    fun drawerDark() = render("21_drawer_dark", dark = true) {
        ModernNavigationDrawerContent(
            workspaces = listOf(WorkspaceFolder("w1", "DSH-Workspace", sessions = sessions)),
            selectedSessionId = "s2",
            onSelectSession = {},
            onNewChatClick = {},
            onNavigatePlugins = {},
            onNavigateFiles = {},
        )
    }

    @Test
    fun files() = render("30_files") {
        FilesScreen(
            currentPath = "/storage/emulated/0/Documents/DSH-Workspace",
            files = listOf(
                FileItem("attachments", "/w/attachments", isDirectory = true),
                FileItem("README.md", "/w/README.md", isDirectory = false, sizeBytes = 2_048),
                FileItem("отчёт-аудита.pdf", "/w/отчёт-аудита.pdf", isDirectory = false, sizeBytes = 704_000),
            ),
            onFileClick = {},
            onNavigateUp = {},
            onImportClick = {},
            onSendFile = {},
        )
    }

    @Test
    fun filesEmpty() = render("31_files_empty") {
        FilesScreen(currentPath = "/w", files = emptyList(), onFileClick = {})
    }

    @Test
    fun hosts() = render("40_hosts") {
        HostsScreen(
            hosts = listOf(
                HostDescriptor.Local(),
                HostDescriptor.Remote("r1", "Рабочий ПК", "https://192.168.1.20:8443", "tok_0123456789abcdef"),
            ),
            activeHostId = "local-host",
            onSelectHost = {},
            onScanPairClick = {},
        )
    }

    @Test
    fun hostsDark() = render("41_hosts_dark", dark = true) {
        HostsScreen(
            hosts = listOf(HostDescriptor.Local()),
            activeHostId = "local-host",
            onSelectHost = {},
            onScanPairClick = {},
        )
    }

    @Test
    fun onboardingSignIn() = render("50_onboarding_signin") {
        OnboardingScreen(onComplete = {}, authenticated = false)
    }

    @Test
    fun onboardingAuthenticated() = render("51_onboarding_auth") {
        OnboardingScreen(onComplete = {}, authenticated = true, funded = false)
    }

    private fun msg(id: String, role: com.deepseek.dsh.core.model.MessageRole, text: String) =
        com.deepseek.dsh.core.model.ChatMessage("local-host", "s1", id, role, text)

    @Test
    fun chatEmpty() = render("10_chat_empty") {
        com.deepseek.dsh.feature.chat.ModernChatScreen(messages = emptyList(), onSendMessage = {})
    }

    @Test
    fun chatConversation() = render("11_chat_conversation") {
        com.deepseek.dsh.feature.chat.ModernChatScreen(
            messages = listOf(
                msg("m1", com.deepseek.dsh.core.model.MessageRole.USER, "Проверь безопасность установщика плагинов"),
                msg(
                    "m2",
                    com.deepseek.dsh.core.model.MessageRole.ASSISTANT,
                    "## Итог\n\n- Имена пакетов **проверяются** до записи\n- Обновление атомарное\n\n```kotlin\nval ok = PluginInstallSpec.isValidPackageName(name)\n```",
                ),
            ),
            onSendMessage = {},
        )
    }

    @Test
    fun chatConversationDark() = render("12_chat_dark", dark = true) {
        com.deepseek.dsh.feature.chat.ModernChatScreen(
            messages = listOf(msg("m1", com.deepseek.dsh.core.model.MessageRole.USER, "Привет! Что умеешь?")),
            onSendMessage = {},
        )
    }

    @Test
    fun plugins() = render("70_plugins") {
        com.deepseek.dsh.PluginsScreen(
            surfaces = emptyList(),
            onOpenSurface = {},
            onOpenSdui = {},
            onInstallPlugin = { _, _ -> Result.success("") },
        )
    }

    @Test
    fun settingsRoot() = render("60_settings_root") {
        SettingsRootScreen(settings = AppSettings(), onUpdateSettings = {})
    }

    @Test
    fun settingsSections() {
        // Каждый раздел — отдельный экран; все должны отрисоваться без исключений.
        var current by androidx.compose.runtime.mutableStateOf(SettingsSectionTab.ACCOUNT)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = false) {
                androidx.compose.runtime.key(current) {
                    SettingsRootScreen(settings = AppSettings(), onUpdateSettings = {}, initialSection = current)
                }
            }
        }
        SettingsSectionTab.entries.forEachIndexed { index, tab ->
            compose.runOnUiThread { current = tab }
            repeat(3) {
                compose.mainClock.advanceTimeBy(600)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            ScreenShots.capture(compose, "6${index + 1}_settings_${tab.id}", compose.activity)
        }
    }
}
