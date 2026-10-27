package com.dsh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** G-29: приватная рабочая папка во внутренней памяти тоже отдаётся через FileProvider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareWorkspaceFileTest {
    @Test
    fun internalWorkspaceFileHasAProviderUri() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = java.io.File(context.filesDir, "DSH-Workspace/Default/notes.md").apply { parentFile?.mkdirs(); writeText("x") }
        // Раньше: IllegalArgumentException «Failed to find configured root» — кнопка молча не работала.
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        assertTrue(uri.toString(), uri.toString().contains("workspace-internal"))
    }
}
