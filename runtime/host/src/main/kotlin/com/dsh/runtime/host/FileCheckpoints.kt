package com.dsh.runtime.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import java.io.File
import java.security.MessageDigest

/**
 * Точки возврата файлов проекта: перед каждым сообщением агенту приложение снимает папку проекта в отдельное
 * хранилище git в своих личных файлах (как «checkpoints» Claude Code). В сам проект ничего не пишется — ни `.git`,
 * ни ссылок; его собственный git не меняется.
 *
 * Снимаются файлы до [MAX_FILE_BYTES], не больше [MAX_FILES]; `.gitignore` проекта соблюдается, тяжёлые
 * пересобираемые папки ([SKIP_DIRS]) пропускаются — их возврат не трогает. Хранятся последние [KEEP] точек.
 *
 * @param project папка проекта (рабочая папка сессии).
 * @param storeRoot где лежат хранилища точек (по одному на проект).
 */
class FileCheckpoints(private val project: File, storeRoot: File) {

    /** Точка: id коммита, сессия, когда снята, хэш текста сообщения, перед которым снята. */
    @Serializable
    data class Point(val id: String, val session: String, val at: Long, val textHash: Int, val files: Int)

    /** Что сделает возврат к точке: изменит файлы, вернёт удалённые после неё, удалит появившиеся после неё. */
    data class Preview(val modified: List<String>, val restored: List<String>, val removed: List<String>) {
        val total: Int get() = modified.size + restored.size + removed.size
    }

    private val dir = File(storeRoot, sha1(project.canonicalPath).take(16))
    private val gitDir = File(dir, "store.git")
    private val indexFile = File(dir, "points.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun repo(): FileRepository {
        GitHubPush.isolate()
        val fresh = !gitDir.isDirectory
        val r = FileRepository(org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(project).setup())
        if (fresh) {
            r.create(false)
            // Хранилище — служебное: без хуков и автосборки мусора в чужое время.
            // Быстрое сжатие: первый снимок большой папки на телефоне иначе идёт десятки секунд.
            r.config.apply {
                setBoolean("core", null, "logallrefupdates", false); setInt("gc", null, "auto", 0)
                setInt("core", null, "compression", 1); setInt("core", null, "loosecompression", 1); save()
            }
        }
        return r
    }

    fun points(): List<Point> = runCatching { json.decodeFromString<List<Point>>(indexFile.readText()) }.getOrDefault(emptyList())

    /**
     * Снять точку перед сообщением [text] сессии [session]. null — не снята (папки нет, слишком много файлов,
     * ошибка): отправка от этого не зависит.
     */
    @Synchronized
    fun snapshot(session: String, text: String, now: Long = System.currentTimeMillis()): Point? = runCatching {
        if (!project.isDirectory) return null
        repo().use { r ->
            val (tree, files) = writeTree(r) ?: return null
            val commit = r.newObjectInserter().use { ins ->
                val who = PersonIdent("DSH", "dsh@localhost", java.util.Date(now), java.util.TimeZone.getTimeZone("UTC"))
                ins.insert(CommitBuilder().apply { setTreeId(tree); author = who; committer = who; message = "checkpoint $session" }).also { ins.flush() }
            }
            r.updateRef("refs/dsh/cp/${commit.name}").apply { setNewObjectId(commit) }.forceUpdate()
            val point = Point(commit.name, session, now, text.trim().hashCode(), files)
            save(prune(r, points() + point))
            point
        }
    }.getOrNull()

