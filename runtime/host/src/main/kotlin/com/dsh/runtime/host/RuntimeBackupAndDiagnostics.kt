package com.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Подпись резервной копии ключом этого телефона (HMAC-SHA256 в Android Keystore, ключ не покидает устройство).
 * null — подписать нельзя (Keystore недоступен).
 */
fun interface BackupSigner {
    fun sign(data: ByteArray): ByteArray?
}

/**
 * Что будет восстановлено: копия уже распакована во временную папку рядом с данными Host, файлы ещё не тронуты.
 * @param files пути файлов копии (от `DSH_HOME`), которые будут записаны.
 * @param replaced из них — те, что уже есть и будут заменены.
 * @param trusted копия подписана ключом этого телефона и не изменена.
 */
class RestorePlan(
    internal val staging: File,
    val files: List<String>,
    val replaced: List<String>,
    val trusted: Boolean,
)

/**
 * Резервная копия данных пользователя и выгрузка диагностики без секретов.
 *
 * Копия подписывается [signer] (G-05): при восстановлении копия с этого телефона узнаётся, а чужая или
 * изменённая (в ней могут быть включённые плагины, пресет прав, конфиги MCP — всё, что Host выполнит после
 * запуска) показывается с предупреждением. Восстановление — в два шага: [prepareRestore] распаковывает во
 * временную папку и показывает, что будет заменено; [applyRestore] переносит файлы с откатом при сбое.
 */
