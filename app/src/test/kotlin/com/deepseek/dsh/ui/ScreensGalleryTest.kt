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
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTextInput
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

    private val ws = "/storage/emulated/0/Documents/DSH-Workspace"
    private val wsFiles = listOf(
        FileItem("attachments", "$ws/attachments", isDirectory = true),
        FileItem("app", "$ws/app", isDirectory = true),
        FileItem("README.md", "$ws/README.md", isDirectory = false, sizeBytes = 2_048, lastModified = 1_760_000_000_000),
        FileItem("libs.versions.toml", "$ws/libs.versions.toml", isDirectory = false, sizeBytes = 3_400, lastModified = 1_760_100_000_000),
        FileItem("MainActivity.kt", "$ws/MainActivity.kt", isDirectory = false, sizeBytes = 12_800, lastModified = 1_760_200_000_000),
        FileItem("отчёт-аудита-безопасности-приложения-за-октябрь.pdf", "$ws/a.pdf", isDirectory = false, sizeBytes = 704_000, lastModified = 1_760_300_000_000),
        FileItem("backup.sh", "$ws/backup.sh", isDirectory = false, sizeBytes = 900),
        FileItem("LICENSE", "$ws/LICENSE", isDirectory = false, sizeBytes = 11_000),
    )

    @Composable
    private fun filesRoot() = FilesScreen(
        currentPath = ws, files = wsFiles, onFileClick = {}, onNavigateUp = {}, onImportClick = {}, onSendFile = {},
        rootPath = ws, onNavigateTo = {}, onClose = {},
    )

    @Test
    fun files() = render("30_files") { filesRoot() }

    @Test
    fun filesDark() = render("30b_files_dark", dark = true) { filesRoot() }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test
    fun filesNarrow() = render("30c_files_narrow") { filesRoot() }

    @Test
    fun filesSubfolder() = render("32_files_sub") {
        FilesScreen(
            currentPath = "$ws/app/src/main", files = wsFiles.drop(2).take(3), onFileClick = {}, onNavigateUp = {}, onImportClick = {}, onSendFile = {},
            rootPath = ws, onNavigateTo = {}, onClose = {},
        )
    }

    @Test
    fun filesEmpty() = render("31_files_empty") {
        FilesScreen(currentPath = ws, files = emptyList(), onFileClick = {}, onImportClick = {}, rootPath = ws, onClose = {})
    }

    @Test
    fun filesEmptyDark() = render("31b_files_empty_dark", dark = true) {
        FilesScreen(currentPath = ws, files = emptyList(), onFileClick = {}, onImportClick = {}, rootPath = ws, onClose = {})
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

    private fun renderOb(
        name: String,
        dark: Boolean = false,
        install: com.deepseek.dsh.feature.runtime.LinuxInstall = com.deepseek.dsh.feature.runtime.LinuxInstall(),
        granted: Boolean = false,
        steps: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                OnboardingScreen(
                    install = install,
                    sharedAccessGranted = granted,
                    checkKey = { _, _, _ -> com.deepseek.dsh.feature.runtime.KeyCheck.Ok(balance = "12,40 $") },
                    onWhereKey = {}, onChoosePrivate = {}, onRequestShared = {}, onRetryInstall = {},
                    notify = true, onNotifyChange = {}, language = "ru", onLanguage = {},
                    onFinish = {}, onRestoreBackup = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.steps()
        compose.mainClock.advanceTimeBy(1_600)
        ScreenShots.capture(compose, name, compose.activity)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.tap(text: String) {
        onAllNodes(androidx.compose.ui.test.hasText(text))[0].performClick()
        mainClock.advanceTimeBy(1_200)
    }

    private val keyOk: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {
        tap("Начать")
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("sk-7f3a9c2e41b84d0e9a65")
        mainClock.advanceTimeBy(1_500)
    }

    private val installing = com.deepseek.dsh.feature.runtime.LinuxInstall(progress = 0.37f, stage = 0, downloadPercent = 67, etaSeconds = 76)
    private val installed = com.deepseek.dsh.feature.runtime.LinuxInstall(progress = 1f, stage = 2, done = true)

    @Test fun ob0() = renderOb("50_ob_welcome")
    @Test fun ob0Dark() = renderOb("50b_ob_welcome_dark", dark = true)
    @Test fun ob1() = renderOb("51_ob_model") { tap("Начать") }
    @Test fun ob1Dark() = renderOb("51b_ob_model_dark", dark = true) { tap("Начать") }
    @Test fun ob1Ok() = renderOb("52_ob_model_ok", steps = keyOk)
    @Test fun ob1Custom() = renderOb("52c_ob_model_custom") { tap("Начать"); tap("Свой API") }
    @Test fun ob2() = renderOb("53_ob_folder") { keyOk(); tap("Продолжить") }
    @Test fun ob2Shared() = renderOb("53b_ob_folder_shared_dark", dark = true) { keyOk(); tap("Продолжить"); tap("В памяти телефона") }
    @Test fun ob3() = renderOb("54_ob_linux", install = installing) { keyOk(); tap("Продолжить"); tap("Продолжить") }
    @Test fun ob3Dark() = renderOb("54b_ob_linux_dark", dark = true, install = installing) { keyOk(); tap("Продолжить"); tap("Продолжить") }
    @Test fun ob3Done() = renderOb("55_ob_linux_done", install = installed) { keyOk(); tap("Продолжить"); tap("Продолжить") }
    @Test fun ob3Error() = renderOb("55b_ob_linux_error", install = installing.copy(error = "Не удалось установить защищённое соединение. Проверьте дату и время на телефоне или другую сеть.", errorDetail = "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")) { keyOk(); tap("Продолжить"); tap("Продолжить") }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun ob0Narrow() = renderOb("56_ob_welcome_360")
    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun ob2Narrow() = renderOb("56b_ob_folder_360") { keyOk(); tap("Продолжить"); tap("В памяти телефона") }
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun ob1Mid() = renderOb("57_ob_model_390", steps = keyOk)
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun ob3Mid() = renderOb("57b_ob_linux_390", install = installing) { keyOk(); tap("Продолжить"); tap("Продолжить") }

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

    private fun tool(id: String, name: String, status: String, body: String, args: String) =
        msg(id, com.deepseek.dsh.core.model.MessageRole.TOOL, "\u0002TOOL\u0002$id\u0002$name\u0002$status\u0002$body\u0002$args")

    private val agentTurn
        get() = listOf(
            msg("u1", com.deepseek.dsh.core.model.MessageRole.USER, "Сборка падает после обновления Compose. Найди причину и исправь."),
            tool("t1", "read_file", "ok", "[versions]", "{\"path\":\"gradle/libs.versions.toml\"}"),
            tool("t2", "bash", "error", "BUILD FAILED in 12s", "{\"command\":\"./gradlew :app:assembleDebug\"}"),
            tool("t3", "edit_file", "ok", "1 line changed", "{\"path\":\"gradle/libs.versions.toml\"}"),
            msg("a1", com.deepseek.dsh.core.model.MessageRole.ASSISTANT, "Причина — несовместимые версии: компилятор Compose требует Kotlin 2.0.21. Я обновил версию в `gradle/libs.versions.toml`, сборка проходит."),
        )

    private fun renderAgent(name: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { com.deepseek.dsh.feature.chat.ModernChatScreen(messages = agentTurn, onSendMessage = {}) } }
        compose.mainClock.advanceTimeBy(800)
        // Раскрываем ход работы, чтобы снять карточки шагов.
        compose.onAllNodes(androidx.compose.ui.test.hasText("Готово", substring = true))[0].performClick()
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    @Test
    fun chatAgentWork() = renderAgent("13_chat_agent", dark = false)

    @Test
    fun chatAgentWorkDark() = renderAgent("14_chat_agent_dark", dark = true)

    @Test
    fun approvalWrite() = render("15_approval_write") {
        com.deepseek.dsh.feature.chat.ModernChatScreen(messages = agentTurn.take(2), onSendMessage = {})
        SheetPreview {
            com.deepseek.dsh.feature.chat.ApprovalSheetContent(
            toolName = "edit_file",
            reason = "Версия Kotlin не подходит компилятору Compose. Агент изменит одну строку.",
            args = "{\"path\":\"gradle/libs.versions.toml\"}",
            onAllow = {}, onReject = {},
            )
        }
    }

    @Test
    fun approvalDestructive() = render("16_approval_danger", dark = true) {
        com.deepseek.dsh.feature.chat.ModernChatScreen(messages = agentTurn.take(2), onSendMessage = {})
        SheetPreview {
            com.deepseek.dsh.feature.chat.ApprovalSheetContent(
            toolName = "bash",
            reason = "Для ночного архива не хватает 1,2 ГБ. Это самые крупные файлы, которые пересоздаются сборкой.",
            args = "{\"command\":\"rm -rf build/ ~/.gradle/caches\"}",
            onAllow = {}, onReject = {},
            )
        }
    }

    @Test
    fun chatConversationDark() = render("12_chat_dark", dark = true) {
        com.deepseek.dsh.feature.chat.ModernChatScreen(
            messages = listOf(msg("m1", com.deepseek.dsh.core.model.MessageRole.USER, "Привет! Что умеешь?")),
            onSendMessage = {},
        )
    }

    // «Плагины», «Автоматизация» и «Настройки» снимает DshScreensSweepTest (все ширины и темы).

    /** Лист поверх затемнённого экрана — как его видит пользователь. */
    @Composable
    private fun SheetPreview(content: @Composable () -> Unit) {
        val c = com.deepseek.dsh.core.designsystem.Dsh.colors
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(c.scrim)) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.s1)
                    .padding(top = 20.dp),
            ) { content() }
        }
    }
}
