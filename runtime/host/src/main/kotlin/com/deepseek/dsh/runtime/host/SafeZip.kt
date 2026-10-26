package com.deepseek.dsh.runtime.host

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Распаковка zip из недоверенного источника (файл пользователя, резервная копия).
 *
 * Запись не выходит из папки назначения (zip-slip, абсолютные пути, `..`, ссылки внутри папки
 * на внешние каталоги), а размер распакованного и число записей ограничены — zip-бомба
 * не заполнит память телефона. Нарушение — [IOException]; недоделанную папку убирает вызывающий.
 */
object SafeZip {

    /** Пределы распаковки. */
    data class Limits(
        val maxTotalBytes: Long = 256L * 1024 * 1024,
        val maxEntryBytes: Long = 128L * 1024 * 1024,
        val maxEntries: Int = 20_000,
    )

    /**
     * Распаковать [input] в [destination].
     *
     * @param pathOf путь записи внутри [destination] или null — запись пропускается.
     *   Вызывается для каждой записи по порядку, в том числе для папок.
     * @return число записанных файлов.
     */
    fun extract(
        input: InputStream,
        destination: File,
        limits: Limits = Limits(),
        pathOf: (ZipEntry, index: Int) -> String? = { entry, _ -> entry.name },
    ): Int {
        val root = destination.apply { mkdirs() }.canonicalFile
        var entries = 0
        var total = 0L
        var files = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > limits.maxEntries) throw IOException("archive has more than ${limits.maxEntries} entries")
                val relative = pathOf(entry, entries - 1)?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: continue
                val target = targetOf(root, relative)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                var written = 0L
                target.outputStream().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        written += read
                        total += read
                        if (written > limits.maxEntryBytes) throw IOException("archive entry is too large: $relative")
                        if (total > limits.maxTotalBytes) throw IOException("unpacked size exceeds ${limits.maxTotalBytes} bytes")
                        out.write(buffer, 0, read)
                    }
                }
                files++
            }
        }
        return files
    }

    /** Файл записи внутри [root]; любой выход за пределы — [IOException]. */
    internal fun targetOf(root: File, relative: String): File {
        if (relative.startsWith('/') || relative.contains('\u0000') || relative.contains('\\')) throw IOException("invalid archive path: $relative")
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) throw IOException("archive path escapes the root: $relative")
        val target = File(root, relative)
        // canonicalFile раскрывает ссылки: папка-ссылка внутри root на внешний каталог тоже отклоняется.
        val canonical = target.canonicalFile
        if (!canonical.toPath().startsWith(root.toPath()) || canonical == root) throw IOException("archive path escapes the root: $relative")
        return canonical
    }
}
