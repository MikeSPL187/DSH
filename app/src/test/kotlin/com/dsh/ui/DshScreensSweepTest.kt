package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.dsh.core.designsystem.DshTheme
import com.dsh.feature.settings.ui.CatalogUi
import com.dsh.feature.settings.ui.KeyState
import com.dsh.feature.settings.ui.PluginCall
import com.dsh.feature.settings.ui.PluginPerm
import com.dsh.feature.settings.ui.PluginSetting
import com.dsh.feature.settings.ui.PluginTool
import com.dsh.feature.settings.ui.PluginUi
import com.dsh.feature.settings.ui.PluginsHomeScreen
import com.dsh.feature.settings.ui.ToolMode
import com.dsh.feature.settings.ui.TrustCandidate
import com.dsh.feature.settings.ui.RemoteHostRow
import com.dsh.feature.settings.ui.SettingsHomeScreen
import com.dsh.feature.settings.ui.SettingsUi
import com.dsh.feature.settings.ui.StPage
import com.dsh.feature.settings.ui.StorageUsage
import com.dsh.feature.settings.ui.TrustedPluginRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Обход экранов «Плагины», «Автоматизация», «Настройки» как в `docs/design/tools/sweep.js`:
 * ширины 360 / 390 / 412, светлая и тёмная тема, каждая страница во всю длину
 * (`app/build/ui-sweep/<экран>-<ширина>-<тема>.png`). Данные — те же, что в прототипе,
 * чтобы снимки сравнивались с прототипом рядом.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-w412dp-h2400dp-xxhdpi")
class DshScreensSweepTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("user.dir"), "build/ui-sweep").apply { mkdirs() }

    private class Frame(val width: Int, val dark: Boolean, val name: String, val content: @Composable () -> Unit) {
        /** Листы снимаем в высоту телефона: их место — у нижнего края экрана. */
        val height: Int get() = if (name.startsWith("term") || name.startsWith("chat") || name.startsWith("start") || name.contains("sheet") || name.endsWith("-add") || name.endsWith("-trust")) (if (width == 360) 740 else if (width == 390) 844 else 915) else 2400
    }

    /** Отрисовать по очереди каждый кадр в окне [w]×2400 и сохранить его, обрезав пустой низ. */
    private fun sweep(frames: List<Frame>) {
        var current by mutableStateOf(frames.first())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val f = current
            DshTheme(darkTheme = f.dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { Box(Modifier.requiredSize(f.width.dp, f.height.dp).background(com.dsh.core.designsystem.Dsh.colors.bg)) {
                    key(f.name, f.width, f.dark) { f.content() }
                } }
            }
        }
        frames.forEach { f ->
            compose.runOnUiThread { current = f }
            repeat(4) {
                compose.mainClock.advanceTimeBy(700)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            save(f)
        }
    }

    private fun save(f: Frame) {
        val root = compose.activity.window.decorView
        val full = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(full))
        val density = compose.activity.resources.displayMetrics.density
        val w = (f.width * density).toInt().coerceAtMost(full.width)
        // Низ без содержимого (цвет фона) отрезаем, оставляя поле 28 dp, как в прототипе.
        val bg = full.getPixel(w - 1, full.height - 1)
        var bottom = full.height - 1
        scan@ while (bottom > 0) {
            for (x in 0 until w step 3) if (full.getPixel(x, bottom) != bg) break@scan
            bottom--
        }
        val h = if (f.height < 2400) (f.height * density).toInt() else (bottom + (28 * density).toInt()).coerceIn((600 * density).toInt(), full.height)
        val out = android.graphics.Bitmap.createBitmap(full, 0, 0, w, h)
        File(dir, "${f.name}-${f.width}-${if (f.dark) "dark" else "light"}.png").outputStream().use {
            out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun matrix(names: List<Pair<String, @Composable () -> Unit>>): List<Frame> =
        listOf(360, 390, 412).flatMap { w -> listOf(false, true).flatMap { d -> names.map { (n, c) -> Frame(w, d, n, c) } } }

    // ---------- Настройки ----------

    private val st = SettingsUi(
        appVersion = "1.1.0", appBuild = 110, hostVersion = "0.2.1", sdkVersion = "0.2.1-rc.3", distro = "Debian 12", androidVersion = "Android 14",
        accountName = "Михаил", keyState = KeyState.Ok, keyLast4 = "9a65", keyAdded = "12 окт",
        balance = "12,40 $", today = "0,38 $", monthSpent = "7,60 $", monthSpentFraction = 0.38f, balanceDays = 30,
        defaultModel = "DeepSeek V4", reasoning = "Высокий", automationModel = "V4 · Низкий",
        pluginsOn = 3, automationsOn = 3, autoApprove = false,
        theme = "light", language = "ru", codeSize = 13,
        hostRunning = true, hostUptime = "2 ч 14 мин", hostMemory = "412 МБ", hostMemoryFraction = 0.34f, hostCpu = 6,
        remotes = listOf(RemoteHostRow("r1", "Рабочий ноутбук", "4F:9A…C2", online = false)), rootfsUpdated = "20 окт",
        storage = StorageUsage(linux = 1_288_490_188, projects = 1_395_864_371, cache = 429_916_160, other = 251_658_240, logs = 39_845_888, device = 68_719_476_736),
        biometricAvailable = true,
        trusted = listOf(
            TrustedPluginRow("bridge", "Мост устройства", "М", 160f, "A1:7C:0E…54:9B", blocked = false),
            TrustedPluginRow("md", "Markdown Preview", "M", 280f, "9D:22:B4…01:7F", blocked = true),
        ),
    )

    @Test
    fun settings() = sweep(
        matrix(
            StPage.entries.map { p ->
                "st-${p.name.lowercase()}" to @Composable {
                    SettingsHomeScreen(st.copy(theme = if (com.dsh.core.designsystem.Dsh.colors.isDark) "dark" else "light"), onClose = {}, initialPage = p)
                }
            } + listOf(
                "st-nokey" to @Composable { SettingsHomeScreen(SettingsUi(appVersion = "1.1.0", androidVersion = "Android 14"), onClose = {}) },
                "st-host-off" to @Composable { SettingsHomeScreen(st.copy(hostRunning = false), onClose = {}, initialPage = StPage.Host) },
            ),
        ),
    )

    // ---------- Плагины ----------

    private val pl = listOf(
        PluginUi(
            "arch", "Archive Manager", "A", 32f, native = false, version = "0.2.1", on = true, update = "0.3.0", changelog = "Поддержка .7z и прогресс распаковки",
            by = "DSH Team", size = "1,2 МБ", description = "Даёт агенту инструменты для архивов: создать, распаковать, посмотреть содержимое.", compat = "Host 0.2.1 · нужен ≥ 0.2",
            tools = listOf(PluginTool("archive.create", "Создать архив из папки", ToolMode.Auto), PluginTool("archive.extract", "Распаковать в проект", ToolMode.Ask), PluginTool("archive.list", "Показать содержимое", ToolMode.Auto)),
            perms = listOf(PluginPerm("folder", "Файлы проекта", "чтение и запись", true)),
            settings = listOf(PluginSetting("format", "Формат", listOf("tar.gz", "zip", "7z"), 0), PluginSetting("level", "Сжатие", listOf("Быстрое", "Обычное", "Сильное"), 1)),
            calls = 14, errors = 0, last = "10:52" to "сегодня", hasSource = true,
            log = listOf(PluginCall("archive.create", "10:52", "38 с", "ok", "~/backups/DSH-2026-10-24.tar.gz"), PluginCall("archive.list", "10:51", "0,2 с", "ok", "47 файлов"), PluginCall("archive.extract", "вчера", "—", "ask", "Вы отклонили")),
        ),
        PluginUi(
            "git", "Git Tools", "G", 12f, native = false, version = "1.4.0", on = true, by = "DSH Team", size = "640 КБ",
            description = "История, авторство строк и сравнение веток прямо в ответах агента.", compat = "Host 0.2.1 · нужен ≥ 0.2",
            tools = listOf(PluginTool("git.log", "История коммитов", ToolMode.Auto), PluginTool("git.blame", "Кто менял строки", ToolMode.Auto), PluginTool("git.compare", "Сравнить ветки", ToolMode.Auto)),
            calls = 31, errors = 1, last = "09:20" to "сегодня", hasSource = true,
            log = listOf(PluginCall("git.log", "09:20", "0,3 с", "ok", "50 коммитов"), PluginCall("git.compare", "09:18", "1,1 с", "err", "Ветка feature/ui не найдена"), PluginCall("git.blame", "вчера", "0,4 с", "ok", "MainActivity.kt")),
        ),
        PluginUi(
            "bridge", "Мост устройства", "М", 160f, native = true, version = "2.0.1", on = true, by = "DSH Team", size = "3,4 МБ", hasUi = true,
            description = "Агент может показывать уведомления, открывать ссылки и узнавать заряд батареи.", compat = "SDK 0.2.1-rc.3 · совместим",
            tools = listOf(PluginTool("device.notify", "Показать уведомление", ToolMode.Auto), PluginTool("device.open", "Открыть ссылку или приложение", ToolMode.Ask), PluginTool("device.battery", "Заряд и питание", ToolMode.Auto)),
            perms = listOf(PluginPerm("bell", "Уведомления", "разрешено", true), PluginPerm("copy", "Буфер обмена", "закрыт", false)),
            pkg = "dev.dsh.bridge", cert = "A1:7C:0E…54:9B", trustedAt = "12 окт", calls = 6, last = "18:04" to "вчера",
        ),
        PluginUi(
            "md", "Markdown Preview", "M", 280f, native = true, version = "1.1.0", on = false, blocked = true, by = "example.dev", size = "2,1 МБ", hasUi = true,
            description = "Предпросмотр Markdown-файлов с оглавлением.", compat = "SDK 0.2.1 · совместим",
            tools = listOf(PluginTool("md.preview", "Открыть предпросмотр", ToolMode.Off)), perms = listOf(PluginPerm("folder", "Файлы проекта", "только чтение", true)),
            pkg = "com.example.mdpreview", cert = "9D:22:B4…01:7F", certWas = "C3:10:8A…E2:44", trustedAt = "3 окт",
        ),
    )
    private val cat = listOf(
        CatalogUi("fetch", "Web Fetch", "W", 210f, "Читает страницы и документацию по ссылке"),
        CatalogUi("sqlite", "SQLite Browser", "S", 190f, "Агент смотрит и меняет базы .db"),
        CatalogUi("ktlint", "ktlint", "K", 265f, "Проверка и автоформат Kotlin"),
    )
    private val wx = TrustCandidate(
        "wx", "Weather Widget", "W", 200f, "1.2.0", "com.acme.weather",
        "4F:9A:31:0C:7D:22:B4:01:9E:5A:63:C8:11:F0:2D:7B:A4:38:E6:90:5C:1F:72:BD:08:43:EE:61:9A:27:E8:C2",
        listOf(PluginPerm("globe", "Интернет", "", false, "Загрузка прогноза погоды"), PluginPerm("bell", "Уведомления", "", false)),
    )

    @Test
    fun plugins() = sweep(
        matrix(
            listOf(
                "pl-list" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}) },
                "pl-detail-arch" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialPlugin = "arch") },
                "pl-detail-bridge" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialPlugin = "bridge") },
                "pl-blocked" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialPlugin = "md") },
                "pl-log" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialPlugin = "arch", initialLog = true) },
                "pl-log-git" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialPlugin = "git", initialLog = true) },
                "pl-add" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, initialSheet = "add") },
                "pl-trust" to @Composable { PluginsHomeScreen(pl, cat, onClose = {}, trust = wx) },
                "pl-empty" to @Composable { PluginsHomeScreen(emptyList(), cat, onClose = {}) },
            ),
        ),
    )

    // ---------- Автоматизация ----------

    private val zone = java.time.ZoneId.systemDefault()
    private fun t(day: Int, h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val auNow = t(24, 12, 38)
    private val au = listOf(
        com.dsh.feature.settings.logic.Automation(
            "arch", "Ночной архив", "box", "", true, com.dsh.feature.settings.logic.AutoFreq.Daily, "03:00", listOf(0, 1, 2, 3, 4, 5, 6), charge = true, perm = "edit", notify = "err",
            project = "/w", model = "DeepSeek V4 · Низкий", prompt = "Запусти scripts/backup.sh и проверь, что архив создан. Удали копии старше 7 дней.",
            history = listOf(
                com.dsh.feature.settings.logic.AutoRun(t(24, 3), "ok", 38_000, "Архив 1,9 ГБ, удалена 1 старая копия"),
                com.dsh.feature.settings.logic.AutoRun(t(23, 3), "ok", 41_000, "Архив 1,9 ГБ"),
                com.dsh.feature.settings.logic.AutoRun(t(22, 3), "skip", null, "Пропущен: телефон не заряжался"),
            ),
        ),
        com.dsh.feature.settings.logic.Automation(
            "brief", "Утренняя сводка", "msg", "b", true, com.dsh.feature.settings.logic.AutoFreq.Work, "08:45", listOf(0, 1, 2, 3, 4), wifi = true, perm = "read", notify = "always",
            project = "/w", model = "DeepSeek V4 · Высокий", prompt = "Посмотри коммиты и изменения за вчера.",
            history = listOf(com.dsh.feature.settings.logic.AutoRun(t(24, 8, 45), "ok", 72_000, "3 коммита, 1 упавший тест")),
        ),
        com.dsh.feature.settings.logic.Automation(
            "deps", "Проверка зависимостей", "shield", "o", true, com.dsh.feature.settings.logic.AutoFreq.Week, "09:00", listOf(0), wifi = true, perm = "read", notify = "changes",
            project = "/w", model = "DeepSeek V4 · Высокий", prompt = "Проверь gradle/libs.versions.toml на обновления и известные уязвимости.",
            history = listOf(
                com.dsh.feature.settings.logic.AutoRun(t(20, 9), "err", 4_000, "Нет сети — повторю при подключении к Wi‑Fi"),
                com.dsh.feature.settings.logic.AutoRun(t(13, 9), "ok", 120_000, "2 обновления, уязвимостей нет"),
            ),
        ),
        com.dsh.feature.settings.logic.Automation(
            "tests", "Прогон тестов", "check", "g", false, com.dsh.feature.settings.logic.AutoFreq.Hour, "", emptyList(), charge = true, perm = "read", notify = "err",
            project = "/w", model = "DeepSeek V4 · Низкий", prompt = "Запусти ./gradlew test.",
            history = listOf(com.dsh.feature.settings.logic.AutoRun(t(17, 14), "ok", 180_000, "349 тестов, 0 ошибок")),
        ),
    )
    private val projects = listOf(com.dsh.feature.settings.ui.AutoProject("DSH-Workspace", "/w"))

    @Test
    fun automation() = sweep(
        matrix(
            listOf(
                "au-list" to @Composable { com.dsh.feature.settings.ui.AutomationHomeScreen(au, onClose = {}, projects = projects, now = auNow) },
                "au-edit" to @Composable { com.dsh.feature.settings.ui.AutomationHomeScreen(au, onClose = {}, projects = projects, now = auNow, initialEdit = "arch") },
                "au-edit-week" to @Composable { com.dsh.feature.settings.ui.AutomationHomeScreen(au, onClose = {}, projects = projects, now = auNow, initialEdit = "deps") },
                "au-new" to @Composable { com.dsh.feature.settings.ui.AutomationHomeScreen(au, onClose = {}, projects = projects, now = auNow, initialTemplate = 3, defaultModel = "DeepSeek V4 · Высокий" to 0) },
                "au-empty" to @Composable { com.dsh.feature.settings.ui.AutomationHomeScreen(emptyList(), onClose = {}, projects = projects, now = auNow) },
            ),
        ),
    )

    // ---------- Терминал ----------

    private val esc = "\u001b"
    private fun done(code: Int, cwd: String = "/root/workspace") = "$esc]133;D;$code;$cwd;/root\u0007"

    /** Вкладка bash как в прототипе: поток собран так, как его печатает bash с метками. */
    private fun bashModel(withExplain: Boolean = false, busy: Boolean = false): com.dsh.terminal.TermModel {
        var now = System.currentTimeMillis() - 120_000
        val m = com.dsh.terminal.TermModel("bash") { now }
        m.setupLine(); m.feed(done(0))
        fun run(cmd: String, out: String, code: Int, ms: Long) { m.submit(cmd); m.feed("$cmd\r\n$out"); now += ms; m.feed(done(code)) }
        run("git status", "На ветке main\r\nИзменения, не проиндексированные для коммита:\r\n  $esc[31mизменено:      gradle/libs.versions.toml$esc[m\r\n", 0, 40)
        run("./gradlew :app:assembleDebug", "$esc[2m> Task :app:compileDebugKotlin\r\n> Task :app:packageDebug$esc[0m\r\n$esc[32mBUILD SUCCESSFUL in 41s$esc[0m\r\n", 0, 41_200)
        m.blocks.last().folded = true
        run("adb devices", "bash: adb: команда не найдена\r\n", 127, 10)
        if (withExplain) m.blocks.last().explain = com.dsh.terminal.Explain().apply {
            text = "В Linux-окружении нет adb. Поставьте пакет — или работайте с устройством через мост DSH, он уже подключён."
            fix = "apt install -y adb"; done = true
        }
        if (busy) {
            now = System.currentTimeMillis() - 1_000
            m.submit("./gradlew test"); m.feed("./gradlew test\r\n$esc[2m> Task :core:model:test\r\n> Task :core:protocol:test$esc[0m\r\n")
        }
        return m
    }

    private fun gradleModel(): com.dsh.terminal.TermModel {
        val m = com.dsh.terminal.TermModel("gradle") { System.currentTimeMillis() - 3_000 }
        m.setupLine(); m.feed(done(0))
        m.submit("./gradlew test --continuous")
        m.feed("./gradlew test --continuous\r\n$esc[2mОжидание изменений в исходниках…\r\n> Task :core:protocol:test$esc[0m\r\n$esc[32mBUILD SUCCESSFUL$esc[0m · 182 теста\r\n")
        return m
    }

    private val termTabs = listOf(com.dsh.TermTab("bash", "bash", false), com.dsh.TermTab("gradle", "gradle test", true))
    private val termHistory = listOf("git status", "./gradlew :app:assembleDebug", "adb devices")

    @Composable
    private fun term(
        model: com.dsh.terminal.TermModel?,
        status: com.dsh.TermStatus = com.dsh.TermStatus.Ready,
        active: String = "bash",
        tabs: List<com.dsh.TermTab> = termTabs,
        font: Float = 12.5f,
    ) = com.dsh.TerminalScreen(
        tabs = tabs, activeId = active, model = model, status = status,
        subtitle = if (status == com.dsh.TermStatus.Ready) "Debian 12 · ~/workspace" else if (status == com.dsh.TermStatus.Connecting) "Подключение…" else "Нет подключения",
        connected = status == com.dsh.TermStatus.Ready, writable = status == com.dsh.TermStatus.Ready,
        history = termHistory, fontSize = font, toast = com.dsh.core.designsystem.kit.rememberToast(), onBack = {},
        onFont = {}, onClear = {}, onSelectTab = {}, onAddTab = {}, onCloseTab = {}, onRun = {}, onStdin = {}, onRaw = {},
        onExplain = {}, onCopy = {}, onRetryStatus = {}, onResize = { _, _ -> },
    )

    @Test
    fun terminal() = sweep(
        matrix(
            listOf(
                "term-main" to @Composable { term(androidx.compose.runtime.remember { bashModel() }) },
                "term-explain" to @Composable { term(androidx.compose.runtime.remember { bashModel(withExplain = true) }) },
                "term-gradle" to @Composable { term(androidx.compose.runtime.remember { gradleModel() }, active = "gradle") },
                "term-busy" to @Composable { term(androidx.compose.runtime.remember { bashModel(busy = true) }, tabs = listOf(com.dsh.TermTab("bash", "bash", true), termTabs[1])) },
                "term-font" to @Composable { term(androidx.compose.runtime.remember { bashModel() }, font = 14f) },
                "term-empty" to @Composable { term(androidx.compose.runtime.remember { com.dsh.terminal.TermModel("bash") }) },
                "term-connecting" to @Composable { term(null, com.dsh.TermStatus.Connecting, tabs = emptyList()) },
                "term-nosession" to @Composable { term(null, com.dsh.TermStatus.NoSession, tabs = emptyList()) },
                "term-closed" to @Composable { term(androidx.compose.runtime.remember { bashModel() }, com.dsh.TermStatus.Closed) },
            ),
        ),
    )

    // ---------- Чат ----------

    @Composable
    private fun chatTop(scrolled: Boolean) = com.dsh.ModernTopAppBar(title = "", workspace = "DSH-Workspace", scrolled = scrolled, onMenuClick = {})

    private fun msg(id: String, role: com.dsh.core.model.MessageRole, content: String, at: Long) =
        com.dsh.core.model.ChatMessage("h", "s", id, role, content, createdAt = at)

    private fun tool(id: String, name: String, status: String, body: String, args: String, at: Long) =
        msg("tool_$id", com.dsh.core.model.MessageRole.TOOL, "\u0002TOOL\u0002$id\u0002$name\u0002$status\u0002$body\u0002$args", at)

    /** Запрос из прототипа: чтение, сборка с ошибкой; готово — с ответом. */
    private fun agentMessages(done: Boolean): List<com.dsh.core.model.ChatMessage> {
        val t0 = 1_700_000_000_000L
        val log = "> Configure project :app\n> Task :app:preBuild UP-TO-DATE\n> Task :app:compileDebugKotlin\ne: This version (1.7.3) of the Compose Compiler\n   requires Kotlin 2.0.21 but you appear to be using 2.0.0\nBUILD FAILED in 12s"
        val list = mutableListOf(
            msg("u1", com.dsh.core.model.MessageRole.USER, "Сборка падает после обновления Compose. Найди причину и исправь.", t0),
            tool("c1", "read", "ok", "...", "{\"path\":\"app/build.gradle.kts, gradle/libs.versions.toml\"}", t0 + 1_000),
            tool("c2", "bash", "error", log, "{\"command\":\"./gradlew :app:assembleDebug\"}", t0 + 14_000),
        )
        if (done) {
            list += tool("c3", "edit", "ok", "ok", "{\"path\":\"gradle/libs.versions.toml\"}", t0 + 20_000)
            list += tool("c4", "bash", "ok", "> Task :app:compileDebugKotlin\n> Task :app:packageDebug\nBUILD SUCCESSFUL in 41s", "{\"command\":\"./gradlew :app:assembleDebug\"}", t0 + 62_000)
            list += msg("a1", com.dsh.core.model.MessageRole.ASSISTANT, "Причина — несовместимые версии: компилятор Compose 1.7.3 требует Kotlin 2.0.21, а в проекте стоял 2.0.0. Я обновил версию в `gradle/libs.versions.toml` и пересобрал проект — сборка проходит.", t0 + 64_000)
        }
        return list
    }

    private val approveEdit = com.dsh.feature.chat.ChatApproval(
        eventId = "e1", toolName = "edit", callId = "c9",
        reason = "Версия Kotlin не подходит компилятору Compose. Агент изменит одну строку.",
        args = "{\"file_path\":\"gradle/libs.versions.toml\",\"old_string\":\"kotlin = \\\"2.0.0\\\"\",\"new_string\":\"kotlin = \\\"2.0.21\\\"\"}",
        onAllow = {}, onReject = {},
    )

    private val approveDelete = com.dsh.feature.chat.ChatApproval(
        eventId = "e2", toolName = "bash", callId = "c9",
        reason = "Для ночного архива не хватает 1,2 ГБ. Эти файлы занимают больше всего места и пересоздаются при сборке.",
        args = "{\"command\":\"rm -rf build/ ~/.gradle/caches\"}",
        onAllow = {}, onReject = {},
        inspect = { paths ->
            com.dsh.feature.chat.DangerInfo(
                listOf(
                    com.dsh.feature.chat.DangerTarget(paths[0], 327_155_712, false),
                    com.dsh.feature.chat.DangerTarget(paths[1], 1_180_696_576, true),
                ),
                966_367_641,
            )
        },
    )

    @Composable
    private fun chatAgent(done: Boolean, approval: com.dsh.feature.chat.ChatApproval? = null, online: Boolean = true) = com.dsh.feature.chat.ModernChatScreen(
        messages = agentMessages(done), onSendMessage = {}, isThinking = !done, isSending = !done, approval = approval, online = online,
        sessionTitle = "Скрипт резервного копирования",
        topBar = { com.dsh.ModernTopAppBar(title = "Сборка падает после обновления Compose", workspace = "DSH-Workspace", scrolled = it, onMenuClick = {}) },
        filesBadge = 2, onOpenTerminal = {}, voiceEnabled = true,
        selectedModel = "DeepSeek V4", availableModels = listOf("DeepSeek V4"),
    )

    private val models = listOf(
        com.dsh.core.model.ModelProviderGroup(
            "deepseek-official", "DeepSeek",
            listOf(
                com.dsh.core.model.ModelItem("deepseek-v4", "DeepSeek V4", "Основная модель: код, тексты, анализ."),
                com.dsh.core.model.ModelItem("custom", "Свой провайдер", "Любой OpenAI-совместимый API по вашему ключу."),
            ),
        ),
    )

    private val efforts = listOf(
        com.dsh.feature.chat.ReasoningOption("low", "Низкий", "Отвечает почти сразу. Для простых вопросов, коротких правок и команд."),
        com.dsh.feature.chat.ReasoningOption("high", "Высокий", "Рассуждает перед ответом. Лучший выбор для большинства задач с кодом."),
        com.dsh.feature.chat.ReasoningOption("max", "Макс.", "Самый глубокий анализ: сложная отладка, архитектура, большие изменения. Медленнее и дороже."),
    )

    @Composable
    private fun chatHome(sheet: String? = null) = com.dsh.feature.chat.ModernChatScreen(
        initialSheet = sheet, modelGroups = models, online = true, defaultModelId = "deepseek-v4", reasoningOptions = efforts, selectedReasoningId = "high",
        terminalContext = com.dsh.feature.chat.ChatTerminalContext("adb devices", 127, "bash: adb: команда не найдена"), onTakePhoto = {},
        messages = emptyList(), onSendMessage = {}, topBar = { chatTop(it) }, userName = "Михаил",
        resume = com.dsh.feature.chat.ChatResume("s1", "Сборка падает после обновления Compose", 3, 5),
        filesBadge = 2, onOpenTerminal = {}, voiceEnabled = true,
        selectedModel = "DeepSeek V4", availableModels = listOf("DeepSeek V4"),
    )

    @Test
    fun chat() = sweep(
        matrix(
            listOf(
                "chat-home" to @Composable { chatHome() },
                "chat-agent" to @Composable { chatAgent(done = false) },
                "chat-done" to @Composable { chatAgent(done = true) },
                "chat-approve" to @Composable { chatAgent(done = false, approval = approveEdit) },
                "chat-danger" to @Composable { chatAgent(done = false, approval = approveDelete) },
                "chat-offline" to @Composable { chatAgent(done = false, online = false) },
                "chat-model" to @Composable { chatHome(sheet = "model") },
                "chat-plus" to @Composable { chatHome(sheet = "plus") },
                "chat-perm" to @Composable { chatHome(sheet = "permission") },
            ),
        ),
    )

    private fun at(day: Int, h: Int, m: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -day); set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
    }.timeInMillis

    /** Сессии из прототипа (`#drawer`). */
    private fun drawerSessions(): List<com.dsh.feature.sessions.DrawerSession> {
        val monday = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -3); set(java.util.Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        fun s(id: String, title: String, time: Long, preview: String? = null, running: Boolean = false, waiting: Boolean = false, pinned: Boolean = false) =
            com.dsh.feature.sessions.DrawerSession(
                id, title, if (running) "сейчас" else com.dsh.feature.sessions.drawerTime(time), time,
                running = running, waiting = waiting, pinned = pinned, preview = preview,
            )
        return listOf(
            s("p1", "Архитектура плагинов", monday, "Схема загрузки и проверки подписи", pinned = true),
            s("s1", "Сборка падает после обновления Compose", at(0, 11, 2), running = true),
            s("s2", "Скрипт резервного копирования", at(0, 10, 52), waiting = true),
            s("s3", "Разбор протокола bridge", at(0, 9, 20), "Ответ: JSON-RPC поверх loopback"),
            s("s4", "Тесты для semver", at(1, 18, 4), "Добавлено 12 тестов, все проходят"),
            s("s5", "Почему не приходят уведомления", at(1, 9, 12), "Причина в канале уведомлений"),
        )
    }

    @Composable
    private fun drawer(list: List<com.dsh.feature.sessions.DrawerSession> = drawerSessions(), query: String = "") {
        val state = androidx.compose.runtime.remember { com.dsh.feature.sessions.DshDrawerState().apply { open() } }
        com.dsh.feature.sessions.DshPushDrawer(
            state,
            drawer = {
                com.dsh.feature.sessions.DshDrawerContent(
                    hostLive = true, project = "DSH-Workspace", projectLetter = "D", onProject = {},
                    sessions = list, selectedId = "s1", onSelect = {}, onSessionMenu = {}, onNew = {},
                    query = query, onQuery = {}, searchHits = emptyList(), pluginsOn = 3, automationsOn = 2,
                    onPlugins = {}, onAutomation = {}, userName = "Михаил", footerSub = "Настройки · свой ключ DeepSeek", onSettings = {},
                )
            },
        ) { chatHome() }
    }

    @Test
    fun menu() = sweep(
        matrix(
            listOf(
                "chat-drawer" to @Composable { drawer() },
                "chat-drawer-empty" to @Composable { drawer(emptyList()) },
                "chat-drawer-search" to @Composable { drawer(query = "сборка") },
            ),
        ),
    )

    @Test
    fun startup() = sweep(
        matrix(
            listOf(
                "start-loading" to @Composable { com.dsh.feature.runtime.HostStartScreen(status = "Запуск локального Host…") },
                "start-failed" to @Composable {
                    com.dsh.feature.runtime.HostStartScreen(
                        status = "", failed = true, detail = "Локальный Host не запустился за 90 с",
                        logTail = listOf(
                            "[host] loading plugins from /data/dsh/plugins",
                            "[host] node: v22.11.0",
                            "Error: listen EADDRINUSE: address already in use 127.0.0.1:7461",
                            "    at Server.setupListenHandle (node:net:1817:16)",
                        ),
                        onCopyReport = {}, onContinue = {},
                    )
                },
                "start-access" to @Composable { com.dsh.feature.runtime.StorageAccessScreen(onAllow = {}, onPrivate = {}) },
            ),
        ),
    )
}
