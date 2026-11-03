package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginDisplayNameTest {
    @Test
    fun `plugin names without a title are made readable`() {
        assertEquals("Base", pluginDisplayName("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-base"))
        assertEquals("Web app", pluginDisplayName("web-app", "@deepseek-ai/dsh-web-app"))
        assertEquals("Mobile bridge", pluginDisplayName("", "dsh-mobile-bridge-plugin"))
        assertEquals("Agent Teams", pluginDisplayName("Agent Teams", "@deepseek-ai/dsh-experimental-agent-team-profile"))
        assertEquals("ACP app", pluginDisplayName("acp-app", "@deepseek-ai/dsh-acp-app"))
        assertEquals("SDK minimal", pluginDisplayName("sdk-minimal", "@deepseek-ai/dsh-sdk-minimal"))
    }
}
