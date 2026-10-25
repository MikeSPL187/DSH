package com.deepseek.dsh.plugin.nativehost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot
import com.deepseek.dsh.plugin.nativesdk.slots.SlotContribution
import com.deepseek.dsh.plugin.nativesdk.slots.SlotRegistry
import kotlinx.serialization.json.JsonObject

/**
 * Вкладки плагинов в панели: API места вставки на стороне приложения.
 *
 * Плагин регистрирует [SlotContribution] в [NativeSlot.RIGHT_PANEL] с `tabTitle`,
 * и в листе панелей плагинов появляется вкладка с этим названием; её содержимое — Render вклада. Встроенные вкладки
 * (Files / Document / Plan / Deliverables / Terminal / Schedule / Browser)
 * всегда первые, вкладки плагинов — по [SlotContribution.order].
 *
 * Одно описание и для панели, и для тестов: чистая [rightPaneTabDescriptors] только
 * фильтрует и сортирует, без Compose; [nativeRightPaneTabs] превращает описания в элементы для отрисовки.
 */
data class NativeRightPaneTab(
    /** Стабильный ключ: класс плагина + order, чтобы одинаковые названия не конфликтовали. */
    val key: String,
    val title: String,
    val render: @Composable () -> Unit,
)

/** Вклады панели с названием из снимка реестра (по order). */
fun rightPaneTabDescriptors(
    contributions: List<SlotContribution>,
): List<Pair<String, String>> = contributions
    .filter { it.slot == NativeSlot.RIGHT_PANEL && it.tabTitle != null }
    .sortedBy { it.order }
    .map { contribution ->
        val title = contribution.tabTitle.orEmpty()
        "${contribution.javaClass.name}#${contribution.order}" to title
    }

/**
 * Зарегистрированные вкладки плагинов в панели.
 *
 * Чтение [SlotRegistry.resolve] наблюдает версию реестра — после загрузки или удаления APK список обновится сам.
 * Без NativeHost / Theme (превью, тесты, приложение без Host) — пустой список.
 */
@Composable
fun nativeRightPaneTabs(): List<NativeRightPaneTab> {
    val host = NativeHost.LocalHost.current ?: return emptyList()
    val theme = NativeHost.LocalTheme.current ?: return emptyList()
    val contributions = SlotRegistry.resolve(NativeSlot.RIGHT_PANEL)
    val slotHost = remember(host) { SlotBridgeHost(host) }
    val descriptors = remember(contributions) { rightPaneTabDescriptors(contributions) }
    if (descriptors.isEmpty()) return emptyList()
    // contributions и descriptors соответствуют один к одному (тот же фильтр и порядок).
    val titled = remember(contributions) {
        contributions
            .filter { it.slot == NativeSlot.RIGHT_PANEL && it.tabTitle != null }
            .sortedBy { it.order }
    }
    return titled.mapIndexed { index, contribution ->
        val (key, title) = descriptors[index]
        NativeRightPaneTab(
            key = key,
            title = title,
            render = {
                NativeSlotGuard(
                    pluginLabel = contribution.javaClass.simpleName,
                    theme = theme,
                ) {
                    // У вкладок панели нет spec слота: данные плагин берёт сам через PluginRpcHost.
                    contribution.Render(JsonObject(emptyMap()), theme, slotHost)
                }
            },
        )
    }
}


/** Капсула плагина на стартовой странице панели (`sidebar.right.tab.guide.entry`). */
data class NativeGuideEntry(
    val key: String,
    val title: String,
    val description: String?,
    val render: @Composable () -> Unit,
)

/** Входы с guideTitle из снимка реестра (по order). */
fun guideEntryDescriptors(
    contributions: List<SlotContribution>,
): List<Triple<String, String, String?>> = contributions
    .filter { it.slot == NativeSlot.RIGHT_PANEL_GUIDE_ENTRY && it.guideTitle != null }
    .sortedBy { it.order }
    .map { contribution ->
        Triple(
            "${contribution.javaClass.name}#${contribution.order}",
            contribution.guideTitle.orEmpty(),
            contribution.guideDescription,
        )
    }

/** Зарегистрированные капсулы guide. */
@Composable
fun nativeGuideEntries(): List<NativeGuideEntry> {
    val host = NativeHost.LocalHost.current ?: return emptyList()
    val theme = NativeHost.LocalTheme.current ?: return emptyList()
    val contributions = SlotRegistry.resolve(NativeSlot.RIGHT_PANEL_GUIDE_ENTRY)
    val slotHost = remember(host) { SlotBridgeHost(host) }
    val descriptors = remember(contributions) { guideEntryDescriptors(contributions) }
    if (descriptors.isEmpty()) return emptyList()
    val titled = remember(contributions) {
        contributions
            .filter { it.slot == NativeSlot.RIGHT_PANEL_GUIDE_ENTRY && it.guideTitle != null }
            .sortedBy { it.order }
    }
    return titled.mapIndexed { index, contribution ->
        val (key, title, description) = descriptors[index]
        NativeGuideEntry(
            key = key,
            title = title,
            description = description,
            render = {
                NativeSlotGuard(
                    pluginLabel = contribution.javaClass.simpleName,
                    theme = theme,
                ) {
                    contribution.Render(JsonObject(emptyMap()), theme, slotHost)
                }
            },
        )
    }
}
