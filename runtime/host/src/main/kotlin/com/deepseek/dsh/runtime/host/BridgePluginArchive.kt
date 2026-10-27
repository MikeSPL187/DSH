package com.deepseek.dsh.runtime.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Проверка и распаковка архива выпуска GitHub (zipball).
 *
 * У выпуска плагина нет файлов-вложений: сам тег `v<version>` и есть результат, клиент скачивает архив
 * исходников этого тега. В архиве уже собранный `lib/`, поэтому здесь только три вещи: безопасность путей,
 * совпадение версии, полнота входа — без сборки и без доверия к путям в архиве.
 *
 * Объект нарочно не зависит от API Android, поэтому распаковка и проверки покрываются обычными тестами JVM.
 */
internal object BridgePluginArchive {

    /** Имя файла манифеста плагина. */
    private const val MANIFEST_FILE = "package.json"

    /** Наибольший размер скачиваемого архива; текущий выпуск около 130 КБ, запас большой. */
    const val MAX_ARCHIVE_BYTES: Long = 32L * 1024 * 1024

    /** Наибольшая сумма распакованных байтов — защита от zip-бомб. */
    const val MAX_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    /** Наибольшее число записей. */
    const val MAX_ENTRIES: Int = 4096

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Манифест плагина из архива.
     * @param packageName `name` из `package.json`.
     * @param version `version` из `package.json`.
     * @param main входной файл (от папки пакета).
     * @param patch файл патча bundle (от папки пакета).
     * @param dependencies зависимости времени выполнения — проверить разбор до установки.
     */
    data class Contents(
        val packageName: String,
        val version: String,
        val main: String,
        val patch: String,
        val dependencies: Map<String, String>,
    )

