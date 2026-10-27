package com.deepseek.dsh

import androidx.test.core.app.ApplicationProvider
import com.deepseek.dsh.core.protocol.ForwardedWaterfall
import com.deepseek.dsh.feature.settings.logic.ToolPolicy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** H-7: режимы инструментов действуют без интерфейса (автоматизация в фоне) — политика не из эффекта экрана. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ToolApprovalPolicyTest {
    private fun ask(tool: String, args: String = "{}") = ForwardedWaterfall(
        hostId = "local-host", event = "approval/request", eventId = "e1", agentId = null,
        request = buildJsonObject { put("toolName", tool); put("args", JsonPrimitive(args)) },
    )

    @Test
    fun modesApplyWithoutScreen() {
        ToolPolicy.init(ApplicationProvider.getApplicationContext())
        ToolPolicy.set("web_fetch", "off")
        ToolPolicy.set("read_file", "auto")
        assertEquals(false, toolApprovalPolicy(ask("web_fetch")))
        assertEquals(true, toolApprovalPolicy(ask("read_file")))
        assertNull(toolApprovalPolicy(ask("unknown_tool")))
    }

    @Test
    fun destructiveCommandIsAlwaysAsked() {
        ToolPolicy.init(ApplicationProvider.getApplicationContext())
        ToolPolicy.set("bash", "auto")
        assertNull(toolApprovalPolicy(ask("bash", """{"command":"rm -rf /workspace/src"}""")))
    }
}
