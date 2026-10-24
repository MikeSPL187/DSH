package com.deepseek.dsh.ui

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
import com.deepseek.dsh.core.designsystem.DshTheme
import com.deepseek.dsh.feature.settings.ui.CatalogUi
import com.deepseek.dsh.feature.settings.ui.KeyState
import com.deepseek.dsh.feature.settings.ui.PluginCall
import com.deepseek.dsh.feature.settings.ui.PluginPerm
import com.deepseek.dsh.feature.settings.ui.PluginSetting
import com.deepseek.dsh.feature.settings.ui.PluginTool
import com.deepseek.dsh.feature.settings.ui.PluginUi
import com.deepseek.dsh.feature.settings.ui.PluginsHomeScreen
import com.deepseek.dsh.feature.settings.ui.ToolMode
import com.deepseek.dsh.feature.settings.ui.TrustCandidate
import com.deepseek.dsh.feature.settings.ui.RemoteHostRow
import com.deepseek.dsh.feature.settings.ui.SettingsHomeScreen
import com.deepseek.dsh.feature.settings.ui.SettingsUi
import com.deepseek.dsh.feature.settings.ui.StPage
import com.deepseek.dsh.feature.settings.ui.StorageUsage
import com.deepseek.dsh.feature.settings.ui.TrustedPluginRow
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
        val height: Int get() = if (name.contains("sheet") || name.endsWith("-add") || name.endsWith("-trust")) (if (width == 360) 740 else if (width == 390) 844 else 915) else 2400
    }

    /** Отрисовать по очереди каждый кадр в окне [w]×2400 и сохранить его, обрезав пустой низ. */
    private fun sweep(frames: List<Frame>) {
        var current by mutableStateOf(frames.first())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val f = current
            DshTheme(darkTheme = f.dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { Box(Modifier.requiredSize(f.width.dp, f.height.dp).background(com.deepseek.dsh.core.designsystem.Dsh.colors.bg)) {
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
                    SettingsHomeScreen(st.copy(theme = if (com.deepseek.dsh.core.designsystem.Dsh.colors.isDark) "dark" else "light"), onClose = {}, initialPage = p)
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
        "wx", "Weather Widget", "W", 200f, "1.2.0", "com.acme.weather", "Acme Labs", "4F:9A:31…E8:C2",
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
}