    /**
     * Распаковать zipball GitHub в [destination] и проверить, что это тот плагин.
     *
     * В архиве должна быть ровно одна верхняя папка (так делает GitHub); пути записей не должны выходить из [destination].
     * Ошибка проверки — [IllegalArgumentException], недоделанную папку убирает вызывающий.
     * @param archive поток байтов zip.
     * @param destination целевая папка; при ошибке её удаляет вызывающий.
     * @param expectedPackage ожидаемое имя пакета, должно совпасть с манифестом архива.
     * @param expectedVersion ожидаемая версия, должна совпасть с манифестом архива.
     * @return проверенное содержимое манифеста.
     */
    fun extract(
        archive: InputStream,
        destination: File,
        expectedPackage: String,
        expectedVersion: String,
    ): Contents {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        var rootPrefix: String? = null
        var manifest: String? = null
        var entryCount = 0
        var writtenBytes = 0L

        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRIES) {
                    throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive has more than $MAX_ENTRIES entries", "В архиве больше $MAX_ENTRIES записей"))
                }
                val name = entry.name
                if (name.isBlank() || name.contains('\u0000')) {
                    throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive contains an invalid entry name", "В архиве недопустимое имя записи"))
                }
                val prefix = rootPrefix ?: run {
                    val detected = name.substringBefore('/', missingDelimiterValue = "")
                    if (detected.isBlank()) throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive has no top-level directory: $name", "В архиве нет корневой папки: $name"))
                    (detected + "/").also { rootPrefix = it }
                }
                if (!name.startsWith(prefix)) {
                    throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive has an entry outside the top-level directory: $name", "В архиве запись вне корневой папки: $name"))
                }
                val relative = name.removePrefix(prefix).trimEnd('/')
                if (relative.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val target = safeTarget(canonicalRoot, relative)
                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }
                if (entry.size > MAX_UNCOMPRESSED_BYTES) {
                    throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive entry is too large: $relative", "Слишком большая запись архива: $relative"))
                }
                target.parentFile?.mkdirs()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                target.outputStream().use { output ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        writtenBytes += read
                        if (writtenBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Unpacked size exceeds $MAX_UNCOMPRESSED_BYTES bytes", "После распаковки больше $MAX_UNCOMPRESSED_BYTES байт"))
                        }
                        output.write(buffer, 0, read)
                    }
                }
                if (relative == MANIFEST_FILE) manifest = target.readText()
                zip.closeEntry()
            }
        }

        val manifestText = manifest
            ?: throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive has no $MANIFEST_FILE", "В архиве нет $MANIFEST_FILE"))
        val parsed = runCatching {
            require(!com.deepseek.dsh.core.model.jsonTooDeep(manifestText)) { "nested too deeply" }
            json.decodeFromString(ArchiveManifest.serializer(), manifestText)
        }.getOrElse { error ->
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Cannot parse $MANIFEST_FILE: ${error.message}", "Не удалось разобрать $MANIFEST_FILE: ${error.message}"))
        }
        if (parsed.name != expectedPackage) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Package mismatch: archive is ${parsed.name}, expected $expectedPackage", "Имя пакета не совпадает: в архиве ${parsed.name}, ожидалось $expectedPackage"))
        }
        if (parsed.version != expectedVersion) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Version mismatch: archive is ${parsed.version}, expected $expectedVersion", "Версия не совпадает: в архиве ${parsed.version}, ожидалась $expectedVersion"))
        }
        val main = parsed.main.ifBlank { "lib/index.js" }.normalizeArchivePath()
        val patch = (parsed.dsh?.bundle?.patch
            ?: throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Plugin does not declare dsh.bundle.patch", "Плагин не объявляет dsh.bundle.patch"))).normalizeArchivePath()
        requireFile(canonicalRoot, main, com.deepseek.dsh.core.model.Tr.t("entry", "точка входа"))
        requireFile(canonicalRoot, patch, "bundle patch")
        return Contents(
            packageName = parsed.name,
            version = parsed.version,
            main = main,
            patch = patch,
            dependencies = parsed.dependencies.orEmpty(),
        )
    }

    /** SHA-256 содержимого распакованного пакета ([ContentDigest]) — сверяется с закреплённым в APK (G-42). */
    fun contentDigest(root: File): String = ContentDigest.ofTree(root)

    /** Относительный путь в архиве → целевой файл; любой выход за пределы отклоняется. */
    private fun safeTarget(root: File, relative: String): File {
        if (relative.startsWith('/') || relative.startsWith('\\')) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive contains an absolute path: $relative", "В архиве абсолютный путь: $relative"))
        }
        val segments = relative.split('/')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive path escapes the root: $relative", "Путь в архиве выходит за пределы папки: $relative"))
        }
        if (relative.contains('\\')) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive contains an invalid path separator: $relative", "В архиве недопустимый разделитель пути: $relative"))
        }
        val target = File(root, relative)
        val canonical = runCatching { target.canonicalFile }.getOrElse {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Cannot resolve archive path: $relative", "Не удалось разобрать путь в архиве: $relative"))
        }
        if (canonical != root && !canonical.toPath().startsWith(root.toPath())) {
            throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Archive path escapes the target directory: $relative", "Путь в архиве выходит за целевую папку: $relative"))
        }
        return target
    }

    /** Файл должен быть в архиве; пути из манифеста тоже не могут выходить из папки пакета. */
    private fun requireFile(root: File, relative: String, label: String) {
        if (relative.isBlank()) throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Plugin has no $label path", "У плагина не указан путь: $label"))
        val file = safeTarget(root, relative)
        if (!file.isFile) throw IllegalArgumentException(com.deepseek.dsh.core.model.Tr.t("Plugin is missing $label: $relative", "У плагина отсутствует $label: $relative"))
    }

    /** Пути манифеста могут начинаться с `./` — приводим к пути внутри пакета. */
    private fun String.normalizeArchivePath(): String =
        trim().removePrefix("./").replace('\\', '/').trimStart('/')

    @Serializable
    private data class ArchiveManifest(
        val name: String = "",
        val version: String = "",
        val main: String = "",
        val dsh: ArchiveDsh? = null,
        val dependencies: Map<String, String>? = null,
    )

    @Serializable
    private data class ArchiveDsh(val bundle: ArchiveBundle? = null)

    @Serializable
    private data class ArchiveBundle(val patch: String? = null)
}
