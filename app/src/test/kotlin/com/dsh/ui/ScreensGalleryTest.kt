package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.dsh.core.designsystem.DshTheme
import com.dsh.core.model.AppSettings
import com.dsh.core.model.HostDescriptor
import com.dsh.feature.files.FileItem
import com.dsh.feature.files.FilesScreen
import com.dsh.feature.runtime.OnboardingScreen
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

    /** Проект как в прототипе: папки, файлы и два незакоммиченных изменения. */
    private fun demoProject(): java.io.File {
        val root = java.io.File(compose.activity.cacheDir, "DSH-Workspace").apply { deleteRecursively(); mkdirs() }
        fun put(path: String, text: String, ageMin: Long) {
            val f = java.io.File(root, path); f.parentFile?.mkdirs(); f.writeText(text)
            f.setLastModified(System.currentTimeMillis() - ageMin * 60_000)
        }
        put("app/src/main/MainActivity.kt", "package dev.dsh.app\n\nclass MainActivity\n", 60 * 26)
        put("app/src/main/AndroidManifest.xml", "<manifest/>\n", 60 * 50)
        put("app/src/test/MainViewModelTest.kt", "class MainViewModelTest\n", 60 * 27)
        put("app/build.gradle.kts", "plugins { }\n", 60 * 25)
        put("app/proguard-rules.pro", "-keep class dev.dsh.**\n", 60 * 5)
        put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org\n", 60 * 24 * 31)
        put("gradle/libs.versions.toml", "[versions]\nagp = \"8.7.2\"\ncompose-bom = \"2024.10.01\"\nkotlin = \"2.0.21\"\nroom = \"2.6.1\"\nokhttp = \"4.12.0\"\n\n[libraries]\nandroidx-core = { module = \"androidx.core:core-ktx\", version = \"1.13.1\" }\ncompose-bom = { module = \"androidx.compose:compose-bom\", version.ref = \"compose-bom\" }\nokhttp = { module = \"com.squareup.okhttp3:okhttp\", version.ref = \"okhttp\" }\n\n[plugins]\nkotlin-android = { id = \"org.jetbrains.kotlin.android\", version.ref = \"kotlin\" }\n", 2)
        put("scripts/backup.sh", "#!/usr/bin/env bash\n# Ночной архив проекта: запускается в 03:00\nset -euo pipefail\n", 40)
        put("build.gradle.kts", "plugins { }\n".repeat(200), 60 * 24)
        put("settings.gradle.kts", "include(\":app\")\n", 60 * 24 * 3)
        put("README.md", "# DSH-Workspace\n\nРабочая папка агента на телефоне.\n\n## Сборка\n./gradlew :app:assembleDebug\n", 60 * 3)
        put(".gitignore", "build/\n", 60 * 24 * 8)
        put("web/index.html", "<!doctype html>\n<html><body><h1>Сборка зелёная</h1><p>128 тестов прошли</p></body></html>\n", 30)
        return root
    }

    private val fakeGit = object : com.dsh.feature.files.FilesGit {
        override suspend fun status() = com.dsh.feature.files.GitInfo(
            "main",
            listOf(
                com.dsh.feature.files.GitChange("gradle/libs.versions.toml", com.dsh.feature.files.ChangeKind.MODIFIED, 1, 1),
                com.dsh.feature.files.GitChange("scripts/backup.sh", com.dsh.feature.files.ChangeKind.ADDED, 18, 0),
            ),
        )
        override suspend fun diff(path: String) = listOf(
            com.dsh.feature.files.DiffRow.Hunk("@@ -1,6 +1,6 @@ [versions]"),
            com.dsh.feature.files.DiffRow.Line(' ', 1, "[versions]"),
            com.dsh.feature.files.DiffRow.Line(' ', 2, "agp = \"8.7.2\""),
            com.dsh.feature.files.DiffRow.Line(' ', 3, "compose-bom = \"2024.10.01\""),
            com.dsh.feature.files.DiffRow.Line('-', 4, "kotlin = \"2.0.0\""),
            com.dsh.feature.files.DiffRow.Line('+', 4, "kotlin = \"2.0.21\""),
            com.dsh.feature.files.DiffRow.Line(' ', 5, "room = \"2.6.1\""),
            com.dsh.feature.files.DiffRow.Line(' ', 6, "okhttp = \"4.12.0\""),
        )
        override suspend fun revert(change: com.dsh.feature.files.GitChange) = true
        override suspend fun clone(url: String, intoDir: String) = Result.success(Unit)
    }

    /** Всё закоммичено, один коммит ещё не отправлен. */
    private val cleanGit = object : com.dsh.feature.files.FilesGit by fakeGit {
        override suspend fun status() = com.dsh.feature.files.GitInfo("main", emptyList())
        override suspend fun unpushed() = 1 to "Обновить Kotlin до 2.0.21, добавить ночной архив"
        override suspend fun pushTarget() = "origin/main"
    }

    private val noGitHub = object : com.dsh.feature.files.FilesGitHub {
        override fun signedIn() = false
        override suspend fun begin() = Result.success(com.dsh.core.designsystem.kit.DeviceCodeView("WDJB-MJHT", "https://github.com/login/device", 899))
        override suspend fun await(): Result<String> = kotlinx.coroutines.awaitCancellation()
        override fun errorOf(t: Throwable) = com.dsh.core.designsystem.kit.DeviceSignInError.Other(t.message.orEmpty())
    }

    private fun renderFiles(
        name: String,
        dark: Boolean = false,
        tab: Int = 0,
        git: com.dsh.feature.files.FilesGit = fakeGit,
        prepare: (java.io.File) -> Unit = {},
        steps: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        val root = demoProject().also(prepare)
        compose.setContent {
            DshTheme(darkTheme = dark) {
                FilesScreen(
                    root = root, rootLabel = "DSH-Workspace", refreshKey = 0, git = git,
                    onClose = {}, onAttach = {}, onAsk = {}, onReview = {}, commitMessage = null, onShare = {}, onImport = {}, onToast = {},
                    initialTab = tab,
                    markdown = { com.dsh.feature.chat.MarkdownMessageText(it) },
                    onOpenInBrowser = {},
                    gitHub = noGitHub,
                )
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_200)
        compose.steps()
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.open(text: String) {
        // Содержимое папки читается в фоне (IO): ждём, пока строка появится, а не надеемся на тайминг.
        mainClock.autoAdvance = true
        waitUntil(5_000) { onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        mainClock.autoAdvance = false
        onAllNodes(androidx.compose.ui.test.hasText(text))[0].performClick()
        mainClock.autoAdvance = true; waitForIdle(); mainClock.autoAdvance = false
        mainClock.advanceTimeBy(1_200)
    }

    // ── Коммит из «Изменений» ──
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun filesCommit() = renderFiles("26_files_commit_sheet", tab = 1) { open("Закоммитить") }
    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun filesCommitDark() = renderFiles("26b_files_commit_sheet_360_dark", dark = true, tab = 1) { open("Закоммитить") }
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun filesUnpushed() = renderFiles("26c_files_unpushed", tab = 1, git = cleanGit)

    @Test fun files() = renderFiles("30_files")
    @Test fun filesDark() = renderFiles("30b_files_dark", dark = true)
    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun filesNarrow() = renderFiles("30c_files_360")
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun filesMid() = renderFiles("30d_files_390")
    @Test fun filesNested() = renderFiles("31_files_nested") { open("app"); open("src") }
    @Test fun filesDoc() = renderFiles("32_files_doc") { open("libs.versions.toml") }
    @Test fun filesDocDark() = renderFiles("32b_files_doc_dark", dark = true) { open("libs.versions.toml") }
    @Test fun filesDiff() = renderFiles("33_files_diff") { open("libs.versions.toml"); open("Изменения") }
    @Test fun filesChanges() = renderFiles("34_files_changes", tab = 1)
    @Test fun filesChangesDark() = renderFiles("34b_files_changes_dark", dark = true, tab = 1)
    @Test fun filesSearch() = renderFiles("35_files_search") {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Поиск"))[0].performClick()
        mainClock.advanceTimeBy(800)
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("gradle")
        mainClock.autoAdvance = true; waitForIdle(); mainClock.autoAdvance = false
    }
    @Test fun filesSearchNone() = renderFiles("35b_files_search_none") {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Поиск"))[0].performClick()
        mainClock.advanceTimeBy(800)
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("zzz")
        mainClock.autoAdvance = true; waitForIdle(); mainClock.autoAdvance = false
    }
    @Test fun filesMore() = renderFiles("36_files_more") {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Действия"))[3].performClick()
        mainClock.advanceTimeBy(1_200)
    }
    @Test fun filesAdd() = renderFiles("37_files_add") {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Добавить"))[0].performClick()
        mainClock.advanceTimeBy(1_200)
    }
    @Test fun filesEmpty() = renderFiles("38_files_empty_dark", dark = true, prepare = { java.io.File(it, "docs").mkdirs() }) { open("docs") }

    private fun renderOb(
        name: String,
        dark: Boolean = false,
        install: com.dsh.feature.runtime.LinuxInstall = com.dsh.feature.runtime.LinuxInstall(),
        granted: Boolean = false,
        steps: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                OnboardingScreen(
                    install = install,
                    sharedAccessGranted = granted,
                    checkKey = { _, _, _ -> com.dsh.feature.runtime.KeyCheck.Ok(balance = "12,40 $") },
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

    private val installing = com.dsh.feature.runtime.LinuxInstall(progress = 0.37f, stage = 0, downloadPercent = 67, etaSeconds = 76)
    private val installed = com.dsh.feature.runtime.LinuxInstall(progress = 1f, stage = 2, done = true)

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

    @Test fun linuxSetup() = render("58_linux_setup", dark = true) {
        com.dsh.feature.runtime.LinuxSetupScreen(install = installing, onRetry = {})
    }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun ob0Narrow() = renderOb("56_ob_welcome_360")
    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun ob2Narrow() = renderOb("56b_ob_folder_360") { keyOk(); tap("Продолжить"); tap("В памяти телефона") }
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun ob1Mid() = renderOb("57_ob_model_390", steps = keyOk)
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun ob3Mid() = renderOb("57b_ob_linux_390", install = installing) { keyOk(); tap("Продолжить"); tap("Продолжить") }

    private fun msg(id: String, role: com.dsh.core.model.MessageRole, text: String) =
        com.dsh.core.model.ChatMessage("local-host", "s1", id, role, text)

    @Test
    fun chatEmpty() = render("10_chat_empty") {
        com.dsh.feature.chat.ModernChatScreen(messages = emptyList(), onSendMessage = {})
    }

    @Test
    fun chatConversation() = render("11_chat_conversation") {
        com.dsh.feature.chat.ModernChatScreen(
            messages = listOf(
                msg("m1", com.dsh.core.model.MessageRole.USER, "Проверь безопасность установщика плагинов"),
                msg(
                    "m2",
                    com.dsh.core.model.MessageRole.ASSISTANT,
                    "## Итог\n\n- Имена пакетов **проверяются** до записи\n- Обновление атомарное\n\n```kotlin\nval ok = PluginInstallSpec.isValidPackageName(name)\n```",
                ),
            ),
            onSendMessage = {},
        )
    }

    private fun tool(id: String, name: String, status: String, body: String, args: String) =
        msg(id, com.dsh.core.model.MessageRole.TOOL, "\u0002TOOL\u0002$id\u0002$name\u0002$status\u0002$body\u0002$args")

    private val agentTurn
        get() = listOf(
            msg("u1", com.dsh.core.model.MessageRole.USER, "Сборка падает после обновления Compose. Найди причину и исправь."),
            tool("t1", "read_file", "ok", "[versions]", "{\"path\":\"gradle/libs.versions.toml\"}"),
            tool("t2", "bash", "error", "BUILD FAILED in 12s", "{\"command\":\"./gradlew :app:assembleDebug\"}"),
            tool("t3", "edit_file", "ok", "1 line changed", "{\"path\":\"gradle/libs.versions.toml\"}"),
            msg("a1", com.dsh.core.model.MessageRole.ASSISTANT, "Причина — несовместимые версии: компилятор Compose требует Kotlin 2.0.21. Я обновил версию в `gradle/libs.versions.toml`, сборка проходит."),
        )

    private fun renderAgent(name: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { com.dsh.feature.chat.ModernChatScreen(messages = agentTurn, onSendMessage = {}) } }
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
    fun chatConversationDark() = render("12_chat_dark", dark = true) {
        com.dsh.feature.chat.ModernChatScreen(
            messages = listOf(msg("m1", com.dsh.core.model.MessageRole.USER, "Привет! Что умеешь?")),
            onSendMessage = {},
        )
    }

    /** G-02: Host не принял сообщение — пузырь остаётся с «Не отправлено» и «Повторить». */
    private fun renderNotSent(name: String, dark: Boolean) = render(name, dark = dark) {
        com.dsh.feature.chat.ModernChatScreen(
            messages = listOf(
                msg("m1", com.dsh.core.model.MessageRole.USER, "Привет! Что умеешь?"),
                msg("a1", com.dsh.core.model.MessageRole.ASSISTANT, "Читаю и правлю код, запускаю команды в терминале, работаю с git."),
                msg("m2", com.dsh.core.model.MessageRole.USER, "Собери проект и прогони тесты, а затем покажи, какие файлы изменились за последний коммит")
                    .copy(failed = true),
            ),
            onSendMessage = {},
        )
    }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun chatNotSentNarrow() = renderNotSent("15_chat_not_sent_360", dark = false)
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun chatNotSentDark() = renderNotSent("15b_chat_not_sent_390_dark", dark = true)
    @Test fun chatNotSentWide() = renderNotSent("15c_chat_not_sent_412", dark = false)

    /** G-05: окно восстановления — своя копия и чужая (с предупреждением). Окно — отдельный Dialog, снимаем его узел. */
    private fun renderRestore(name: String, trusted: Boolean, dark: Boolean) {
        val files = listOf("settings.json", "profiles/web/dsh.profile.yml", "plugins/enabled.json", "mcp/servers.json", "sessions/index.json", "permissions.json", "workspace.json", "theme.json")
        val plan = com.dsh.runtime.host.RestorePlan(java.io.File("/nonexistent"), files, files.take(5), trusted)
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { com.dsh.RestoreConfirmDialog(plan, onConfirm = {}, onCancel = {}) } }
        compose.mainClock.advanceTimeBy(1_500)
        captureDialog(name)
    }

    /** Окно диалога — последнее в WindowManagerGlobal; рисуем его поверх окна экрана, как видит пользователь. */
    private fun captureDialog(name: String) {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<android.view.View>
        val base = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(base.width, base.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        base.draw(canvas)
        views.last().draw(canvas)
        java.io.File(System.getProperty("user.dir"), "build/ui-screens/$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun restoreTrustedNarrow() = renderRestore("16_restore_own_360", trusted = true, dark = false)
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun restoreForeignDark() = renderRestore("16b_restore_foreign_390_dark", trusted = false, dark = true)
    @Test fun restoreForeignWide() = renderRestore("16c_restore_foreign_412", trusted = false, dark = false)

    /** G-12: удаление проекта — ввод имени; «Default» удалить нельзя. */
    private fun renderDeleteProject(name: String, folder: String, dark: Boolean) {
        val ui = com.dsh.AppUiState(com.dsh.core.model.AppSettings(), java.io.File("/nonexistent"), false)
        val runtimeUi = com.dsh.AppRuntimeUiState(false, 0f to "")
        runtimeUi.deleteWorkspaceFolder = com.dsh.feature.sessions.WorkspaceFolder(folder, folder, "/nonexistent/$folder")
        val ops = com.dsh.WorkspaceOps(java.io.File("/nonexistent"), object : com.dsh.WorkspaceHost {
            override suspend fun create(path: String) = true
            override suspend fun rename(workspaceId: String, title: String) = true
            override suspend fun delete(workspaceId: String) = true
            override suspend fun renameSession(sessionId: String, title: String) = true
        }) { null }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                com.dsh.WorkspaceDialogs(ui, runtimeUi, ops, androidx.compose.runtime.rememberCoroutineScope(), emptySet())
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        captureDialog(name)
    }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun deleteProjectNarrow() = renderDeleteProject("17_delete_project_360", "android-app", dark = false)
    @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi")
    @Test fun deleteDefaultDark() = renderDeleteProject("17b_delete_default_390_dark", "Default", dark = true)
    @Test fun deleteProjectWide() = renderDeleteProject("17c_delete_project_412_dark", "android-app", dark = true)

    /** G-19: Host не принял вход — полоса с «Перезапустить». */
    private fun renderHostDenied(name: String, dark: Boolean) = render(name, dark = dark) {
        com.dsh.feature.chat.ModernChatScreen(
            messages = listOf(msg("m1", com.dsh.core.model.MessageRole.USER, "Привет! Что умеешь?")),
            onSendMessage = {},
            online = true,
            hostDenied = true,
        )
    }

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun hostDeniedNarrow() = renderHostDenied("18_host_denied_360", dark = false)
    @Test fun hostDeniedDark() = renderHostDenied("18b_host_denied_412_dark", dark = true)

    // «Плагины», «Автоматизация» и «Настройки» снимает DshScreensSweepTest (все ширины и темы).

    /** Лист поверх затемнённого экрана — как его видит пользователь. */
    @Composable
    private fun SheetPreview(content: @Composable () -> Unit) {
        val c = com.dsh.core.designsystem.Dsh.colors
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

    // ── Ссылки «@» и цель сессии ──
    private val refFiles = listOf(
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.DIRECTORY, "app/", "@app/"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.DIRECTORY, "gradle/", "@gradle/"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.FILE, "build.gradle.kts", "@build.gradle.kts"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.FILE, "README.md", "@README.md"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.SESSION, "Архитектура плагинов", "@[Архитектура плагинов](dsh-session:YWE)", createdAt = System.currentTimeMillis() - 3 * 86_400_000L),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.SESSION, "Разбор протокола bridge", "@[Разбор протокола bridge](dsh-session:YmI)", detail = "/workspace/Bridge", createdAt = System.currentTimeMillis() - 2 * 3_600_000L),
    )
    private val refApp = listOf(
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.DIRECTORY, "app/src/", "@app/src/"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.FILE, "app/build.gradle.kts", "@app/build.gradle.kts"),
        com.dsh.core.model.ReferenceCandidate(com.dsh.core.model.ReferenceCandidate.Kind.FILE, "app/proguard-rules.pro", "@app/proguard-rules.pro"),
    )

    private fun renderMention(name: String, dark: Boolean = false, typed: String = "@", fail: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                com.dsh.feature.chat.ModernChatScreen(
                    messages = emptyList(), onSendMessage = {},
                    loadReferences = { q, _ ->
                        val items = (if (q.startsWith("app/")) refApp else refFiles).filter { it.title.contains(q.substringAfterLast('/'), ignoreCase = true) }
                        com.dsh.core.data.ContextRepository.Candidates(if (fail) emptyList() else items, failed = fail)
                    },
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Сообщение")).performTextInput(typed)
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    @Test fun mentionRoot() = renderMention("18_mention")
    @Test fun mentionRootDark() = renderMention("18b_mention_dark", dark = true)
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun mentionRoot360() = renderMention("18c_mention_360")
    @Test @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi") fun mentionRoot390() = renderMention("18d_mention_390", dark = true)
    @Test fun mentionDrill() = renderMention("18e_mention_drill", typed = "посмотри @app/")
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun mentionDrill360() = renderMention("18f_mention_drill_360_dark", dark = true, typed = "@app/")
    @Test fun mentionEmpty() = renderMention("18g_mention_empty", typed = "@zzz")
    @Test fun mentionError() = renderMention("18h_mention_error_dark", dark = true, fail = true)

    private val goalDemo = com.dsh.core.model.GoalInfo(
        objective = "Сборка зелёная после обновления Compose, все тесты проходят на x86_64 и arm64",
        phase = "active", roundsStarted = 3, maxGoalRounds = 10, id = "g1", revision = 2,
    )

    private fun renderGoal(name: String, dark: Boolean = false, goal: com.dsh.core.model.GoalInfo = goalDemo, steps: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                com.dsh.feature.chat.ModernChatScreen(
                    messages = agentTurn.take(2), onSendMessage = {}, goal = goal,
                    todos = listOf(
                        com.dsh.core.model.TodoEntryInfo("Найти несовместимую версию", "completed"),
                        com.dsh.core.model.TodoEntryInfo("Обновить Kotlin до 2.0.21", "in_progress"),
                        com.dsh.core.model.TodoEntryInfo("Прогнать тесты", "pending"),
                    ),
                    onGoalAction = { _, _ -> Result.success(Unit) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.steps()
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.openGoal() =
        onAllNodes(androidx.compose.ui.test.hasText("Сборка зелёная", substring = true))[0].performClick()

    @Test fun goalCollapsed() = renderGoal("19_goal")
    @Test fun goalOpen() = renderGoal("19b_goal_open") { openGoal() }
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun goalOpen360() = renderGoal("19c_goal_open_360_dark", dark = true) { openGoal() }
    @Test fun goalPausedSure() = renderGoal("19d_goal_paused_sure", goal = goalDemo.copy(phase = "paused")) {
        openGoal(); mainClock.advanceTimeBy(500)
        onNode(androidx.compose.ui.test.hasText("Убрать")).performClick()
    }
    @Test fun goalEdit() = renderGoal("19e_goal_edit_dark", dark = true) {
        openGoal(); mainClock.advanceTimeBy(500)
        onNode(androidx.compose.ui.test.hasText("Изменить")).performClick()
    }

    @Test
    fun chatMentionsSent() = render("20_chat_mentions_sent") {
        com.dsh.feature.chat.ModernChatScreen(
            messages = listOf(
                msg("m1", com.dsh.core.model.MessageRole.USER, "Сравни @app/build.gradle.kts с решением из @[Архитектура плагинов](dsh-session:YWE) и поправь"),
                msg("a1", com.dsh.core.model.MessageRole.ASSISTANT, "Прочитал файл и сессию: версии плагинов совпадают, отличается только `compileSdk`."),
            ),
            onSendMessage = {},
        )
    }


    // ── Проверка плана ──
    private val planDemo = com.dsh.feature.chat.PlanReviewInfo(
        eventId = "e1", questionId = "plan-review", approveLabel = "Approve", keepLabel = "Keep planning",
        markdown = "# Обновить Kotlin и починить сборку\n\n## Причина\n\nCompose Compiler 1.5.14 требует Kotlin 2.0.21, а в проекте 1.9.24.\n\n" +
            "## Шаги\n\n1. Поднять `kotlin` до 2.0.21 в `gradle/libs.versions.toml`\n2. Подключить плагин `org.jetbrains.kotlin.plugin.compose` в `app/build.gradle.kts`\n" +
            "3. Удалить устаревший `composeOptions.kotlinCompilerExtensionVersion`\n4. Собрать `:app:assembleDebug` и прогнать тесты\n\n## Риски\n\n- Предупреждения новых проверок Kotlin 2 — исправлю, если сборка их сделает ошибками",
    )

    private fun renderPlan(name: String, dark: Boolean = false, steps: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.() -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { com.dsh.feature.chat.PlanReviewScreen(planDemo, "Сборка падает после обновления Compose", {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(800)
        compose.steps()
        compose.mainClock.advanceTimeBy(1_000)
        ScreenShots.capture(compose, name, compose.activity)
    }

    @Test fun planReview() = renderPlan("21_plan_review")
    @Test fun planReviewDark() = renderPlan("21b_plan_review_dark", dark = true)
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun planReview360() = renderPlan("21c_plan_review_360")
    @Test @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi") fun planReviewFeedback() = renderPlan("21d_plan_feedback_390_dark", dark = true) {
        onNode(androidx.compose.ui.test.hasText("Доработать")).performClick()
    }

    @Test
    fun planCard() = render("22_plan_card") {
        com.dsh.feature.chat.ModernChatScreen(messages = agentTurn.take(3), onSendMessage = {}, pendingPlan = planDemo)
    }


    // ── Субагенты ──
    private val subDemo = listOf(
        com.dsh.core.model.SubagentInfo("c1", "Проверка тестов", com.dsh.core.model.SubagentInfo.Mode.CONTINUABLE, System.currentTimeMillis() - 120_000),
        com.dsh.core.model.SubagentInfo("c2", "Поиск устаревших API во всех модулях проекта", com.dsh.core.model.SubagentInfo.Mode.ONE_SHOT, System.currentTimeMillis() - 300_000),
        com.dsh.core.model.SubagentInfo("c3", "Codex: ревью diff", com.dsh.core.model.SubagentInfo.Mode.EXTERNAL, System.currentTimeMillis() - 86_400_000),
    )

    private fun renderSub(name: String, dark: Boolean = false, sheet: Boolean = false, view: com.dsh.feature.chat.SubagentView? = null) = render(name, dark) {
        com.dsh.feature.chat.ModernChatScreen(
            messages = if (view == null) agentTurn.take(3) else listOf(
                msg("u1", com.dsh.core.model.MessageRole.USER, "Прогони модульные тесты app и собери список упавших"),
                msg("a1", com.dsh.core.model.MessageRole.ASSISTANT, "Запустил `./gradlew :app:testDebugUnitTest`. Упали 2 теста в `ChatLogicTest`: оба ждут старый формат даты."),
            ),
            onSendMessage = {}, subagents = if (view == null) subDemo else emptyList(), runningSessionIds = setOf("c1"),
            initialSheet = if (sheet) "subagents" else null, subagentView = view,
        )
    }

    @Test fun subagentsDock() = renderSub("23_subagents_dock")
    @Test fun subagentsSheet() = renderSub("23b_subagents_sheet", sheet = true)
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun subagentsSheet360() = renderSub("23c_subagents_sheet_360_dark", dark = true, sheet = true)
    @Test fun subagentChild() = renderSub("23d_subagent_child", view = com.dsh.feature.chat.SubagentView("p1", "Сборка падает после обновления Compose", "c1", "Проверка тестов", com.dsh.core.model.SubagentInfo.Mode.CONTINUABLE))
    @Test @Config(qualifiers = "ru-w390dp-h844dp-xxhdpi") fun subagentOneShot() = renderSub("23e_subagent_one_shot_390_dark", dark = true, view = com.dsh.feature.chat.SubagentView("p1", "Сборка падает после обновления Compose", "c2", "Поиск устаревших API", com.dsh.core.model.SubagentInfo.Mode.ONE_SHOT))


    // ── Секреты в сообщении ──
    private fun renderSecret(name: String, dark: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme(darkTheme = dark) {
                com.dsh.feature.chat.ModernChatScreen(
                    messages = listOf(msg("m1", com.dsh.core.model.MessageRole.USER, "Подключи DeepSeek с ключом sk-4f8a2b9c0d1e2f3a4b5c6d7eQ9z1 и проверь")),
                    onSendMessage = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Сообщение")).performTextInput("и вот токен ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    @Test fun secretWarning() = renderSecret("24_secret_warning")
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun secretWarning360() = renderSecret("24b_secret_warning_360_dark", dark = true)


    // ── Превью документов ──
    @Test fun filesMarkdown() = renderFiles("25_files_markdown") { open("README.md") }
    @Test @Config(qualifiers = "ru-w360dp-h780dp-xxhdpi") fun filesMarkdownDark() = renderFiles("25b_files_markdown_360_dark", dark = true) { open("README.md") }
    @Test fun filesMarkdownCode() = renderFiles("25c_files_markdown_code") { open("README.md"); open("Код") }
    @Test fun filesHtml() = renderFiles("25d_files_html") { open("web"); open("index.html") }

}
