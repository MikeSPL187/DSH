package com.deepseek.dsh.runtime.host

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
 * 不跟随符号链接的目录树操作。
 *
 * Kotlin 的 `File.deleteRecursively()` / `copyRecursively()` / `walkTopDown()` 用
 * `File.isDirectory()` 判断目录，会**跟随**指向目录的符号链接：rootfs 和 node_modules
 * 里到处是链接（镜像自带的、Agent 在 proot 里创建的、pnpm 的），删除旧 rootfs 时就可能
 * 顺着一个指向 `/storage/emulated/0` 的链接把共享存储里的文件删掉（App 持有
 * MANAGE_EXTERNAL_STORAGE）。这里的实现只删除/复制链接本身，绝不进入链接目标。
 */
object SafeFiles {

    /**
     * 删除整棵树；符号链接只删链接本身。
     * @return 删除后路径不存在为 true。
     */
    fun deleteTree(file: File): Boolean {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(root)
            } else {
                // walkFileTree 默认不跟随链接：链接作为普通文件交给 visitFile。
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
     * 复制整棵树：符号链接按原样重建（不复制目标内容），保留文件属性与可执行位。
     *
     * @param skip 返回 true 的相对路径（以 `/` 分隔）连同其子树被跳过。
     * @param overwrite 目标已存在的文件是否覆盖。
     * @throws IOException 任意一个条目复制失败（调用方据此回滚，而不是吞掉错误）。
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

    /** 先写同目录临时文件并 fsync，再 rename 覆盖；崩溃时不会留下半截文件。 */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            java.io.FileOutputStream(tmp).use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException(com.deepseek.dsh.core.model.Tr.t("Cannot write ${file.absolutePath}", "Не удалось записать ${file.absolutePath}"))
        } finally {
            tmp.delete()
        }
    }
}
