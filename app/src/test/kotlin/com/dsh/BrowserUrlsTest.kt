package com.dsh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserUrlsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `input becomes an address under the http policy`() {
        assertEquals("http://localhost:5173", BrowserUrls.normalize("localhost:5173"))
        assertEquals("http://127.0.0.1:8080/api", BrowserUrls.normalize("127.0.0.1:8080/api"))
        assertEquals("https://developer.android.com", BrowserUrls.normalize("developer.android.com"))
        assertEquals("https://example.com/a", BrowserUrls.normalize("https://example.com/a"))
        assertNull(BrowserUrls.normalize("http://example.com"))
        assertNull(BrowserUrls.normalize("file:///data/data/app.dsh.mobile/files/key"))
        assertNull(BrowserUrls.normalize("javascript:alert(1)"))
        assertNull(BrowserUrls.normalize("intent://scan#Intent;end"))
        assertNull(BrowserUrls.normalize("two words"))
        assertNull(BrowserUrls.normalize(""))
    }

    @Test
    fun `navigation allows https and loopback http only`() {
        assertTrue(BrowserUrls.allowed("https://example.com"))
        assertTrue(BrowserUrls.allowed("http://localhost:3000/x"))
        assertTrue(BrowserUrls.allowed("http://[::1]:3000"))
        assertFalse(BrowserUrls.allowed("http://192.168.1.5:3000"))
        assertFalse(BrowserUrls.allowed("content://media/1"))
        assertFalse(BrowserUrls.allowed("market://details?id=x"))
    }

    @Test
    fun `workspace files are served only from inside the workspace`() {
        val root = tmp.newFolder("ws")
        File(root, "web/assets").mkdirs()
        File(root, "web/index.html").writeText("<h1>ok</h1>")
        File(root, "web/my page.html").writeText("x")
        val secret = tmp.newFile("secret.txt")
        assertEquals("https://dsh.workspace/web/my%20page.html", BrowserUrls.workspaceUrl(root, File(root, "web/my page.html")))
        assertNull(BrowserUrls.workspaceUrl(root, secret))
        assertEquals("index.html", BrowserUrls.resolveWorkspace(root, "/web/")?.name)
        assertEquals("my page.html", BrowserUrls.resolveWorkspace(root, "/web/my%20page.html")?.name)
        assertNull(BrowserUrls.resolveWorkspace(root, "/../secret.txt"))
        assertNull(BrowserUrls.resolveWorkspace(root, "/web/%2E%2E/%2E%2E/secret.txt"))
        assertNull(BrowserUrls.resolveWorkspace(root, "/web/assets"))
        assertNull(BrowserUrls.resolveWorkspace(root, "/missing.js"))
        assertEquals("text/javascript", BrowserUrls.mime("app.mjs"))
        assertEquals("application/octet-stream", BrowserUrls.mime("data.bin"))
    }

    @Test
    fun `directory index link cannot expose a file outside the workspace`() {
        // DSH-15: index.html папки — ссылка на файл вне рабочей папки.
        val root = tmp.newFolder("ws-link")
        val site = File(root, "site").apply { mkdirs() }
        val private = tmp.newFile("private.txt").apply { writeText("QA SYNTHETIC PRIVATE FILE") }
        java.nio.file.Files.createSymbolicLink(File(site, "index.html").toPath(), private.toPath())
        assertNull(BrowserUrls.resolveWorkspace(root, "/site/"))
        assertNull(BrowserUrls.resolveWorkspace(root, "/site/index.html"))
        // Ссылка внутри рабочей папки остаётся рабочей.
        val docs = File(root, "docs").apply { mkdirs() }
        File(docs, "page.html").writeText("<p>ok</p>")
        java.nio.file.Files.createSymbolicLink(File(docs, "index.html").toPath(), File(docs, "page.html").toPath())
        assertEquals("page.html", BrowserUrls.resolveWorkspace(root, "/docs/")?.name)
    }

    @Test
    fun `output links cover only addresses the browser may open`() {
        var opened = ""
        val text = AnnotatedString("  ➜  Local:   http://localhost:5173/, docs https://vitejs.dev. LAN http://192.168.1.5:5173/")
        val linked = text.withLinks(Color.Blue) { opened = it }
        val links = linked.getLinkAnnotations(0, linked.length).map { (it.item as LinkAnnotation.Clickable).tag }
        assertEquals(listOf("http://localhost:5173/", "https://vitejs.dev"), links)
        assertEquals(text.text, linked.text)
        assertEquals(text, text.withLinks(Color.Blue, null))
        assertEquals("", opened)
    }
}
