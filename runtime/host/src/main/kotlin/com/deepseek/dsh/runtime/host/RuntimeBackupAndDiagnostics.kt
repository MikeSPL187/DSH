package com.deepseek.dsh.runtime.host

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
 * Резервная копия данных пользователя и выгрузка диагностики без секретов.
 */
class RuntimeBackupAndDiagnostics(
    private val layout: RuntimeLayout
) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun createUserDataBackup(): Result<File> = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val timestamp = dateFormat.format(Date())
        val backupZip = File(layout.backupsDir, "dsh_backup_$timestamp.zip")

        try {
            ZipOutputStream(FileOutputStream(backupZip)).use { zos ->
                // Хотя бы одна корневая запись снимка.
                val markerEntry = ZipEntry("snapshot_meta.txt")
                zos.putNextEntry(markerEntry)
                zos.write("DSH User Data Snapshot $timestamp\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (layout.dshHomeDir.exists()) {
                    zipDirectory(layout.dshHomeDir, "dsh_home", zos)
                }
            }
            Result.success(backupZip)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportDiagnosticsZip(): Result<File> = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val timestamp = dateFormat.format(Date())
        val diagZip = File(layout.logsDir, "dsh_diagnostics_$timestamp.zip")

        try {
            ZipOutputStream(FileOutputStream(diagZip)).use { zos ->
                // 1. Журналы окружения (секреты вычищаются).
                if (layout.logsDir.exists()) {
                    layout.logsDir.listFiles()?.forEach { logFile ->
                        if (logFile.isFile && logFile.name.endsWith(".log")) {
                            val scrubbed = scrubSensitiveInfo(logFile.readText())
                            val entry = ZipEntry("logs/${logFile.name}")
                            zos.putNextEntry(entry)
                            zos.write(scrubbed.toByteArray(Charsets.UTF_8))
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

    internal fun scrubSensitiveInfo(rawText: String): String {
        // Вычистка секретов: token/cookie/API-ключ. Кроме query/Bearer/sk-, в журналах часто встречаются
        // поля JSON, заголовки Cookie/Authorization и токены GitHub — раньше они выгружались как есть.
        return SCRUB_RULES.fold(rawText) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
    }

    /** Рекурсивно упаковать папку: по символическим ссылкам не ходим (ссылки в node_modules ведут на весь rootfs или образуют цикл). */
    private fun zipDirectory(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            val path = file.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) continue
            // Копия «без API-ключей»: файлы с секретами в архив не попадают.
            if (SECRET_FILE.containsMatchIn(file.name)) continue
            if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                zipDirectory(file, "$parentFolder/${file.name}", zos)
            } else if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                zos.putNextEntry(ZipEntry("$parentFolder/${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * Восстановить копию из [createUserDataBackup]: записи `dsh_home/…` раскладываются
     * в [RuntimeLayout.dshHomeDir]. Пути за пределами папки (zip-slip), ссылки и файлы
     * с секретами пропускаются. Host на время восстановления должен быть остановлен.
     * Возвращает число восстановленных файлов.
     */
    suspend fun restoreUserDataBackup(input: java.io.InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val home = layout.dshHomeDir.apply { mkdirs() }.canonicalFile
            var count = 0
            var marker = false
            java.util.zip.ZipInputStream(input).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.name == "snapshot_meta.txt") { marker = true; continue }
                    if (e.isDirectory || !e.name.startsWith("dsh_home/")) continue
                    val rel = e.name.removePrefix("dsh_home/")
                    if (rel.isBlank() || SECRET_FILE.containsMatchIn(rel.substringAfterLast('/'))) continue
                    val target = File(home, rel).canonicalFile
                    if (!target.path.startsWith(home.path + File.separator)) continue
                    target.parentFile?.mkdirs()
                    java.io.FileOutputStream(target).use { zis.copyTo(it) }
                    count++
                }
            }
            require(marker) { "not a DSH backup" }
            count
        }
    }

    private companion object {
        /** Имена файлов с ключами и токенами — их не архивируем и не восстанавливаем. */
        val SECRET_FILE = Regex("""(?i)(credential|secret|token|\.env$|auth\.json$|\.key$|\.pem$)""")

        private val SECRET_KEYS =
            "(?:token|access_?token|refresh_?token|api_?key|apikey|secret|password|passwd|authorization|cookie|session|credential|private_?key)"
        val SCRUB_RULES: List<Pair<Regex, String>> = listOf(
            Regex("""(?i)([?&](?:token|auth|access_token|key)=)[^&\s"']+""") to "$1REDACTED",
            Regex("""(?i)token=[a-zA-Z0-9_\-.]+""") to "token=REDACTED",
            Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-.~+/=]+""") to "Bearer REDACTED",
            Regex("""(?i)\b(authorization|x-api-key|cookie|set-cookie)\s*:\s*[^\r\n]+""") to "$1: REDACTED",
            Regex("""(?i)("[A-Za-z0-9_\-]*$SECRET_KEYS[A-Za-z0-9_\-]*"\s*:\s*)"[^"]*"""") to "$1\"REDACTED\"",
            Regex("""(?i)\b(dsh-web-session|session|sid)=[^;\s"']+""") to "$1=REDACTED",
            Regex("""\bsk-[A-Za-z0-9_\-]{8,}""") to "sk-REDACTED",
            Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""") to "gh_REDACTED",
        )
    }
}
