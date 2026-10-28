package com.dsh

import com.dsh.feature.files.ChangeKind
import com.dsh.feature.files.DiffRow
import com.dsh.feature.files.FilesGit
import com.dsh.feature.files.GitChange
import com.dsh.feature.files.GitInfo
import com.dsh.runtime.host.WorkspaceGit

/** Git проекта [project] для экрана «Файлы» поверх [WorkspaceGit]. */
internal class WorkspaceFilesGit(private val git: WorkspaceGit, private val project: String, private val dir: java.io.File) : FilesGit {

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

    override suspend fun identity(): Pair<String, String>? = git.identity(project)

    override suspend fun setIdentity(name: String, email: String): Boolean = git.setIdentity(project, name, email)

    override suspend fun commit(message: String): Result<String> = git.commit(project, message)

    override suspend fun unpushed(): Pair<Int, String>? = git.unpushed(project)?.let { it.count to it.lastSubject }

    override suspend fun pushTarget(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.dsh.runtime.host.GitHubPush.target(dir).getOrNull()?.let { "origin/${it.branch}" }
    }

    override suspend fun push(): Result<String> = GitHubAccount.push(dir).fold(
        onSuccess = { Result.success("origin/${it.branch}") },
        onFailure = { e ->
            val problem = when {
                e is GitHubAccount.NeedLoginException -> com.dsh.feature.files.PushProblem.NEED_LOGIN
                e is com.dsh.runtime.host.GitHubPush.PushException -> when (e.failure) {
                    com.dsh.runtime.host.GitHubPush.Failure.NotGitHub -> com.dsh.feature.files.PushProblem.NOT_GITHUB
                    com.dsh.runtime.host.GitHubPush.Failure.UnsafeConfig -> com.dsh.feature.files.PushProblem.UNSAFE_CONFIG
                    com.dsh.runtime.host.GitHubPush.Failure.Detached -> com.dsh.feature.files.PushProblem.DETACHED
                    com.dsh.runtime.host.GitHubPush.Failure.Rejected -> com.dsh.feature.files.PushProblem.REJECTED
                    com.dsh.runtime.host.GitHubPush.Failure.Auth -> com.dsh.feature.files.PushProblem.AUTH
                    is com.dsh.runtime.host.GitHubPush.Failure.Other -> com.dsh.feature.files.PushProblem.OTHER
                }
                else -> com.dsh.feature.files.PushProblem.OTHER
            }
            Result.failure(com.dsh.feature.files.PushException(problem, e.message))
        },
    )

    override suspend fun branchState(): com.dsh.feature.files.BranchState? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = com.dsh.runtime.host.GitHubPush.target(dir).getOrNull() ?: return@withContext null
        val info = com.dsh.runtime.host.GitHubPush.branchInfo(dir).getOrNull() ?: return@withContext null
        val base = baseOf(target, info)
        val pull = if (info.onRemote && info.branch != base) GitHubAccount.openPull(target.owner, target.repo, info.branch) else null
        com.dsh.feature.files.BranchState(info.branch, base, info.onRemote, pull?.let { com.dsh.feature.files.PullInfo(it.number, it.url, it.title, it.draft) })
    }

    /** База запроса: `origin/HEAD` после клонирования, иначе ответ GitHub, иначе main/master по имени ветки. */
    private suspend fun baseOf(target: com.dsh.runtime.host.GitHubPush.Target, info: com.dsh.runtime.host.GitHubPush.BranchInfo): String =
        info.localDefault ?: GitHubAccount.defaultBranch(target.owner, target.repo) ?: info.branch.takeIf { it == "main" || it == "master" } ?: "main"

    override suspend fun moveToBranch(name: String): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.dsh.runtime.host.GitHubPush.moveToNewBranch(dir, name).recoverCatching { e ->
            throw if (e.message == "branch exists") com.dsh.feature.files.BranchExistsException() else e
        }
    }

    override suspend fun branchCommits(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = com.dsh.runtime.host.GitHubPush.target(dir).getOrNull() ?: return@withContext emptyList()
        val info = com.dsh.runtime.host.GitHubPush.branchInfo(dir).getOrNull() ?: return@withContext emptyList()
        com.dsh.runtime.host.GitHubPush.commitsSince(dir, baseOf(target, info))
    }

    override suspend fun createPull(title: String, body: String, draft: Boolean): Result<com.dsh.feature.files.PullInfo> {
        val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.dsh.runtime.host.GitHubPush.target(dir).getOrNull() }
            ?: return Result.failure(com.dsh.feature.files.PullException(com.dsh.feature.files.PullProblem.NOT_GITHUB))
        val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.dsh.runtime.host.GitHubPush.branchInfo(dir).getOrNull() }
            ?: return Result.failure(com.dsh.feature.files.PullException(com.dsh.feature.files.PullProblem.OTHER))
        return GitHubAccount.createPull(target.owner, target.repo, info.branch, baseOf(target, info), title, body, draft).fold(
            onSuccess = { Result.success(com.dsh.feature.files.PullInfo(it.number, it.url, it.title, it.draft)) },
            onFailure = { e ->
                val problem = when {
                    e is GitHubAccount.NeedLoginException -> com.dsh.feature.files.PullProblem.NEED_LOGIN
                    e is com.dsh.runtime.host.GitHubPulls.PullException -> when (e.failure) {
                        com.dsh.runtime.host.GitHubPulls.Failure.Auth -> com.dsh.feature.files.PullProblem.AUTH
                        com.dsh.runtime.host.GitHubPulls.Failure.NoAccess -> com.dsh.feature.files.PullProblem.NO_ACCESS
                        com.dsh.runtime.host.GitHubPulls.Failure.Exists -> com.dsh.feature.files.PullProblem.EXISTS
                        com.dsh.runtime.host.GitHubPulls.Failure.NoCommits -> com.dsh.feature.files.PullProblem.NO_COMMITS
                        com.dsh.runtime.host.GitHubPulls.Failure.DraftUnsupported -> com.dsh.feature.files.PullProblem.DRAFT_UNSUPPORTED
                        is com.dsh.runtime.host.GitHubPulls.Failure.Other -> com.dsh.feature.files.PullProblem.OTHER
                    }
                    else -> com.dsh.feature.files.PullProblem.OTHER
                }
                Result.failure(com.dsh.feature.files.PullException(problem, (e as? com.dsh.runtime.host.GitHubPulls.PullException)?.failure?.let { (it as? com.dsh.runtime.host.GitHubPulls.Failure.Other)?.message } ?: e.message))
            },
        )
    }

    override suspend fun log(skip: Int, limit: Int): List<com.dsh.feature.files.CommitInfo>? = git.log(project, skip, limit)?.map {
        com.dsh.feature.files.CommitInfo(it.hash, it.short, it.author, it.time, it.subject, it.body, it.pushed)
    }

    override suspend fun commitFiles(hash: String): List<com.dsh.feature.files.CommitFile>? = git.commitFiles(project, hash)?.map {
        com.dsh.feature.files.CommitFile(it.path, it.oldPath, it.kind.toUi(), it.added, it.deleted)
    }

    override suspend fun commitDiff(hash: String, file: com.dsh.feature.files.CommitFile): List<DiffRow> =
        git.commitDiff(project, hash, WorkspaceGit.CommitFile(file.path, file.oldPath, file.kind.toHost(), file.added, file.deleted)).map {
            when (it) {
                is WorkspaceGit.DiffLine.Hunk -> DiffRow.Hunk(it.text)
                is WorkspaceGit.DiffLine.Line -> DiffRow.Line(it.sign, it.number, it.text)
            }
        }

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
