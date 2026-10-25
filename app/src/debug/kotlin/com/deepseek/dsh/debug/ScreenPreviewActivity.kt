package com.deepseek.dsh.debug

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
}
