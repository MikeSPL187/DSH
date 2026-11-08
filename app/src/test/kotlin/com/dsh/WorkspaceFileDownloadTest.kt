package com.dsh

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files

/** Exercise real ContentResolver streams, including binary data and denied destinations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class WorkspaceFileDownloadTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var destination: File
    private lateinit var provider: DestinationProvider
    private val uri = Uri.parse("content://qa.download/document/saved")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric's WebKit MIME registry starts empty; Android supplies this mapping.
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("txt", "text/plain")
        StorageAccess.choosePrivateWorkspace(context)
        root = StorageAccess.workspaceRoot(context).apply { mkdirs() }
        destination = File(context.cacheDir, "saved-download").apply { writeText("previous content") }
        provider = DestinationProvider(destination).apply {
            attachInfo(context, ProviderInfo().apply { authority = "qa.download" })
        }
        ShadowContentResolver.registerProviderInternal("qa.download", provider)
    }

    @Test fun pickerPreservesTheOriginalNameAndMimeType() {
        val text = workspaceFileDownloadIntent(File(root, "обзор архитектуры.TXT"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, text.action)
        assertTrue(text.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("обзор архитектуры.TXT", text.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("text/plain", text.type)
        assertEquals("application/octet-stream", workspaceFileDownloadIntent(File(root, "report.dshartifact")).type)
    }

    @Test fun binaryAndEmptyFilesAreCopiedExactlyWithoutChangingTheSource() {
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val source = File(root, "binary.dat").apply { writeBytes(bytes) }
        assertTrue(saveWorkspaceFile(context, source, uri).isSuccess)
        assertArrayEquals(bytes, destination.readBytes())
        assertArrayEquals(bytes, source.readBytes())
        val empty = File(root, "empty.txt").apply { writeBytes(byteArrayOf()) }
        assertTrue(saveWorkspaceFile(context, empty, uri).isSuccess)
        assertEquals(0L, destination.length())
        assertEquals("wt", provider.lastMode)
    }

    @Test fun permissionFailureIsReportedAndTheSourceSurvives() {
        val source = File(root, "notes.txt").apply { writeText("retain me") }
        provider.deny = true
        assertTrue(saveWorkspaceFile(context, source, uri).isFailure)
        assertEquals("retain me", source.readText())
        assertEquals("previous content", destination.readText())
    }

    @Test fun invalidOrPrivateSourcesNeverOpenOrTruncateTheDestination() {
        val privateFile = File(context.filesDir, "private-fixture.txt").apply { writeText("private fixture") }
        val neighbor = File(root.parentFile, root.name + "-other").apply { mkdirs() }
        val outside = File(neighbor, "outside.txt").apply { writeText("outside") }
        val link = File(root, "private-link.txt")
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), privateFile.toPath())
        for (source in listOf(privateFile, outside, link, root, File(root, "missing.txt"))) {
            assertTrue(source.name, saveWorkspaceFile(context, source, uri).isFailure)
        }
        assertEquals(0, provider.opens)
        assertEquals("previous content", destination.readText())
    }

    private class DestinationProvider(private val file: File) : ContentProvider() {
        var deny = false
        var opens = 0
        var lastMode: String? = null
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            if (deny) throw SecurityException("Destination permission denied")
            lastMode = mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
