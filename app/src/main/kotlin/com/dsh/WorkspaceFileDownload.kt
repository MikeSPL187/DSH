package com.dsh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** SAF works on Android 8+ without asking for broad storage permissions. */
internal fun workspaceFileDownloadIntent(file: File): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
        ?: "application/octet-stream"
    putExtra(Intent.EXTRA_TITLE, file.name)
}

private fun downloadableWorkspaceFile(context: Context, file: File): File {
    val canonical = file.canonicalFile
    require(canonical.toPath().startsWith(StorageAccess.workspaceRoot(context).canonicalFile.toPath()) && canonical.isFile) {
        "Not a workspace file"
    }
    return canonical
}

/** Open the source before the destination; a missing/invalid source must not truncate output. */
internal fun saveWorkspaceFile(context: Context, file: File, destination: Uri): Result<Unit> = runCatching {
    downloadableWorkspaceFile(context, file).inputStream().use { input ->
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Destination unavailable")
        output.use { input.copyTo(it) }
    }
    Unit
}

@Composable
internal fun rememberWorkspaceFileDownload(): (File) -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext as DshApplication
    // The document picker can outlive the Activity; keep the selected source with its result.
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingSource?.let(::File)
        pendingSource = null
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            val destination = result.data?.data
            val saved = context.getString(com.dsh.feature.files.R.string.fs_download_saved, source.name)
            val failed = context.getString(com.dsh.feature.files.R.string.fs_download_failed, source.name)
            app.applicationScope.launch {
                val ok = destination != null && withContext(Dispatchers.IO) { saveWorkspaceFile(app, source, destination).isSuccess }
                Toast.makeText(app, if (ok) saved else failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    return { file ->
        if (pendingSource == null) {
            runCatching {
                downloadableWorkspaceFile(context, file)
                pendingSource = file.absolutePath
                launcher.launch(workspaceFileDownloadIntent(file))
            }.onFailure {
                pendingSource = null
                Toast.makeText(context, context.getString(com.dsh.feature.files.R.string.fs_download_failed, file.name), Toast.LENGTH_LONG).show()
            }
        }
    }
}