    /**
     * Точка, снятая перед сообщением [text] сессии [session], отправленным около [sentAt]; null — такой нет.
     * Только по тексту: точка соседнего сообщения вернула бы файлы не туда — лучше честное «нет точки».
     */
    fun pointFor(session: String, text: String, sentAt: Long): Point? {
        val hash = text.trim().hashCode()
        return points().filter { it.session == session && it.textHash == hash && kotlin.math.abs(it.at - sentAt) < MATCH_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.at - sentAt) }
    }

    /** Обновить кэш снимков без точки: следующий снимок перед сообщением будет быстрым. */
    @Synchronized
    fun warm() { runCatching { if (project.isDirectory) repo().use { writeTree(it) } } }

    /** Что изменится, если вернуть файлы к [point]. */
    @Synchronized
    fun preview(point: Point): Preview? = runCatching {
        repo().use { r ->
            val (current, _) = writeTree(r) ?: return null
            diff(r, current, r.parseCommit(ObjectId.fromString(point.id)).tree)
        }
    }.getOrNull()

    /**
     * Вернуть файлы к [point]. Перед этим снимается точка «как было» — её id возвращается для отмены
     * ([restore] к ней же). Файлы вне снимков (игнорируемые, большие, в [SKIP_DIRS]) не трогаются.
     */
    @Synchronized
    fun restore(point: Point, session: String): Result<Pair<Preview, Point>> = runCatching {
        val before = snapshot(session, UNDO_TEXT) ?: error("cannot snapshot current files")
        repo().use { r ->
            val current = r.parseCommit(ObjectId.fromString(before.id)).tree
            val target = r.parseCommit(ObjectId.fromString(point.id)).tree
            val change = diff(r, current, target)
            TreeWalk(r).use { tw ->
                tw.addTree(target); tw.isRecursive = true
                val want = (change.modified + change.restored).toSet()
                while (tw.next()) {
                    if (tw.pathString !in want) continue
                    val out = safeFile(tw.pathString)
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { r.open(tw.getObjectId(0)).copyTo(it) }
                    if (tw.getFileMode(0) == FileMode.EXECUTABLE_FILE) out.setExecutable(true)
                }
            }
            change.removed.forEach { safeFile(it).delete() }
            change to before
        }
    }

    /** Файл проекта по пути из снимка; путь вне проекта (`..`, ссылки) — ошибка. */
    private fun safeFile(path: String): File {
        val f = File(project, path)
        require(f.canonicalPath.startsWith(project.canonicalPath + File.separator)) { "path outside project" }
        return f
    }

    private fun diff(r: FileRepository, from: ObjectId, to: ObjectId): Preview {
        val modified = ArrayList<String>(); val restored = ArrayList<String>(); val removed = ArrayList<String>()
        TreeWalk(r).use { tw ->
            tw.addTree(from); tw.addTree(to); tw.isRecursive = true
            tw.filter = org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF
            while (tw.next()) {
                val inFrom = !tw.getObjectId(0).equals(ObjectId.zeroId())
                val inTo = !tw.getObjectId(1).equals(ObjectId.zeroId())
                when {
                    inFrom && inTo -> modified += tw.pathString
                    inTo -> restored += tw.pathString
                    else -> removed += tw.pathString
                }
            }
        }
        return Preview(modified, restored, removed)
    }

    private fun org.eclipse.jgit.lib.Repository.parseCommit(id: ObjectId) = org.eclipse.jgit.revwalk.RevWalk(this).use { it.parseCommit(id) }

    /**
     * Дерево текущих файлов проекта. Индекс хранилища — кэш: файл с тем же размером и временем изменения не
     * читается заново. null — файлов больше [MAX_FILES].
     */
    private fun writeTree(r: FileRepository): Pair<ObjectId, Int>? {
        val cache = DirCache.lock(r.indexFile, r.fs)
        try {
            val old = HashMap<String, DirCacheEntry>()
            for (i in 0 until cache.entryCount) cache.getEntry(i).let { old[it.pathString] = it }
            val builder = cache.builder()
            var files = 0
            // Один pack на снимок вместо тысяч мелких файлов-объектов: первый снимок большой папки в разы быстрее.
            r.objectDatabase.newPackInserter().apply { checkExisting(true) }.use { ins ->
                TreeWalk(r).use { tw ->
                    // Вложенные репозитории (клоны в подпапках) — обычные папки: правки агента в них тоже снимаются.
                    val it = FileTreeIterator(r, FileTreeIterator.NoGitlinksStrategy.INSTANCE)
                    tw.addTree(it)
                    tw.isRecursive = false
                    while (tw.next()) {
                        val wt = tw.getTree(0, WorkingTreeIterator::class.java)
                        val name = tw.nameString
                        if (wt.isEntryIgnored || (tw.isSubtree && name in SKIP_DIRS)) continue
                        if (tw.isSubtree) { tw.enterSubtree(); continue }
                        val mode = wt.entryFileMode
                        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                        val len = wt.entryLength
                        if (len > MAX_FILE_BYTES) continue
                        if (++files > MAX_FILES) return null
                        val path = tw.pathString
                        val prev = old[path]
                        val modified = wt.entryLastModifiedInstant
                        val id = if (prev != null && prev.length.toLong() == len && prev.lastModifiedInstant == modified) prev.objectId
                        else wt.openEntryStream().use { s -> ins.insert(Constants.OBJ_BLOB, len, s) }
                        builder.add(DirCacheEntry(path).apply {
                            fileMode = mode; setLength(len); setLastModified(modified); setObjectId(id)
                        })
                    }
                }
                builder.finish()
                val tree = cache.writeTree(ins)
                ins.flush()
                cache.write(); cache.commit()
                return tree to files
            }
        } finally {
            cache.unlock()
        }
    }

    /** Оставить последние [KEEP] точек; ссылки на остальные удаляются, мусор собирается раз в [GC_EVERY] точек. */
    private fun prune(r: FileRepository, all: List<Point>): List<Point> {
        val keep = all.takeLast(KEEP)
        (all - keep.toSet()).forEach { p -> r.updateRef("refs/dsh/cp/${p.id}").apply { isForceUpdate = true }.delete() }
        if (all.size % GC_EVERY == 0) runCatching {
            // Час на «висящие» объекты: снимок, который сейчас пишется, ещё ни на что не ссылается.
            org.eclipse.jgit.api.Git(r).gc().setExpire(java.util.Date(System.currentTimeMillis() - 3_600_000L)).call()
        }
        return keep
    }

    private fun save(points: List<Point>) {
        dir.mkdirs()
        val tmp = File(dir, ".points.json.tmp")
        tmp.writeText(json.encodeToString(points))
        if (!tmp.renameTo(indexFile)) error("cannot save points")
    }

    companion object {
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        const val MAX_FILES = 20_000
        const val KEEP = 200
        private const val GC_EVERY = 50
        private const val MATCH_WINDOW_MS = 10 * 60_000L
        /** Текст «сообщения» служебной точки «как было перед возвратом». */
        const val UNDO_TEXT = "\u0000dsh-undo"
        /** Пересобираемые и служебные папки: снимать их долго, а возвращать бессмысленно. */
        val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", "dist", "target", ".venv", "venv", "__pycache__", ".next", ".cache", ".dsh-attachments")

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
