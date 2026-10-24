package com.deepseek.dsh

import com.deepseek.dsh.feature.files.ChangeKind
import com.deepseek.dsh.feature.files.DiffRow
import com.deepseek.dsh.feature.files.FilesGit
import com.deepseek.dsh.feature.files.GitChange
import com.deepseek.dsh.feature.files.GitInfo
import com.deepseek.dsh.runtime.host.WorkspaceGit

/** Git проекта [project] для экрана «Файлы» поверх [WorkspaceGit]. */
internal class WorkspaceFilesGit(private val git: WorkspaceGit, private val project: String) : FilesGit {

    override suspend fun status(): GitInfo? = git.status(project)?.let { s ->
        GitInfo(s.branch, s.changes.map { GitChange(it.path, it.kind.toUi(), it.added, it.deleted) })
    }

    override suspend fun diff(path: String): List<DiffRow> = git.diff(project, path).map {
        when (it) {
            is WorkspaceGit.DiffLine.Hunk -> DiffRow.Hunk(it.text)
            is WorkspaceGit.DiffLine.Line -> DiffRow.Line(it.sign, it.number, it.text)
        }
    }

    override suspend fun revert(change: GitChange): Boolean =
        git.revert(project, WorkspaceGit.Change(change.path, change.kind.toHost(), change.added, change.deleted))

    override suspend fun clone(url: String, intoDir: String): Result<Unit> = git.clone(project, url, intoDir)

    private fun WorkspaceGit.Kind.toUi() = when (this) {
        WorkspaceGit.Kind.MODIFIED -> ChangeKind.MODIFIED
        WorkspaceGit.Kind.ADDED -> ChangeKind.ADDED
        WorkspaceGit.Kind.DELETED -> ChangeKind.DELETED
        WorkspaceGit.Kind.RENAMED -> ChangeKind.RENAMED
    }

    private fun ChangeKind.toHost() = when (this) {
        ChangeKind.MODIFIED -> WorkspaceGit.Kind.MODIFIED
        ChangeKind.ADDED -> WorkspaceGit.Kind.ADDED
        ChangeKind.DELETED -> WorkspaceGit.Kind.DELETED
        ChangeKind.RENAMED -> WorkspaceGit.Kind.RENAMED
    }
}

/** Системное «Поделиться» для файла рабочей папки через FileProvider. */
internal fun shareWorkspaceFile(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val type = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(type)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            android.content.Intent.createChooser(send, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
