package com.dsh.plugin.surface

import com.dsh.core.model.DshSlotRegistry
import com.dsh.core.model.SurfaceCatalog
import com.dsh.core.model.SurfaceItem
import com.dsh.core.model.SurfaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Каталог поверхностей плагинов и выбор нативного места для каждого слота.
 */
enum class PlacementDestination {
    SETTING_SHEET,    // лист настроек
    SIDEBAR_PANEL,    // панель сбоку
    COMPOSER_DOCK,    // меню поля ввода / нижняя полоса капсул
    COMPOSER_OVERLAY, // всплывающее окно у поля ввода
    PLUGIN_OVERLAY,   // плавающее окно плагина (поверх, свёрнутое, пузырь)
    MESSAGE_ACTION,   // действие по долгому нажатию на сообщение
    TOOL_VIEW_SHEET,  // окно карточки инструмента
    WEB_ISLAND_FULL   // запасной путь — весь веб-островок
}

data class SurfacePlacement(
    val item: SurfaceItem,
    val destination: PlacementDestination,
    val isIsolatedSupported: Boolean
)

class SurfaceCatalogStore {
    private val _catalogFlow = MutableStateFlow(
        SurfaceCatalog(clientId = "local", dshVersion = "4.0.0")
    )
    val catalogFlow: StateFlow<SurfaceCatalog> = _catalogFlow.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun updateCatalogFromJson(rawJson: String) {
        try {
            val catalog = json.decodeFromString<SurfaceCatalog>(rawJson)
            _catalogFlow.value = catalog
        } catch (_: Exception) {
        }
    }

    /**
     * Слот DSH → нативное место на телефоне.
     */
    fun resolvePlacement(item: SurfaceItem): SurfacePlacement {
        val dest = when (item.slot) {
            DshSlotRegistry.CONVERSATION_COMPOSER_DOCK -> PlacementDestination.COMPOSER_DOCK
            DshSlotRegistry.CONVERSATION_INPUT_DOCK -> PlacementDestination.COMPOSER_DOCK
            DshSlotRegistry.CONVERSATION_INPUT_OVERLAY -> PlacementDestination.COMPOSER_OVERLAY
            DshSlotRegistry.CONVERSATION_CHAT_ASSISTANT_ACTIONS,
            DshSlotRegistry.CONVERSATION_CHAT_TURN_TAIL -> PlacementDestination.MESSAGE_ACTION
            DshSlotRegistry.TOOL_CALL_TOOLVIEW,
            DshSlotRegistry.TOOL_CALL_IMAGES -> PlacementDestination.TOOL_VIEW_SHEET
            DshSlotRegistry.SIDEBAR_WORKSPACES,
            DshSlotRegistry.SIDEBAR_PANELLIST,
            DshSlotRegistry.SIDEBAR_RIGHT_PANE_TAB,
            DshSlotRegistry.RIGHTBAR_SESSION,
            DshSlotRegistry.SIDEBAR_RIGHT_TAB_GUIDE_ENTRY,
            DshSlotRegistry.SIDEBAR_WORKSPACES_SESSION_MENU_ITEM -> PlacementDestination.SIDEBAR_PANEL
            DshSlotRegistry.SHELL_OVERLAY -> PlacementDestination.PLUGIN_OVERLAY
            DshSlotRegistry.SETTINGS_SECTION,
            DshSlotRegistry.SETTINGS_GENERAL_ITEM,
            DshSlotRegistry.PLUGINS_TAB -> PlacementDestination.SETTING_SHEET
            else -> {
                when (item.kind) {
                    SurfaceKind.PAGE -> PlacementDestination.SETTING_SHEET
                    SurfaceKind.SIDEBAR -> PlacementDestination.SIDEBAR_PANEL
                    SurfaceKind.DOCK -> PlacementDestination.COMPOSER_DOCK
                    SurfaceKind.OVERLAY -> PlacementDestination.PLUGIN_OVERLAY
                    SurfaceKind.MESSAGE_ACTION -> PlacementDestination.MESSAGE_ACTION
                    else -> PlacementDestination.WEB_ISLAND_FULL
                }
            }
        }
        return SurfacePlacement(
            item = item,
            destination = dest,
            isIsolatedSupported = item.kind != SurfaceKind.THEME
        )
    }
}
