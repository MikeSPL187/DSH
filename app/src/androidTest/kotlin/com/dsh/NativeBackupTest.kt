package com.dsh

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.runtime.host.RuntimeBackupAndDiagnostics
import com.dsh.runtime.host.RuntimeLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** Runs in the test APK's independent process/UID, not the target instrumentation process. */
class QaArchiveReceiver : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val passed = runCatching {
            @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            check(uri.scheme == "content")
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            ZipInputStream(contentResolver.openInputStream(uri)!!).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "dsh_home/qa.txt") found = zip.readBytes().decodeToString() == "backup-fixture"
                }
                check(found)
            }
        }.isSuccess
        setContentView(TextView(this).apply { text = if (passed) "QA archive read PASS" else "QA archive read FAIL"; textSize = 20f })
    }
}

class NativeBackupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun restoreCancelCorruptArchiveAndRealShareReceiver() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "qa-backup-fixture").apply { deleteRecursively(); mkdirs() }
        try {
            val layout = RuntimeLayout(root, File(root, "lib")).also { it.ensureDirectories() }
            val sample = File(layout.dshHomeDir, "qa.txt").apply { writeText("backup-fixture") }
            val backup = RuntimeBackupAndDiagnostics(layout)
            val zip = backup.createUserDataBackup().getOrThrow()
            sample.writeText("current")
            val cancelled = backup.prepareRestore(zip.inputStream()).getOrThrow()
            backup.discardRestore(cancelled)
            assertEquals("current", sample.readText())
            assertTrue(backup.prepareRestore("not a zip".byteInputStream()).isFailure)
            assertEquals("current", sample.readText())
            val plan = backup.prepareRestore(zip.inputStream()).getOrThrow()
            backup.applyRestore(plan).getOrThrow()
            assertEquals("backup-fixture", sample.readText())
            val shared = File(context.filesDir, "backups/qa-export.zip").apply { parentFile!!.mkdirs() }
            zip.copyTo(shared, overwrite = true)
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                val all = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(n: android.view.accessibility.AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
                return all
            }
            for (title in listOf("Share backup", "Поделиться копией")) {
                compose.runOnUiThread { shareArchive(compose.activity, Result.success(shared), title, "QA export failed") }
                compose.waitUntil(30_000) { nodes().any { it.text?.toString() == "DSH QA archive" } }
                val node = nodes().first { it.text?.toString() == "DSH QA archive" }
                var action: android.view.accessibility.AccessibilityNodeInfo? = node
                while (action != null && !action.isClickable) action = action.parent
                assertTrue(action?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
                compose.waitUntil(30_000) { nodes().any { it.text?.toString()?.startsWith("QA archive read ") == true } }
                assertTrue(nodes().any { it.text?.toString() == "QA archive read PASS" })
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
            shared.delete()
        } finally { root.deleteRecursively() }
    }
}
