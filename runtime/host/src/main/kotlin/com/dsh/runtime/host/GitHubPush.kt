package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Отправка коммитов на GitHub из процесса приложения (JGit), а не git внутри Linux: так токен не попадает ни в
 * аргументы, ни в окружение, ни в файлы, которые видят процессы агента (`/proc/<pid>/environ` того же uid).
 *
 * Токен уходит только на `https://github.com/<владелец>/<репозиторий>`: адрес `origin` проверяется, отправка идёт
 * на проверенный адрес явно, а конфиг репозитория с секциями, которые меняют адрес или TLS (`url.*.insteadOf`,
 * `http.*`, `credential.*`, `include`, `core.hooksPath`), отклоняется — их мог подложить кто угодно, кто писал в рабочую папку.
 * Хуки (`pre-push`) не запускаются: JGit выполнил бы их в процессе приложения, вне песочницы Linux.
 */
object GitHubPush {
    /** Куда отправлять: проверенный адрес, ветка и сколько коммитов уйдёт. */
    data class Target(val url: String, val owner: String, val repo: String, val branch: String)

    sealed interface Failure {
        /** Нет `origin` на GitHub по https (ssh, другой хостинг, нет удалённого). */
        data object NotGitHub : Failure
        /** В конфиге репозитория опасные секции — отправка не начинается. */
        data object UnsafeConfig : Failure
        /** HEAD не на ветке. */
        data object Detached : Failure
        /** GitHub отклонил: история разошлась (сначала забрать изменения). */
        data object Rejected : Failure
        /** Токен не подошёл или нет прав на репозиторий. */
        data object Auth : Failure
        data class Other(val message: String) : Failure
    }

    class PushException(val failure: Failure) : Exception(failure.toString())

    private val GITHUB = Regex("""^https://github\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\.git)?/?$""")
    private val UNSAFE = Regex("""(?im)^\s*\[\s*(url|http|credential|include|includeif)\b|^\s*hookspath\s*=""")

