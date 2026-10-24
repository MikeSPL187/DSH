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
 * v4.0 设计文档 §4.5 & §4.6:
 * 用户数据快照备份与脱敏诊断包导出
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
                // 保证至少写入一条根快照 entry
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
                // 1. 导出运行日志 (自动脱敏)
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

                // 2. 导出环境描述
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
        // §4.6 诊断脱敏：token/cookie/API key。除了 query/Bearer/sk- 之外，日志里常见
        // JSON 字段、Cookie/Authorization 头和 GitHub 令牌，原实现会原样导出它们。
        return SCRUB_RULES.fold(rawText) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
    }

    /** 递归打包目录：不跟随符号链接（node_modules 里的链接会指向整棵 rootfs 或形成环）。 */
    private fun zipDirectory(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            val path = file.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) continue
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

    private companion object {
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
