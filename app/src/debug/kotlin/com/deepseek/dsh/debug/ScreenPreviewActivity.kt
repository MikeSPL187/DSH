package com.deepseek.dsh.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.deepseek.dsh.feature.sessions.ProjectSheetBody
import com.deepseek.dsh.feature.sessions.SessionSheetBody
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.deepseek.dsh.TermStatus
import com.deepseek.dsh.TermTab
import com.deepseek.dsh.TerminalScreen
import com.deepseek.dsh.core.designsystem.DshTheme
import com.deepseek.dsh.feature.settings.ui.rememberToast
import com.deepseek.dsh.terminal.Explain
import com.deepseek.dsh.terminal.TermModel

/**
 * Только для debug-сборки: экран терминала на устройстве без Linux, на тех же данных,
 * что снимки в `DshScreensSweepTest`. Команды «выполняет» заглушка: `echo` печатает,
 * остальное — «команда не найдена».
 *
 * `adb shell am start -n com.dsh.mobile.debug/com.deepseek.dsh.debug.ScreenPreviewActivity --ez dark true --es state main`
 */
class ScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val state = intent.getStringExtra("state") ?: "main"
        setContent {
            DshTheme(darkTheme = dark) {
                if (state.startsWith("chat")) { DrawerPreview(state); return@DshTheme }
                val model = remember { sample(state) }
                val history = remember { androidx.compose.runtime.mutableStateListOf("git status", "./gradlew :app:assembleDebug", "adb devices") }
                var font = remember { androidx.compose.runtime.mutableFloatStateOf(12.5f) }
                val toast = rememberToast()
                TerminalScreen(
                    tabs = listOf(TermTab("bash", "bash", model.live != null), TermTab("gradle", "gradle test", true)),
                    activeId = "bash", model = model, status = if (state == "connecting") TermStatus.Connecting else TermStatus.Ready,
                    subtitle = "Debian 12 · ${model.cwd ?: "~/workspace"}", connected = true, writable = true,
                    history = history, fontSize = font.floatValue, toast = toast, onBack = { finish() },
                    onFont = { font.floatValue = if (font.floatValue >= 14f) 11.5f else font.floatValue + 1.25f; toast.show("Шрифт ${font.floatValue} pt") },
                    onClear = { model.clearLocal(false) }, onSelectTab = {}, onAddTab = { toast.show("Новая вкладка bash") }, onCloseTab = {},
                    onRun = { cmd -> history.add(cmd); fake(model, cmd) },
                    onStdin = {}, onRaw = { if (it == "\u0003") model.feed(DONE(130)) },
                    onExplain = { b -> b.explain = Explain().apply { text = "Команды «${b.cmd.substringBefore(' ')}» нет в окружении. Проверьте написание или установите пакет через apt."; fix = "apt install -y ${b.cmd.substringBefore(' ')}"; done = true } },
                    onCopy = { toast.show("Скопировано") }, onRetryStatus = {}, onResize = { _, _ -> },
                )
            }
        }
    }

    private fun DONE(code: Int) = "\u001b]133;D;$code;/root/workspace;/root\u0007"

    private fun fake(m: TermModel, cmd: String) {
        m.submit(cmd)
        m.feed("$cmd\r\n")
        when {
            cmd.startsWith("echo ") -> { m.feed(cmd.removePrefix("echo ") + "\r\n"); m.feed(DONE(0)) }
            cmd.startsWith("sleep") -> Unit
            else -> { m.feed("bash: ${cmd.substringBefore(' ')}: команда не найдена\r\n"); m.feed(DONE(127)) }
        }
    }

    private fun sample(state: String): TermModel {
        val m = TermModel("bash")
        m.setupLine(); m.feed(DONE(0))
        if (state == "empty" || state == "connecting") return m
        val e = "\u001b"
        m.submit("git status"); m.feed("git status\r\nНа ветке main\r\nИзменения, не проиндексированные для коммита:\r\n  $e[31mизменено:      gradle/libs.versions.toml$e[m\r\n"); m.feed(DONE(0))
        m.submit("./gradlew :app:assembleDebug"); m.feed("x\r\n$e[2m> Task :app:compileDebugKotlin\r\n> Task :app:packageDebug$e[0m\r\n$e[32mBUILD SUCCESSFUL in 41s$e[0m\r\n"); m.feed(DONE(0))
        m.blocks.last().folded = true
        m.submit("adb devices"); m.feed("x\r\nbash: adb: команда не найдена\r\n"); m.feed(DONE(127))
        return m
    }

    /** Чат на данных прототипа: главный, ход работы, правка, удаление. */
    @androidx.compose.runtime.Composable
    private fun DrawerPreview(state: String) {
        val drawer = remember { com.deepseek.dsh.feature.sessions.DshDrawerState().apply { if (state == "chat-drawer") open() } }
        var sheet by remember { androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)?>(null) }
        var query by remember { androidx.compose.runtime.mutableStateOf("") }
        val now = System.currentTimeMillis()
        val sessions = remember {
            listOf(
                com.deepseek.dsh.feature.sessions.DrawerSession("p1", "Архитектура плагинов", "пн", now - 3 * 86_400_000L, pinned = true, preview = "Схема загрузки и проверки подписи"),
                com.deepseek.dsh.feature.sessions.DrawerSession("s1", "Сборка падает после обновления Compose", "сейчас", now, running = true),
                com.deepseek.dsh.feature.sessions.DrawerSession("s2", "Скрипт резервного копирования", com.deepseek.dsh.feature.sessions.drawerTime(now - 600_000), now - 600_000, waiting = true),
                com.deepseek.dsh.feature.sessions.DrawerSession("s3", "Разбор протокола bridge", com.deepseek.dsh.feature.sessions.drawerTime(now - 3_600_000), now - 3_600_000, preview = "Ответ: JSON-RPC поверх loopback"),
            )
        }
        val projects = listOf(com.deepseek.dsh.feature.sessions.WorkspaceFolder("w1", "DSH-Workspace"), com.deepseek.dsh.feature.sessions.WorkspaceFolder("w2", "android-app"))
        com.deepseek.dsh.core.designsystem.DshSheetScaffold(sheet, { sheet = null }) {
            com.deepseek.dsh.feature.sessions.DshPushDrawer(drawer, drawer = {
                com.deepseek.dsh.feature.sessions.DshDrawerContent(
                    hostLive = true, project = "DSH-Workspace", projectLetter = "D",
                    onProject = { sheet = { ProjectSheetBody(projects, "DSH-Workspace", { it }, { sheet = null }, { sheet = null }, { sheet = null }, { _, _ -> sheet = null }, { sheet = null }) } },
                    sessions = sessions, selectedId = "s1", onSelect = { drawer.close() },
                    onSessionMenu = { item -> sheet = { SessionSheetBody(item, { sheet = null }, { sheet = null }, { sheet = null }, { sheet = null }) } },
                    onNew = { drawer.close() }, query = query, onQuery = { query = it }, searchHits = emptyList(), pluginsOn = 3, automationsOn = 2,
                    onPlugins = {}, onAutomation = {}, userName = "Михаил", footerSub = "Настройки · свой ключ DeepSeek", onSettings = {},
                )
            }) { ChatPreview(state) { drawer.open() } }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ChatPreview(state: String, onMenu: () -> Unit) {
        val t0 = System.currentTimeMillis() - 70_000
        fun msg(id: String, role: com.deepseek.dsh.core.model.MessageRole, content: String, at: Long) = com.deepseek.dsh.core.model.ChatMessage("h", "s", id, role, content, createdAt = at)
        fun tool(id: String, name: String, status: String, body: String, args: String, at: Long) =
            msg("tool_$id", com.deepseek.dsh.core.model.MessageRole.TOOL, "\u0002TOOL\u0002$id\u0002$name\u0002$status\u0002$body\u0002$args", at)
        val log = "> Configure project :app\n> Task :app:compileDebugKotlin\ne: This version (1.7.3) of the Compose Compiler\n   requires Kotlin 2.0.21 but you appear to be using 2.0.0\nBUILD FAILED in 12s"
        val messages = if (state == "chat-home") emptyList() else listOf(
            msg("u1", com.deepseek.dsh.core.model.MessageRole.USER, "Сборка падает после обновления Compose. Найди причину и исправь.", t0),
            tool("c1", "read", "ok", "...", "{\"path\":\"app/build.gradle.kts\"}", t0 + 1_000),
            tool("c2", "bash", "error", log, "{\"command\":\"./gradlew :app:assembleDebug\"}", t0 + 14_000),
        )
        val approval = when (state) {
            "chat-approve" -> com.deepseek.dsh.feature.chat.ChatApproval("e1", "edit", "Версия Kotlin не подходит компилятору Compose. Агент изменит одну строку.",
                "{\"file_path\":\"gradle/libs.versions.toml\",\"old_string\":\"kotlin = 2.0.0\",\"new_string\":\"kotlin = 2.0.21\"}", "c9", { }, { })
            "chat-danger" -> com.deepseek.dsh.feature.chat.ChatApproval("e2", "bash", "Для ночного архива не хватает 1,2 ГБ.",
                "{\"command\":\"rm -rf build/ ~/.gradle/caches\"}", "c9", { }, { },
                inspect = { p -> com.deepseek.dsh.feature.chat.DangerInfo(listOf(com.deepseek.dsh.feature.chat.DangerTarget(p[0], 327_155_712, false), com.deepseek.dsh.feature.chat.DangerTarget(p[1], 1_180_696_576, true)), 966_367_641) })
            else -> null
        }
        com.deepseek.dsh.feature.chat.ModernChatScreen(
            messages = messages, onSendMessage = {}, isThinking = state != "chat-home", isSending = state != "chat-home",
            approval = approval, sessionTitle = "Скрипт резервного копирования",
            topBar = { sc -> com.deepseek.dsh.ModernTopAppBar(title = if (state == "chat-home") "" else "Сборка падает после обновления Compose", workspace = "DSH-Workspace", scrolled = sc, onMenuClick = onMenu) },
            userName = "Михаил", resume = if (state == "chat-home") com.deepseek.dsh.feature.chat.ChatResume("s1", "Сборка падает после обновления Compose") else null,
            filesBadge = 2, onOpenTerminal = {}, voiceEnabled = true, selectedModel = "DeepSeek V4", availableModels = listOf("DeepSeek V4"),
        )
    }
}
