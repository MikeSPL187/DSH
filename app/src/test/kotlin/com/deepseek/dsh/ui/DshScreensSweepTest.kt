package com.deepseek.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
import com.deepseek.dsh.feature.settings.ui.KeyState
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

    private class Frame(val width: Int, val dark: Boolean, val name: String, val content: @Composable () -> Unit)

    /** Отрисовать по очереди каждый кадр в окне [w]×2400 и сохранить его, обрезав пустой низ. */
    private fun sweep(frames: List<Frame>) {
        var current by mutableStateOf(frames.first())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val f = current
            DshTheme(darkTheme = f.dark) {
                Box(Modifier.requiredSize(f.width.dp, 2400.dp).background(com.deepseek.dsh.core.designsystem.Dsh.colors.bg), contentAlignment = Alignment.TopStart) {
                    key(f.name, f.width, f.dark) { f.content() }
                }
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
        val h = (bottom + (28 * density).toInt()).coerceIn((600 * density).toInt(), full.height)
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
}