    /** `(владелец, репозиторий)` для адреса GitHub по https; иначе null (ssh, логин в адресе, другой хост). */
    fun parseGitHubUrl(url: String): Pair<String, String>? = GITHUB.matchEntire(url.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    /** Конфиг репозитория без секций, способных перенаправить запрос или ослабить TLS. */
    fun configSafe(configText: String): Boolean = !UNSAFE.containsMatchIn(configText)

    /**
     * JGit читает только конфиг самого репозитория: пользовательский, системный и собственный конфиги JGit пустые и
     * живут в памяти. Иначе чужой `~/.gitconfig` или `/etc/gitconfig` мог бы перенаправить запросы, а неизвестные
     * JGit значения (например, `gpg.format=ssh`) — сорвать отправку.
     */
    @Synchronized
    fun isolate() {
        val current = SystemReader.getInstance()
        if (current is Isolated) return
        SystemReader.setInstance(Isolated(current))
    }

    private class Isolated(private val base: SystemReader) : SystemReader() {
        private fun empty(parent: org.eclipse.jgit.lib.Config?, fs: org.eclipse.jgit.util.FS) = object : FileBasedConfig(parent, null, fs) {
            override fun load() = Unit
            override fun save() = Unit
            override fun isOutdated(): Boolean = false
        }
        override fun getHostname(): String = base.hostname
        override fun getenv(variable: String): String? = base.getenv(variable)
        override fun getProperty(key: String): String? = base.getProperty(key)
        override fun openUserConfig(parent: org.eclipse.jgit.lib.Config?, fs: org.eclipse.jgit.util.FS): FileBasedConfig = empty(parent, fs)
        override fun openSystemConfig(parent: org.eclipse.jgit.lib.Config?, fs: org.eclipse.jgit.util.FS): FileBasedConfig = empty(parent, fs)
        override fun openJGitConfig(parent: org.eclipse.jgit.lib.Config?, fs: org.eclipse.jgit.util.FS): FileBasedConfig = empty(parent, fs)
        override fun getCurrentTime(): Long = base.currentTime
        override fun getTimezone(`when`: Long): Int = base.getTimezone(`when`)
    }

    /** Цель отправки для проекта [dir]; null — отправка невозможна, причина — в [Failure]. */
    fun target(dir: File): Result<Target> = runCatching {
        isolate()
        val dotGit = File(dir, ".git")
        if (!dotGit.isDirectory) throw PushException(Failure.NotGitHub)
        if (!configSafe(File(dotGit, "config").readText())) throw PushException(Failure.UnsafeConfig)
        Git.open(dir).use { git ->
            val repo = git.repository
            val url = repo.config.getString("remote", "origin", "url") ?: throw PushException(Failure.NotGitHub)
            val (owner, name) = parseGitHubUrl(url) ?: throw PushException(Failure.NotGitHub)
            val full = repo.fullBranch ?: throw PushException(Failure.Detached)
            if (!full.startsWith(Constants.R_HEADS)) throw PushException(Failure.Detached)
            Target("https://github.com/$owner/$name.git", owner, name, full.removePrefix(Constants.R_HEADS))
        }
    }

    /** Имя ветки, допустимое для git (и не похожее на ключ командной строки). */
    fun validBranch(name: String): Boolean =
        name.isNotEmpty() && name.length <= 100 && !name.startsWith("-") && Repository.isValidRefName(Constants.R_HEADS + name)

    /** Состояние ветки для запроса на слияние: имя, есть ли она на GitHub (`origin/<ветка>`), ветка по умолчанию локально. */
    data class BranchInfo(val branch: String, val onRemote: Boolean, val localDefault: String?)

    fun branchInfo(dir: File): Result<BranchInfo> = runCatching {
        isolate()
        Git.open(dir).use { git ->
            val repo = git.repository
            val full = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) } ?: throw PushException(Failure.Detached)
            val branch = full.removePrefix(Constants.R_HEADS)
            // `origin/HEAD` ставит git clone; без него ветка по умолчанию неизвестна (её скажет GitHub).
            val originHead = repo.exactRef("refs/remotes/origin/HEAD")?.target?.name
                ?.takeIf { it.startsWith("refs/remotes/origin/") }?.removePrefix("refs/remotes/origin/")
            BranchInfo(branch, repo.exactRef("refs/remotes/origin/$branch") != null, originHead)
        }
    }

    /**
     * Перенести неотправленные коммиты в новую ветку [name]: она создаётся на HEAD, HEAD переходит на неё, а прежняя
     * ветка возвращается к своей версии на GitHub (`origin/<ветка>`), если та — предок HEAD. Файлы и индекс не
     * меняются: коммит тот же, переключается только ссылка.
     */
    fun moveToNewBranch(dir: File, name: String): Result<Unit> = runCatching {
        isolate()
        require(validBranch(name)) { "bad branch name" }
        Git.open(dir).use { git ->
            val repo = git.repository
            val full = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) } ?: throw PushException(Failure.Detached)
            val old = full.removePrefix(Constants.R_HEADS)
            check(repo.exactRef(Constants.R_HEADS + name) == null) { "branch exists" }
            val head = repo.resolve(Constants.HEAD) ?: error("no commits")
            val created = repo.updateRef(Constants.R_HEADS + name).run {
                setNewObjectId(head); setExpectedOldObjectId(ObjectId.zeroId()); setRefLogMessage("branch: created by DSH from $old", false); update()
            }
            check(created == RefUpdate.Result.NEW) { "cannot create branch: $created" }
            val linked = repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + name)
            check(linked == RefUpdate.Result.FORCED || linked == RefUpdate.Result.NEW || linked == RefUpdate.Result.NO_CHANGE) { "cannot switch: $linked" }
            val upstream = repo.resolve("refs/remotes/origin/$old") ?: return@use
            val merged = RevWalk(repo).use { w -> w.isMergedInto(w.parseCommit(upstream), w.parseCommit(head)) }
            if (merged) repo.updateRef(Constants.R_HEADS + old).run {
                setNewObjectId(upstream); setExpectedOldObjectId(head); setRefLogMessage("reset: commits moved to $name by DSH", false); forceUpdate()
            }
        }
    }

    /** Сообщения коммитов ветки, которых нет в `origin/<base>` (новые первыми, не больше [limit]). */
    fun commitsSince(dir: File, base: String, limit: Int = 30): List<String> = runCatching {
        isolate()
        Git.open(dir).use { git ->
            val repo = git.repository
            val head = repo.resolve(Constants.HEAD) ?: return@use emptyList()
            RevWalk(repo).use { w ->
                w.markStart(w.parseCommit(head))
                repo.resolve("refs/remotes/origin/$base")?.let { w.markUninteresting(w.parseCommit(it)) }
                w.take(limit).map { it.fullMessage.trim() }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Отправить текущую ветку в `origin` той же ветки. Успех — цель; затем локальная `refs/remotes/origin/<ветка>`
     * передвигается на отправленный коммит (как у обычного `git push`), а у ветки без upstream он появляется.
     */
    suspend fun push(dir: File, token: String): Result<Target> = withContext(Dispatchers.IO) {
        runCatching {
            val target = target(dir).getOrThrow()
            val ref = "refs/heads/${target.branch}"
            Git.open(dir).use { git ->
                // Только в памяти, на диск не пишется: хуки ищутся в несуществующей папке и не запускаются.
                git.repository.config.setString("core", null, "hooksPath", File(dir, ".git/dsh-no-hooks-${System.nanoTime()}").absolutePath)
                val results = try {
                    git.push()
                        .setRemote(target.url)
                        .setRefSpecs(RefSpec("$ref:$ref"))
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", token))
                        .setTimeout(60)
                        .call()
                } catch (e: org.eclipse.jgit.api.errors.TransportException) {
                    val m = e.message.orEmpty()
                    throw PushException(if (m.contains("not authorized", true) || m.contains("401") || m.contains("403")) Failure.Auth else Failure.Other(m))
                }
                val update = results.flatMap { it.remoteUpdates }.firstOrNull()
                when (update?.status) {
                    RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> throw PushException(Failure.Rejected)
                    null -> throw PushException(Failure.Other("no result"))
                    else -> throw PushException(Failure.Other(update.message ?: update.status.name))
                }
            }
            // Заново, с конфигом с диска: подмена hooksPath выше не должна попасть в сохранённый конфиг.
            Git.open(dir).use { git ->
                val repo = git.repository
                val head = repo.resolve(ref)
                if (head != null) repo.updateRef("refs/remotes/origin/${target.branch}").apply { setNewObjectId(head); forceUpdate() }
                val cfg = repo.config
                if (cfg.getString("branch", target.branch, "remote") == null) {
                    cfg.setString("branch", target.branch, "remote", "origin")
                    cfg.setString("branch", target.branch, "merge", ref)
                    cfg.save()
                }
            }
            target
        }
    }
}
