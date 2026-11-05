package com.dsh

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.model.*
import com.dsh.feature.settings.logic.*
import com.dsh.runtime.host.HostRuntimeHolder
import com.dsh.runtime.host.HostService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class Round4BehaviorTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun automationInheritsMainModelButAnExplicitAutomationDefaultWins() {
        val catalog = ModelCatalogInfo(defaultModel = "main-id", groups = listOf(ModelProviderGroup("deepseek", "DeepSeek",
            listOf(ModelItem("main-id", "Main"), ModelItem("auto-id", "Auto")))))
        val settings = AppSettings(defaultModel = "Main")
        val inherited = automationDefault("" to 0, settings, catalog)
        assertEquals("main-id", resolveAutomationModel(Automation("a", "A"), catalog, inherited)!!.modelId)
        val explicit = automationDefault("Auto" to 1, settings, catalog)
        assertEquals("auto-id", resolveAutomationModel(Automation("a", "A"), catalog, explicit)!!.modelId)
        assertEquals("main-id", automationDefault("" to 0, AppSettings(), catalog).first)
        assertNull(resolveAutomationModel(Automation("a", "A"), catalog, "unavailable" to 0))
        val duplicate = catalog.copy(groups = catalog.groups + ModelProviderGroup("custom", "Custom", listOf(ModelItem("custom-main", "Main"))))
        assertNull(resolveAutomationModel(Automation("a", "A"), duplicate, "Main" to 0))
        val label = automationModelLabel(duplicate, duplicate.groups.last(), duplicate.groups.last().models.single())
        assertEquals("custom-main", resolveAutomationModel(Automation("a", "A", model = label), duplicate, "" to 0)!!.modelId)
    }

    @Test fun terminalIsInitiallyEnabledAndAnExplicitDisableSurvivesRecreation() {
        app.getSharedPreferences(PLUGIN_SELECTION_PREFS, 0).edit().clear().commit()
        val selection = BundleSelection(app)
        val terminal = "@deepseek-ai/dsh-experimental-terminal-bundle"
        assertTrue(selection.enabled().contains(terminal))
        selection.set(terminal, false)
        assertFalse(BundleSelection(app).enabled().contains(terminal))
    }

    @Test fun returningToTheUiCancelsDeferredHostStop() {
        HostRuntimeHolder.isUiVisible = { true }
        HostRuntimeHolder.hasRunningTasks = { false }
        val controller = Robolectric.buildService(HostService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app, HostService::class.java).setAction(HostService.ACTION_KEEP_WHILE_TASKS), 0, 1)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            assertFalse(org.robolectric.Shadows.shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
            HostRuntimeHolder.isUiVisible = null
            HostRuntimeHolder.hasRunningTasks = null
        }
    }
}
