package com.deepseek.dsh.runtime.host

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * SHA-256 содержимого дерева файлов: строки `путь\u0000sha256(файл)\n` всех обычных файлов (ссылки не учитываются
 * и не открываются), по возрастанию пути в байтах UTF-8. Не зависит от упаковки (zip, tar) и порядка записей:
 * тот же хэш даёт `scripts/bridge-plugin-digest.py` по `git archive`. Им закрепляется bridge-плагин (G-42)
 * и подписывается резервная копия (G-05).
 */
object ContentDigest {
    /** Хэш обычных файлов под [root]. */
    fun ofTree(root: File): String {
        val base = root.toPath()
        val rows = ArrayList<Pair<String, String>>()
        Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                rows += base.relativize(path).joinToString("/") to sha256Hex(path.toFile())
            }
        }
        return ofRows(rows)
    }

    /** Хэш по готовым строкам (путь через `/`, sha256 файла в hex). */
    fun ofRows(rows: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        rows.map { (path, hash) -> path.toByteArray(Charsets.UTF_8) to hash }
            .sortedWith { a, b -> compareBytes(a.first, b.first) }
            .forEach { (path, hash) ->
                digest.update(path)
                digest.update(0)
                digest.update(hash.toByteArray(Charsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
        return hex(digest.digest())
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
