package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServersTest {
    private val http = McpServers.Server("github", McpServers.HTTP, url = "https://api.githubcopilot.com/mcp/", hasToken = true)
    private val stdio = McpServers.Server("docs", McpServers.STDIO, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace/docs"))

    @Test
    fun `names, addresses and token refs`() {
        assertTrue(McpServers.validName("git-hub_2"))
        assertFalse(McpServers.validName("git hub"))
        assertFalse(McpServers.validName("a".repeat(33)))
        assertTrue(McpServers.validUrl("https://mcp.linear.app/mcp"))
        assertTrue(McpServers.validUrl("http://localhost:3000/mcp"))
        assertFalse(McpServers.validUrl("http://example.com/mcp"))
        assertFalse(McpServers.validUrl("https://"))
        assertFalse(McpServers.validUrl("https://a b"))
        assertEquals("MCP_GIT_HUB_TOKEN", McpServers.tokenRefOf("git-hub"))
        assertEquals("npx" to listOf("-y", "pkg"), McpServers.splitCommand("  npx   -y  pkg "))
    }

    @Test
    fun `http block holds only the credential reference, strings are quoted`() {
        val block = McpServers.render(http.copy(url = "https://x.test/mcp?q=\"a\"#b"))
        assertTrue(block.startsWith("# android-mcp: github\n- insert:\n"))
        assertTrue(block.contains("url: \"https://x.test/mcp?q=\\\"a\\\"#b\""))
        assertTrue(block.contains("ref: \"MCP_GITHUB_TOKEN\""))
        assertTrue(block.contains("prefix: \"Bearer \""))
        assertFalse(McpServers.render(http.copy(hasToken = false)).contains("credentialHeaders"))
        val s = McpServers.render(stdio)
        assertTrue(s.contains("command: \"npx\""))
        assertTrue(s.contains("args: [\"-y\", \"@modelcontextprotocol/server-filesystem\", \"/workspace/docs\"]"))
    }

    @Test
    fun `mount replaces only our blocks and keeps plugin blocks`() {
        val plugin = "# android-installed: dsh-x\n- insert:\n    - id: x\n      name: dsh-x\n"
        val once = McpServers.mount("[]\n", listOf(http))
        assertTrue(once.startsWith("# android-mcp: github"))
        val withPlugin = once.trimEnd() + "\n\n" + plugin
        val twice = McpServers.mount(withPlugin, listOf(stdio))
        assertFalse(twice.contains("github"))
        assertTrue(twice.contains("# android-installed: dsh-x"))
        assertTrue(twice.contains("# android-mcp: docs"))
        assertEquals("[]\n", McpServers.mount(McpServers.mount("[]\n", listOf(http)), emptyList()))
        assertEquals(plugin, McpServers.mount(plugin.trimEnd() + "\n\n" + McpServers.render(http), emptyList()))
    }

    @Test
    fun `put writes registry and patch, refuses names sharing a token ref`() {
        val root = kotlin.io.path.createTempDirectory("mcp").toFile()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = java.io.File(root, "lib"))
            val patch = webAppPatchFile(layout).apply { parentFile.mkdirs(); writeText("[]\n") }
            val servers = McpServers(layout, java.io.File(root, "mcp.json"))
            servers.put(http.copy(name = "git-hub", tokenTail = "a1b2"))
            assertEquals(listOf("git-hub"), servers.list().map { it.name })
            assertEquals("a1b2", servers.list().single().tokenTail)
            assertTrue(patch.readText().contains("# android-mcp: git-hub"))
            assertTrue(runCatching { servers.put(stdio.copy(name = "GIT_HUB")) }.isFailure)
            servers.put(stdio)
            servers.remove("git-hub")
            assertEquals(listOf("docs"), servers.list().map { it.name })
            assertFalse(patch.readText().contains("git-hub"))
        } finally {
            root.deleteRecursively()
        }
    }
}
