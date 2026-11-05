package com.dsh

import com.dsh.core.model.PluginRowInfo
import com.dsh.feature.settings.ui.McpStatus
import com.dsh.runtime.host.McpServers
import org.junit.Assert.*
import org.junit.Test

class McpStateTest {
    private val server = McpServers.Server("docs", "http", url = "https://example.com/mcp")
    @Test fun loaderTreeIdentityIsResolvedAfterRestart() {
        val row = PluginRowInfo("include:web-app:mcp-docs", "@deepseek-ai/dsh-mcp-client", "docs", "", phase = "active")
        val ui = mcpUi(listOf(server), listOf(row), true)
        assertEquals(McpStatus.OK, ui.servers.single().status)
        assertFalse(ui.restartNeeded)
    }
    @Test fun missingEntrySettlesAsErrorInsteadOfWaitingForAnotherRestart() {
        assertEquals(McpStatus.STARTING, mcpUi(listOf(server), emptyList(), true, settled = false).servers.single().status)
        assertEquals(McpStatus.ERROR, mcpUi(listOf(server), emptyList(), true).servers.single().status)
        assertEquals(McpStatus.STOPPED, mcpUi(listOf(server), emptyList(), false).servers.single().status)
        assertEquals(McpStatus.PENDING, mcpUi(listOf(server), emptyList(), true, restartNeeded = true).servers.single().status)
    }
}
