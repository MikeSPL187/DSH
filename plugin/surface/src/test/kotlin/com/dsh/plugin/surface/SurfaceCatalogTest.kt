package com.dsh.plugin.surface

import com.dsh.core.model.SurfaceItem
import com.dsh.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceCatalogTest {

    @Test
    fun testPlacementDecision() {
        val store = SurfaceCatalogStore()

        val pageItem = SurfaceItem(
            pluginId = "dsh-context",
            slot = "settings.general.item",
            kind = SurfaceKind.PAGE
        )
        val placementPage = store.resolvePlacement(pageItem)
        assertEquals(PlacementDestination.SETTING_SHEET, placementPage.destination)
        assertTrue(placementPage.isIsolatedSupported)

        val overlayItem = SurfaceItem(
            pluginId = "dsh-pet",
            slot = "shell.overlay",
            kind = SurfaceKind.OVERLAY
        )
        val placementOverlay = store.resolvePlacement(overlayItem)
        assertEquals(PlacementDestination.PLUGIN_OVERLAY, placementOverlay.destination)

        val dockItem = SurfaceItem(
            pluginId = "dsh-meme",
            slot = "conversation.composer.dock",
            kind = SurfaceKind.DOCK
        )
        val placementDock = store.resolvePlacement(dockItem)
        assertEquals(PlacementDestination.COMPOSER_DOCK, placementDock.destination)

        val toolViewItem = SurfaceItem(
            pluginId = "dsh-genui",
            slot = "tool.call.toolview",
            kind = SurfaceKind.PAGE
        )
        val placementTool = store.resolvePlacement(toolViewItem)
        assertEquals(PlacementDestination.TOOL_VIEW_SHEET, placementTool.destination)
    }

    @Test
    fun testUpdateCatalogFromJson() {
        val store = SurfaceCatalogStore()
        val json = """
            {
                "clientId": "test-client",
                "dshVersion": "4.0.0",
                "surfaces": [
                    {
                        "pluginId": "dsh-context",
                        "slot": "settings.page",
                        "order": 1,
                        "title": "Context settings",
                        "kind": "PAGE"
                    }
                ]
            }
        """.trimIndent()

        store.updateCatalogFromJson(json)
        val catalog = store.catalogFlow.value
        assertEquals("test-client", catalog.clientId)
        assertEquals(1, catalog.surfaces.size)
        assertEquals("dsh-context", catalog.surfaces[0].pluginId)
        assertEquals("Context settings", catalog.surfaces[0].title)
    }
}
