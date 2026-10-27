package com.dsh

import com.dsh.feature.files.ChangeKind
import com.dsh.feature.files.DiffRow
import com.dsh.feature.files.FilesGit
import com.dsh.feature.files.GitChange
import com.dsh.feature.files.GitInfo
import com.dsh.runtime.host.WorkspaceGit

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

/**
 * Системное «Поделиться» для файла рабочей папки через FileProvider.
 * @return false — не удалось (файл вне рабочей папки, путь не известен FileProvider, нет приложения): вызывающий
 *   говорит об этом, а не молчит (G-29).
 */
internal fun shareWorkspaceFile(context: android.content.Context, file: java.io.File): Boolean = runCatching {
    // Наружу — только файлы рабочей папки: ссылка из неё на личные файлы DSH (копии, журналы) не отдаётся.
    val root = StorageAccess.workspaceRoot(context).canonicalFile.toPath()
    if (!file.canonicalFile.toPath().startsWith(root)) return false
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
    true
}.getOrDefault(false)
