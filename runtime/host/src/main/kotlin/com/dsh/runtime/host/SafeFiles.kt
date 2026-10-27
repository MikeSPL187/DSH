package com.dsh.runtime.host

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Операции с деревом папок без перехода по символическим ссылкам.
 *
 * `File.deleteRecursively()` / `copyRecursively()` / `walkTopDown()` в Kotlin определяют папку через
 * `File.isDirectory()` и **идут** по ссылкам на папки: в rootfs и node_modules
 * ссылок полно (из образа, созданные агентом в proot, от pnpm), и при удалении старого rootfs можно было
 * по ссылке на `/storage/emulated/0` удалить файлы общей памяти (у приложения есть
 * MANAGE_EXTERNAL_STORAGE). Здесь удаляется и копируется только сама ссылка, в её цель не заходим никогда.
 */
object SafeFiles {

    /**
     * Удалить всё дерево; символическая ссылка удаляется как ссылка.
     * @return true, если после удаления пути нет.
     */
    fun deleteTree(file: File): Boolean {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(root)
            } else {
                // walkFileTree по умолчанию по ссылкам не ходит: ссылка приходит в visitFile как обычный файл.
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(path)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(path)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    /**
     * Скопировать всё дерево: символические ссылки воссоздаются как есть (без копии цели), атрибуты и бит исполнения сохраняются.
     *
     * @param skip для относительных путей (через `/`), где вернул true, путь и его поддерево пропускаются.
     * @param overwrite перезаписывать ли существующие файлы цели.
     * @throws IOException если не скопировалась любая запись (вызывающий откатывается, а не глотает ошибку).
     */
    fun copyTree(
        source: File,
        target: File,
        overwrite: Boolean = true,
        skip: (String) -> Boolean = { false },
    ) {
        val sourceRoot = source.toPath()
        val targetRoot = target.toPath()
        Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
            private fun relative(path: Path): String =
                sourceRoot.relativize(path).toString().replace(File.separatorChar, '/')

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = relative(dir)
                if (rel.isNotEmpty() && skip(rel)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(targetRoot.resolve(rel))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = relative(path)
                if (skip(rel)) return FileVisitResult.CONTINUE
                val destination = targetRoot.resolve(rel)
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (!overwrite) return FileVisitResult.CONTINUE
                    if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) deleteTree(destination.toFile())
                    else Files.delete(destination)
                }
                if (attrs.isSymbolicLink) {
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(path))
                } else if (attrs.isRegularFile) {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Сначала временный файл в той же папке и fsync, затем rename поверх: при падении не останется половины файла. */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            java.io.FileOutputStream(tmp).use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException(com.dsh.core.model.Tr.t("Cannot write ${file.absolutePath}", "Не удалось записать ${file.absolutePath}"))
        } finally {
            tmp.delete()
        }
    }
}
