package com.deepseek.dsh

import com.deepseek.dsh.core.data.HostWorkspaceInfo
import com.deepseek.dsh.core.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WorkspaceFoldersTest {
    private val root = File("/storage/emulated/0/Documents/DSH-Workspace")
    private fun dir(name: String) = File(root, name)
    private fun session(id: String, path: String?, updatedAt: Long = 0) =
        SessionInfo(hostId = "local", sessionId = id, title = id, workspacePath = path, status = "idle", updatedAt = updatedAt)

    @Test
    fun `folder name is found in host and phone paths`() {
        assertEquals("App", projectFolderOf("/workspace/App", root))
        assertEquals("App", projectFolderOf("/workspace/App/src/main", root))
        assertEquals("App", projectFolderOf("${root.absolutePath}/App/", root))
        assertNull(projectFolderOf("/workspace", root))
        assertNull(projectFolderOf("/sdcard/Other/App", root))
        assertNull(projectFolderOf(null, root))
    }

    @Test
    fun `sessions go to their own folder, not to a folder with the same prefix`() {
        val folders = buildWorkspaceFolders(
            dirs = listOf(dir("App"), dir("App2")),
            root = root,
            sessions = listOf(session("a", "/workspace/App"), session("b", "/workspace/App2")),
            hostWorkspaces = emptyList(),
            selectedSessionId = "b",
            runningIds = setOf("a"),
            unreadIds = emptySet(),
            timeAgo = { "now" },
        )
        assertEquals(listOf("a"), folders[0].sessions.map { it.sessionId })
        assertEquals(listOf("b"), folders[1].sessions.map { it.sessionId })
        assertEquals(true, folders[0].sessions.single().isRunning)
        assertEquals(true, folders[1].sessions.single().isActiveDot)
    }

    @Test
    fun `host order is applied even when host registered phone paths`() {
        val folders = buildWorkspaceFolders(
            dirs = listOf(dir("A"), dir("B")),
            root = root,
            sessions = listOf(session("s1", "/workspace/B"), session("s2", "/workspace/B")),
            hostWorkspaces = listOf(
                HostWorkspaceInfo(id = "w2", path = dir("B").absolutePath, title = "B", sessionIds = listOf("s2", "s1")),
                HostWorkspaceInfo(id = "w1", path = "/workspace/A", title = "A", sessionIds = emptyList()),
            ),
            selectedSessionId = null,
            runningIds = emptySet(),
            unreadIds = emptySet(),
            timeAgo = { "now" },
        )
        assertEquals(listOf("B", "A"), folders.map { it.name })
        assertEquals(listOf("s2", "s1"), folders[0].sessions.map { it.sessionId })
        assertEquals(true, folders[0].isExpanded)
        assertEquals("w1", listOf(
            HostWorkspaceInfo("w1", "/workspace/A", "A", emptyList()),
        ).forFolder(dir("A").absolutePath, root)?.id)
    }

    @Test
    fun `sessions without a known folder stay visible in the default folder`() {
        val folders = buildWorkspaceFolders(
            dirs = listOf(dir("App"), dir(DEFAULT_WORKSPACE_DIR_NAME)),
            root = root,
            sessions = listOf(session("old", null), session("gone", "/workspace/Deleted")),
            hostWorkspaces = emptyList(),
            selectedSessionId = null,
            runningIds = emptySet(),
            unreadIds = emptySet(),
            timeAgo = { "now" },
        )
        assertEquals(emptyList<String>(), folders.first { it.name == "App" }.sessions.map { it.sessionId })
        assertEquals(listOf("old", "gone"), folders.first { it.name == DEFAULT_WORKSPACE_DIR_NAME }.sessions.map { it.sessionId })
    }
}
