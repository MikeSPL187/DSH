package com.deepseek.dsh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Аудит F-07: без доступа ко всем файлам можно работать в приватной папке приложения. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StorageAccessTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun realChecks() {
        StorageAccess.overrideForTests = null
    }

    @After
    fun reset() {
        context.getSharedPreferences("dsh_storage", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sharedFolderIsTheDefault() {
        assertFalse(StorageAccess.usesPrivateWorkspace(context))
        assertTrue(StorageAccess.workspaceRoot(context).path.endsWith("Documents/DSH-Workspace"))
    }

    @Test
    fun privateWorkspaceNeedsNoAllFilesAccess() {
        StorageAccess.choosePrivateWorkspace(context)
        assertTrue(StorageAccess.hasAccess(context))
        val root = StorageAccess.workspaceRoot(context)
        assertEquals("DSH-Workspace", root.name)
        assertTrue(root.path.startsWith((context.getExternalFilesDir(null) ?: context.filesDir).path))
    }
}
