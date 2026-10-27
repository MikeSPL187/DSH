package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshButton
import com.dsh.core.designsystem.DshButtonKind
import com.dsh.core.designsystem.DshCard
import com.dsh.core.designsystem.DshDivider
import com.dsh.core.designsystem.DshGroup
import com.dsh.core.designsystem.DshIconButton
import com.dsh.core.designsystem.DshIconTile
import com.dsh.core.designsystem.DshRow
import com.dsh.core.designsystem.DshSectionLabel
import com.dsh.core.designsystem.DshSegmented
import com.dsh.core.designsystem.DshSwitch
import com.dsh.core.designsystem.DshTheme
import com.dsh.core.designsystem.DshTileColors
import com.dsh.core.designsystem.DshTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Витрина компонентов дизайн-системы в обеих темах и на узком экране. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class DesignSystemGalleryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DshTheme(darkTheme = dark) { Showcase() } }
        compose.mainClock.advanceTimeBy(1_500)
        ScreenShots.capture(compose, name, compose.activity)
    }

    @Test fun light() = render("00_design_light", dark = false)

    @Test fun dark() = render("01_design_dark", dark = true)

    @Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")
    @Test fun narrow() = render("02_design_narrow", dark = false)
}

@Composable
private fun Showcase() {
    var sleep by remember { mutableIntStateOf(1) }
    var notify by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Dsh.colors.bg)) {
        DshTopBar(title = "Настройки", subtitle = "DSH 1.1.0", onBack = {}) {
            DshIconButton(Icons.Rounded.Search, "Поиск", onClick = {})
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            DshCard {
                Text("Баланс DeepSeek", style = Dsh.type.footnote, color = Dsh.colors.t3)
                Text("12,40 $", style = Dsh.type.largeTitle, color = Dsh.colors.t1)
                Text("≈ 30 дней при текущем расходе", style = Dsh.type.callout, color = Dsh.colors.t2)
            }
            DshSectionLabel("Провайдеры")
            DshGroup {
                DshRow("DeepSeek", subtitle = "Ключ ••••9a65 · добавлен 12 окт", leading = { DshIconTile(Icons.Rounded.Key, DshTileColors.indigo) }, value = "работает", valueColor = Dsh.colors.ok)
                DshDivider(inset = 56.dp)
                DshRow("Хранилище", leading = { DshIconTile(Icons.Rounded.Storage, DshTileColors.gray) }, value = "3,1 ГБ", chevron = true, onClick = {})
                DshDivider(inset = 56.dp)
                DshRow("Оформление", leading = { DshIconTile(Icons.Rounded.Palette, DshTileColors.violet) }, value = "Фарфор", chevron = true, onClick = {})
            }
            DshSectionLabel("Уведомления", trailing = "2")
            DshGroup {
                DshRow("Агент закончил задачу", subtitle = "Если приложение свёрнуто", leading = { DshIconTile(Icons.Rounded.Notifications, DshTileColors.green) }, trailing = { DshSwitch(notify, { notify = it }) })
                DshDivider(inset = 56.dp)
                DshRow("Агент ждёт решения", subtitle = "Одобрение правок и удаления", leading = { DshIconTile(Icons.Rounded.Notifications, DshTileColors.orange) }, trailing = { DshSwitch(false, {}) })
            }
            DshSectionLabel("Засыпать без дела")
            DshSegmented(listOf("5 мин", "15 мин", "30 мин", "Никогда"), sleep, { sleep = it })
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshButton("Отклонить", {}, Modifier.weight(1f), kind = DshButtonKind.Ghost)
                DshButton("Удалить 1,4 ГБ", {}, Modifier.weight(1.3f), kind = DshButtonKind.Danger)
            }
            Spacer(Modifier.height(8.dp))
            DshButton("Продолжить", {}, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            DshButton("Ничего не выбрано", {}, Modifier.fillMaxWidth(), enabled = false)
            Text("kotlin = \"2.0.21\"", style = Dsh.type.code, color = Dsh.colors.t1, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}