class RuntimeBackupAndDiagnostics(
    private val layout: RuntimeLayout,
    private val signer: BackupSigner? = null,
) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun createUserDataBackup(): Result<File> = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val timestamp = dateFormat.format(Date())
        val backupZip = File(layout.backupsDir, "dsh_backup_$timestamp.zip")

        try {
            val rows = ArrayList<Pair<String, String>>()
            ZipOutputStream(FileOutputStream(backupZip)).use { zos ->
                // Хотя бы одна корневая запись снимка.
                val markerEntry = ZipEntry(BACKUP_MARKER)
                zos.putNextEntry(markerEntry)
                zos.write("DSH User Data Snapshot $timestamp\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (layout.dshHomeDir.exists()) {
                    zipDirectory(layout.dshHomeDir, "dsh_home", zos, rows)
                }
                // Подпись — последней записью: HMAC ключом телефона от хэша содержимого (пути и файлы).
                signer?.let { runCatching { it.sign(ContentDigest.ofRows(rows).toByteArray()) }.getOrNull() }?.let { mac ->
                    zos.putNextEntry(ZipEntry(BACKUP_SIGNATURE))
                    zos.write((SIGNATURE_PREFIX + ContentDigest.hex(mac)).toByteArray(Charsets.US_ASCII))
                    zos.closeEntry()
                }
            }
            Result.success(backupZip)
        } catch (e: Exception) {
            runCatching { backupZip.delete() }
            Result.failure(e)
        }
    }

    suspend fun exportDiagnosticsZip(): Result<File> = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val timestamp = dateFormat.format(Date())
        // Прошлые архивы диагностики больше не нужны (их уже отдали через «Поделиться») — не копим.
        HostLog.rotate(layout.logsDir, "dsh_diagnostics_", ".zip", HostLog.KEEP_DIAGNOSTICS)
        val diagZip = File(layout.logsDir, "dsh_diagnostics_$timestamp.zip")

        try {
            ZipOutputStream(FileOutputStream(diagZip)).use { zos ->
                // 1. Журналы окружения (секреты вычищаются).
                if (layout.logsDir.exists()) {
                    layout.logsDir.listFiles()?.forEach { logFile ->
                        if (logFile.isFile && logFile.name.endsWith(".log")) {
                            zos.putNextEntry(ZipEntry("logs/${logFile.name}"))
                            writeLogTail(logFile, zos)
                            zos.closeEntry()
                        }
                    }
                }

                // 2. Описание окружения.
                val envSummary = """
                    OS: Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
                    Brand: ${android.os.Build.BRAND}
                    Model: ${android.os.Build.MODEL}
                    ABI: ${android.os.Build.SUPPORTED_ABIS?.joinToString().orEmpty()}
                    Created: ${Date()}
                """.trimIndent()
                val envEntry = ZipEntry("env_summary.txt")
                zos.putNextEntry(envEntry)
                zos.write(envSummary.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            Result.success(diagZip)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Хвост журнала (не больше [DIAGNOSTICS_LOG_BYTES]) построчно через [SecretScrubber] прямо в архив (G-09):
     * журнал не читается в память целиком — старые журналы без предела размера давали OOM.
     */
    private fun writeLogTail(file: File, out: java.io.OutputStream) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val skip = (raf.length() - DIAGNOSTICS_LOG_BYTES).coerceAtLeast(0)
            raf.seek(skip)
            val reader = java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(raf.channel), Charsets.UTF_8).buffered()
            if (skip > 0) {
                reader.readLine() // неполная первая строка
                out.write("[…] ${skip / 1024} KB earlier output omitted\n".toByteArray())
            }
            reader.lineSequence().forEach { line ->
                out.write((SecretScrubber.scrub(line.take(HostLog.MAX_LINE)) + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    internal fun scrubSensitiveInfo(rawText: String): String {
        return SecretScrubber.scrub(rawText)
    }

    /** Рекурсивно упаковать папку: по символическим ссылкам не ходим (ссылки в node_modules ведут на весь rootfs или образуют цикл). */
    private fun zipDirectory(folder: File, parentFolder: String, zos: ZipOutputStream, rows: MutableList<Pair<String, String>>) {
        for (file in folder.listFiles() ?: emptyArray()) {
            val path = file.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) continue
            // Копия «без API-ключей»: файлы с секретами в архив не попадают.
            if (SECRET_FILE.containsMatchIn(file.name)) continue
            if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                zipDirectory(file, "$parentFolder/${file.name}", zos, rows)
            } else if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                zos.putNextEntry(ZipEntry("$parentFolder/${file.name}"))
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { fis ->
                    java.security.DigestInputStream(fis, digest).copyTo(zos)
                }
                zos.closeEntry()
                rows += "$parentFolder/${file.name}".removePrefix("dsh_home/") to ContentDigest.hex(digest.digest())
            }
        }
    }

    /**
     * Восстановить копию из [createUserDataBackup] сразу, без предпросмотра ([prepareRestore] + [applyRestore]).
     * Host на время восстановления должен быть остановлен. Возвращает число восстановленных файлов.
     */
    suspend fun restoreUserDataBackup(input: java.io.InputStream): Result<Int> =
        prepareRestore(input).mapCatching { plan -> applyRestore(plan).getOrThrow() }

    /**
     * Шаг 1: распаковать копию во временную папку рядом с `DSH_HOME` (данные Host не трогаются) и проверить
     * подпись. Записи `dsh_home/…` берутся, пути за пределами папки (zip-slip), ссылки и файлы с секретами
     * пропускаются; знак снимка должен быть первой записью — чужой архив отклоняется сразу.
     */
    suspend fun prepareRestore(input: java.io.InputStream): Result<RestorePlan> = withContext(Dispatchers.IO) {
        val staging = File(layout.dshHomeDir.parentFile, RESTORE_STAGING)
        SafeFiles.deleteTree(staging)
        runCatching {
            SafeZip.extract(input, staging) { entry, index ->
                if (index == 0) {
                    require(entry.name == BACKUP_MARKER) { "not a DSH backup" }
                    return@extract null
                }
                if (entry.name == BACKUP_SIGNATURE) return@extract SIGNATURE_FILE
                val rel = entry.name.takeIf { it.startsWith("dsh_home/") }?.removePrefix("dsh_home/")
                rel?.takeIf { it.isNotBlank() && it != SIGNATURE_FILE && !SECRET_FILE.containsMatchIn(it.substringAfterLast('/')) }
                    ?.let { "$HOME_DIR/$it" }
            }
            val home = File(staging, HOME_DIR).apply { mkdirs() }
            val signature = File(staging, SIGNATURE_FILE).takeIf { it.isFile }?.readText()?.trim()
            val trusted = signature != null && signature.startsWith(SIGNATURE_PREFIX) && signer != null &&
                runCatching { signer.sign(ContentDigest.ofTree(home).toByteArray()) }.getOrNull()?.let { mac ->
                    java.security.MessageDigest.isEqual(
                        ContentDigest.hex(mac).toByteArray(),
                        signature.removePrefix(SIGNATURE_PREFIX).lowercase().toByteArray(),
                    )
                } == true
            val files = java.nio.file.Files.walk(home.toPath()).use { paths ->
                paths.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                    .map { home.toPath().relativize(it).joinToString("/") }
                    .collect(java.util.stream.Collectors.toList())
            }.sorted()
            RestorePlan(
                staging = staging,
                files = files,
                replaced = files.filter { File(layout.dshHomeDir, it).exists() },
                trusted = trusted,
            )
        }.onFailure { SafeFiles.deleteTree(staging) }
    }

    /**
     * Шаг 2: перенести файлы копии в `DSH_HOME`. Заменяемые файлы сначала отодвигаются в папку отката; сбой
     * на любом файле возвращает всё как было (новые файлы удаляются, старые возвращаются). Переносы — `rename`
     * на том же диске, без копирования данных. Host должен быть остановлен.
     */
    suspend fun applyRestore(plan: RestorePlan): Result<Int> = withContext(Dispatchers.IO) {
        val home = File(plan.staging, HOME_DIR)
        val rollback = File(plan.staging, ROLLBACK_DIR)
        val moved = ArrayList<String>()
        val created = ArrayList<String>()
        val result = runCatching {
            for (rel in plan.files) {
                val source = File(home, rel)
                val target = SafeZip.targetOf(layout.dshHomeDir.apply { mkdirs() }.canonicalFile, rel)
                if (java.nio.file.Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val aside = File(rollback, rel).apply { parentFile?.mkdirs() }
                    move(target, aside)
                    moved += rel
                }
                target.parentFile?.mkdirs()
                move(source, target)
                created += rel
            }
            plan.files.size
        }
        if (result.isFailure) {
            // Откат: убрать записанное и вернуть отодвинутое.
            created.forEach { rel -> runCatching { java.nio.file.Files.deleteIfExists(File(layout.dshHomeDir, rel).toPath()) } }
            moved.forEach { rel -> runCatching { move(File(rollback, rel), File(layout.dshHomeDir, rel)) } }
        }
        SafeFiles.deleteTree(plan.staging)
        result
    }

    /** Отказаться от восстановления: убрать временную папку. */
    fun discardRestore(plan: RestorePlan) {
        SafeFiles.deleteTree(plan.staging)
    }

    private fun move(from: File, to: File) {
        java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        /** Сколько последних байтов каждого журнала идёт в диагностику. */
        const val DIAGNOSTICS_LOG_BYTES = 2L * 1024 * 1024

        /** Первая запись каждой резервной копии DSH. */
        const val BACKUP_MARKER = "snapshot_meta.txt"

        /** Последняя запись: HMAC ключом телефона от [ContentDigest] файлов `dsh_home/`. */
        const val BACKUP_SIGNATURE = "snapshot_sig.txt"
        const val SIGNATURE_PREFIX = "hmac-sha256:"

        /** Временная папка восстановления — рядом с `DSH_HOME`, на том же диске (переносы — rename). */
        const val RESTORE_STAGING = ".dsh-restore"
        const val HOME_DIR = "home"
        const val ROLLBACK_DIR = "rollback"
        const val SIGNATURE_FILE = "signature"

        /** Имена файлов с ключами и токенами — их не архивируем и не восстанавливаем. */
        val SECRET_FILE = Regex("""(?i)(credential|secret|token|\.env$|auth\.json$|\.key$|\.pem$)""")
    }
}
