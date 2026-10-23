package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/** 安装进度：0..1 + 面向用户的步骤文案。 */
data class InstallProgress(
    val step: String,
    val progress: Float
)

/**
 * `rootfs-manifest.json` 的契约：真正的镜像地址/校验值由发布流水线写入，
 * App 只负责下载、校验、解包与安装标记。
 */
@Serializable
data class RootfsManifest(
    val runtimeVersion: String = "",
    val rootfsName: String = RootfsNaming.ARCHIVE_PREFIX,
    val releaseTag: String = "",
    val baseDistro: String = "",
    val libc: String = "",
    val pageSize: String = "",
    val bundledPackages: List<String> = emptyList(),
    val archiveFormat: String = "tar.gz",
    /** ABI -> assets 内自带的 rootfs 归档路径（可选，主要用于离线调试）。 */
    val assetArchives: Map<String, String> = emptyMap(),
    /** ABI -> 归档 SHA-256。 */
    val assetSha256: Map<String, String> = emptyMap(),
    /** ABI -> 首次下载地址（正式发布使用，App 不再内置大体积 rootfs）。 */
    val downloadUrls: Map<String, String> = emptyMap(),
    /** ABI -> 下载归档 SHA-256。 */
    val downloadSha256: Map<String, String> = emptyMap(),
    val downloadUrl: String = "",
    val sha256: String = "",
    val archiveSizeBytes: Long = 0L,
    val prootLibrary: String = "libproot.so",
    /**
     * 镜像是否在 rootfs 构建阶段就已经应用了 Android 运行时补丁。
     *
     * 为 true 时不再要求镜像的 DSH 段与本 APK 的整文件补丁目标一致：镜像里的
     * 上游文件已经是「最新 DSH + 补丁」的组合，App 只需跳过运行时覆盖。
     */
    val androidPatched: Boolean = false,
)

/**
 * rootfs 安装流水线（官方 §4.2/§4.7）：
 * 下载（可跳过）→ SHA-256 校验 → 空间预检 → 解包到 staging（旧 rootfs 不动）→
 * 旧目录 rename 为备份 → 新目录 rename 到位 → 合并 DSH_HOME → 工具链门禁 → 安装标记
 * → 删除备份。任一步失败（含进程被杀后下次启动）都回滚到旧 rootfs（审计 F-02/N-08）。
 *
 * 开发期可把镜像放到 `cache/rootfs-local.tar.gz`（无需网络），manifest 的
 * `sha256` 留空时跳过校验但会记录告警。
 */
class RootfsInstaller(
    private val layout: RuntimeLayout,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(rawJson: String): RootfsManifest = json.decodeFromString(rawJson)

    /**
     * 尝试联网检查 deepseek-harness-mobile-rootfs 最新 release 中的 rootfs-manifest.json。
     * 若网络不通或未发布，则平滑 fallback 返回本地 bundled manifest。
     */
    suspend fun resolveLatestManifest(bundledManifest: RootfsManifest): RootfsManifest = withContext(Dispatchers.IO) {
        val latestManifestUrl = "https://github.com/EricFL1998/deepseek-harness-mobile-rootfs/releases/latest/download/rootfs-manifest.json"
        try {
            val request = Request.Builder()
                .url(latestManifestUrl)
                .header("User-Agent", "dsh-android")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val remoteManifest = parseManifest(body)
                        if (remoteManifest.runtimeVersion.isNotBlank()) {
                            return@withContext remoteManifest
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 网络异常或 404，无缝回退本地 manifest
        }
        bundledManifest
    }

    fun installedVersion(): String? {
        val marker = markerFile()
        if (!marker.isFile) return null
        return runCatching { json.decodeFromString<InstallMarker>(marker.readText()).runtimeVersion }
            .getOrNull()
    }

    /** 读取安装标记；缺失或损坏时为 null。 */
    internal fun readMarker(): InstallMarker? {
        val marker = markerFile()
        if (!marker.isFile) return null
        return runCatching { json.decodeFromString<InstallMarker>(marker.readText()) }.getOrNull()
    }

    fun isInstalled(manifest: RootfsManifest): Boolean {
        recoverInterruptedInstall()
        val version = installedVersion() ?: return false
        if (version != manifest.runtimeVersion) return false
        val marker = readMarker()
        val currentAbi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
        if (!marker?.abi.isNullOrBlank() && currentAbi.isNotBlank() && marker.abi != currentAbi) return false
        return File(layout.rootfsDir, "etc/os-release").isFile ||
            File(layout.rootfsDir, "usr/bin/env").isFile
    }

    suspend fun verifyAndInstall(
        archiveFile: File,
        expectedSha256: String? = null,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        installArchive(
            manifest = RootfsManifest(sha256 = expectedSha256.orEmpty()),
            archive = archiveFile,
            onProgress = onProgress,
        )
    }

    /** 把调用方提供的归档（assets 自带 / SAF 选择）按 manifest 契约安装。 */
    suspend fun installFromArchive(
        manifest: RootfsManifest,
        archiveFile: File,
        expectedSha256: String?,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        installArchive(
            manifest = manifest.copy(sha256 = expectedSha256 ?: manifest.sha256),
            archive = archiveFile,
            onProgress = onProgress,
        )
    }

    /** 按 manifest 安装；`cache/rootfs-local.tar.gz` 优先用于无网调试。 */
    suspend fun install(
        manifest: RootfsManifest,
        abi: String? = null,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val archiveUrl = abi
            ?.let { manifest.downloadUrls[it] }
            ?.takeIf { it.isNotBlank() }
            ?: manifest.downloadUrl
        val expectedSha = abi
            ?.let { manifest.downloadSha256[it] }
            ?.takeIf { it.isNotBlank() }
            ?: manifest.sha256
        val effectiveManifest = manifest.copy(downloadUrl = archiveUrl, sha256 = expectedSha)
        val localOverride = File(layout.cacheDir, "rootfs-local.tar.gz")
        val archive = when {
            localOverride.isFile -> localOverride
            archiveUrl.isNotBlank() -> {
                // 网络来源必须走 HTTPS 且带 SHA-256：没有校验值的远程 manifest 不能安装。
                if (!archiveUrl.startsWith("https://", ignoreCase = true)) {
                    return@withContext Result.failure(IllegalStateException(HostStrings.get(R.string.rt_https_required, "The rootfs download URL must use HTTPS: %1\$s", archiveUrl)))
                }
                if (!SHA256_HEX.matches(expectedSha.removePrefix("sha256:"))) {
                    return@withContext Result.failure(IllegalStateException(HostStrings.get(R.string.rt_sha_missing, "The rootfs manifest has no valid SHA-256; installation refused")))
                }
                val target = RootfsNaming.archiveCacheFile(
                    layout.cacheDir,
                    manifest.runtimeVersion,
                    abi ?: android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty().ifBlank { "unknown" }
                )
                if (!(target.isFile && digestMatches(target, expectedSha))) {
                    onProgress(InstallProgress(HostStrings.get(R.string.rt_download, "Downloading rootfs (%1\$s)…", manifest.baseDistro.ifBlank { "Debian" }), 0.02f))
                    val downloaded = download(effectiveManifest, target) { fraction ->
                        onProgress(InstallProgress(HostStrings.get(R.string.rt_download_pct, "Downloading rootfs… %1\$d%%", (fraction * 100).toInt()), 0.02f + fraction * 0.48f))
                    }
                    if (downloaded.isFailure) return@withContext downloaded
                }
                target
            }
            else -> return@withContext Result.failure(
                IllegalStateException(
                    HostStrings.get(R.string.rt_no_url, "No rootfs download URL configured")
                )
            )
        }

        if (!archive.isFile || archive.length() == 0L) {
            return@withContext Result.failure(IllegalStateException(HostStrings.get(R.string.rt_archive_missing, "The rootfs archive is missing or empty")))
        }
        return@withContext installArchive(effectiveManifest, archive, onProgress)
    }

    /** 上一次安装进行到一半时留下的日志文件（存在即表示未完成）。 */
    private fun journalFile(): File = File(layout.runtimeMetaDir, "rootfs-install.pending")

    /** 替换期间保存的旧 rootfs。 */
    private fun previousRootfsDir(): File = File(layout.baseDir, "${RootfsNaming.INSTALL_DIR}.previous")

    /**
     * 如果上次安装在替换阶段被打断（kill/断电），把旧 rootfs 换回来。
     * 安装标记最后才写，所以日志存在时当前 rootfs 一定不是完整的新版本。
     */
    internal fun recoverInterruptedInstall() {
        // 本进程里正在安装时，日志与备份都是「进行中」的正常状态，不能回滚。
        if (INSTALL_IN_PROGRESS.get()) return
        val journal = journalFile()
        if (!journal.isFile) return
        val previous = previousRootfsDir()
        if (previous.exists()) {
            SafeFiles.deleteTree(layout.rootfsDir)
            if (!previous.renameTo(layout.rootfsDir)) return
        }
        journal.delete()
    }

    private fun installArchive(
        manifest: RootfsManifest,
        archive: File,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> {
        recoverInterruptedInstall()
        if (!INSTALL_IN_PROGRESS.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException(HostStrings.get(R.string.rt_busy, "Another rootfs installation is in progress")))
        }
        try {
            return installArchiveLocked(manifest, archive, onProgress)
        } finally {
            INSTALL_IN_PROGRESS.set(false)
        }
    }

    private fun installArchiveLocked(
        manifest: RootfsManifest,
        archive: File,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> {
        layout.ensureDirectories()
        val staging = RootfsNaming.stagingDir(layout.cacheDir, manifest.runtimeVersion)
        val previous = previousRootfsDir()
        val journal = journalFile()
        var swapped = false
        try {
            if (manifest.sha256.isNotBlank()) {
                onProgress(InstallProgress(HostStrings.get(R.string.rt_verify, "Verifying rootfs SHA-256…"), 0.52f))
                val actual = calculateSha256(archive)
                if (!actual.equals(manifest.sha256.removePrefix("sha256:"), ignoreCase = true)) {
                    return Result.failure(
                        IllegalStateException(HostStrings.get(R.string.rt_sha_mismatch, "Rootfs SHA-256 mismatch: expected %1\$s, got %2\$s", manifest.sha256, actual))
                    )
                }
            }

            // 新旧两份 rootfs 要同时存在一段时间：空间不够时在动旧环境之前就失败。
            val required = estimatedUnpackedBytes(archive) + SPACE_MARGIN_BYTES
            val usable = layout.baseDir.usableSpace
            if (usable in 1 until required) {
                return Result.failure(
                    IllegalStateException(
                        HostStrings.get(R.string.rt_no_space, "Not enough storage: about %1\$d MB needed, %2\$d MB available. The current runtime was left unchanged.", required / MIB, usable / MIB)
                    )
                )
            }

            onProgress(InstallProgress(HostStrings.get(R.string.rt_extract, "Extracting rootfs…"), 0.55f))
            SafeFiles.deleteTree(staging)
            staging.mkdirs()
            extractTarGz(archive, staging) { fraction ->
                onProgress(InstallProgress(HostStrings.get(R.string.rt_extract_pct, "Extracting rootfs… %1\$d%%", (fraction * 100).toInt()), 0.55f + fraction * 0.3f))
            }

            // rootfs 归档通常包含根目录内容；若多包了一层，则下钻。
            val source = unwrapSingleRoot(staging)
            if (!File(source, "etc/os-release").isFile && !File(source, "usr/bin/env").isFile) {
                SafeFiles.deleteTree(staging)
                return Result.failure(IllegalStateException(HostStrings.get(R.string.rt_incomplete, "The rootfs is incomplete: etc/os-release / usr/bin/env missing")))
            }

            onProgress(InstallProgress(HostStrings.get(R.string.rt_replace, "Replacing the rootfs directory…"), 0.9f))
            SafeFiles.deleteTree(previous)
            journal.parentFile?.mkdirs()
            SafeFiles.writeAtomically(journal, manifest.runtimeVersion)
            if (layout.rootfsDir.exists() && !layout.rootfsDir.renameTo(previous)) {
                throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Cannot back up the old rootfs", "Не удалось сохранить старый rootfs", "无法备份旧 rootfs"))
            }
            swapped = true
            if (!source.renameTo(layout.rootfsDir)) {
                // staging 与 rootfs 都在 baseDir 下，rename 正常不会失败；兜底复制。
                SafeFiles.copyTree(source, layout.rootfsDir)
            }
            SafeFiles.deleteTree(staging)
            layout.optDshDir.mkdirs()
            layout.dshHomeDir.mkdirs()

            val previousHome = File(previous, "root/.dsh")
            if (previousHome.isDirectory) {
                // 恢复旧 DSH_HOME（会话/凭证/设置），但 node_modules 单独合并：
                // 新镜像的 node_modules 带着新版 bridge 与官方依赖，旧目录整体盖回去会把
                // 新 bridge 弄丢（「行在、包不在」）；但旧目录里还有用户后来装的第三方插件，
                // 全排除又会把它们弄丢。所以：① 恢复时跳过 node_modules；② 恢复后把旧
                // node_modules 里**新镜像没有的**包（用户插件）补进来，同名以新镜像为准。
                // 失败会抛出并回滚，绝不在丢了用户数据的情况下继续。
                val nodeModulesRel = "profiles/web/node_modules"
                SafeFiles.copyTree(previousHome, layout.dshHomeDir, overwrite = true) { rel ->
                    rel == nodeModulesRel || rel.startsWith("$nodeModulesRel/")
                }
                val oldModules = File(previousHome, nodeModulesRel)
                val newModules = File(layout.dshHomeDir, nodeModulesRel)
                oldModules.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { pkg ->
                    val target = File(newModules, pkg.name)
                    if (!java.nio.file.Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        newModules.mkdirs()
                        if (java.nio.file.Files.isSymbolicLink(pkg.toPath())) {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Files.readSymbolicLink(pkg.toPath()),
                            )
                        } else if (pkg.isDirectory) {
                            SafeFiles.copyTree(pkg, target, overwrite = false)
                        }
                    }
                }
            }
            // 旧 profile 的 pnpm-lock 可能锁定旧版 DSH；删掉让它由新版重新解析。
            runCatching {
                File(layout.dshHomeDir, "profiles/web/pnpm-lock.yaml").delete()
            }

            onProgress(InstallProgress(HostStrings.get(R.string.rt_selfcheck, "Checking the runtime toolchain…"), 0.95f))
            val toolchain = verifyToolchainIntegrity()
            if (toolchain.isFailure) throw toolchain.exceptionOrNull() ?: IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Toolchain self-check failed", "Самопроверка инструментов не прошла", "工具链自检失败"))

            SafeFiles.writeAtomically(
                markerFile(),
                json.encodeToString(
                    InstallMarker.serializer(),
                    InstallMarker(
                        runtimeVersion = manifest.runtimeVersion,
                        baseDistro = manifest.baseDistro,
                        installedAt = System.currentTimeMillis(),
                        archiveBytes = archive.length(),
                        abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
                        rootfsName = RootfsNaming.ARCHIVE_PREFIX,
                        releaseTag = RootfsNaming.releaseTag(manifest.runtimeVersion),
                        androidPatched = manifest.androidPatched,
                    )
                )
            )
            // 提交点：标记已写，删除日志与旧 rootfs。
            journal.delete()
            SafeFiles.deleteTree(previous)
            onProgress(InstallProgress(HostStrings.get(R.string.rt_done, "Runtime installed"), 1.0f))
            return Result.success(Unit)
        } catch (e: Throwable) {
            SafeFiles.deleteTree(staging)
            if (swapped && previous.exists()) {
                SafeFiles.deleteTree(layout.rootfsDir)
                previous.renameTo(layout.rootfsDir)
            }
            if (!previous.exists()) journal.delete()
            return Result.failure(if (e is Exception) e else IllegalStateException(e))
        }
    }

    /**
     * 解包后大小的估计：gzip 尾部 ISIZE（原始大小 mod 2^32）与压缩包 3 倍取大，
     * 只用于空间预检；真实不足时解包会失败并回滚。
     */
    private fun estimatedUnpackedBytes(archive: File): Long =
        maxOf(gzipUncompressedSize(archive), archive.length() * 3)

    /** gzip ISIZE: несжатый размер mod 2^32 (для rootfs < 4 ГБ — точный). */
    private fun gzipUncompressedSize(archive: File): Long =
        runCatching {
            java.io.RandomAccessFile(archive, "r").use { raf ->
                if (raf.length() < 4) return@use 0L
                raf.seek(raf.length() - 4)
                val b = ByteArray(4)
                raf.readFully(b)
                (b[0].toLong() and 0xff) or
                    ((b[1].toLong() and 0xff) shl 8) or
                    ((b[2].toLong() and 0xff) shl 16) or
                    ((b[3].toLong() and 0xff) shl 24)
            }
        }.getOrDefault(0L)

    private fun download(
        manifest: RootfsManifest,
        target: File,
        onProgress: (Float) -> Unit,
    ): Result<Unit> {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        return try {
            val request = Request.Builder().url(manifest.downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException(HostStrings.get(R.string.rt_http_failed, "rootfs download failed: HTTP %1\$d", response.code)))
                }
                val body = response.body ?: return Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs download response is empty", "Пустой ответ при загрузке rootfs", "rootfs 下载响应为空")))
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (part.renameTo(target)) Result.success(Unit)
            else Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Failed to save the downloaded rootfs", "Не удалось сохранить загруженный rootfs", "rootfs 下载文件落盘失败")))
        } catch (e: Exception) {
            part.delete()
            Result.failure(e)
        }
    }

    private fun extractTarGz(archive: File, destination: File, onProgress: (Float) -> Unit) {
        // Прогресс считаем от несжатого размера (gzip ISIZE): раньше делили на размер
        // сжатого архива, и индикатор упирался в 100% задолго до конца распаковки.
        val totalBytes = maxOf(gzipUncompressedSize(archive), archive.length()).coerceAtLeast(1)
        FileInputStream(archive).use { fileIn ->
            GzipCompressorInputStream(fileIn).use { gzipIn ->
                TarArchiveInputStream(gzipIn).use { tar ->
                    var processed = 0L
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        // 归档常带 "./" 前缀；规范化后再做路径穿越校验。
                        val name = entry.name.replace('\\', '/')
                            .split('/')
                            .filter { it.isNotEmpty() && it != "." }
                            .joinToString("/")
                        if (name.isEmpty()) continue
                        val target = File(destination, name)
                        val canonicalDest = destination.canonicalPath + File.separator
                        if (!target.canonicalPath.startsWith(canonicalDest)) {
                            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an invalid path: $name", "Архив rootfs содержит недопустимый путь: $name", "rootfs 归档包含非法路径: $name"))
                        }
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                // 链接目标按 guest 语义解释（proot 把绝对路径映射到 rootfs 内）。
                                // 相对目标不得经 `..` 逃出 rootfs；App 侧所有树操作都不跟随链接
                                // （见 SafeFiles），所以绝对目标不会被 Android 侧误用。
                                if (!isSafeSymlinkTarget(name, entry.linkName)) {
                                    throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an escaping symlink: $name -> ${entry.linkName}", "Архив rootfs содержит выходящую наружу ссылку: $name -> ${entry.linkName}", "rootfs 归档包含越界符号链接: $name -> ${entry.linkName}"))
                                }
                                target.parentFile?.mkdirs()
                                Files.deleteIfExists(target.toPath())
                                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                            }
                            entry.isLink -> {
                                val linkName = entry.linkName.replace('\\', '/')
                                    .split('/')
                                    .filter { it.isNotEmpty() && it != "." }
                                    .joinToString("/")
                                val linkSource = File(destination, linkName)
                                if (!linkSource.canonicalPath.startsWith(canonicalDest)) {
                                    throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an escaping hard link: $name -> ${entry.linkName}", "Архив rootfs содержит выходящую наружу жёсткую ссылку: $name -> ${entry.linkName}", "rootfs 归档包含越界硬链接: $name -> ${entry.linkName}"))
                                }
                                if (linkSource.isFile) {
                                    target.parentFile?.mkdirs()
                                    linkSource.copyTo(target, overwrite = true)
                                    // 硬链接同样要恢复可执行位（pnpm 等二进制依赖它）。
                                    applyMode(target, entry.mode)
                                }
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var read: Int
                                    while (tar.read(buffer).also { read = it } != -1) {
                                        output.write(buffer, 0, read)
                                        processed += read
                                    }
                                }
                                applyMode(target, entry.mode)
                            }
                        }
                        onProgress((processed.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    /**
     * 相对符号链接从链接所在目录出发、按路径段解析，`..` 不得越过 rootfs 根；
     * 绝对链接在 guest 内总是落在 rootfs 里，允许。NUL 字节一律拒绝。
     */
    internal fun isSafeSymlinkTarget(entryName: String, linkName: String): Boolean {
        if (linkName.isEmpty() || linkName.contains('\u0000')) return false
        if (linkName.startsWith("/")) return true
        val stack = ArrayDeque(entryName.split('/').filter { it.isNotEmpty() }.dropLast(1))
        for (segment in linkName.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return false else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return true
    }

    private fun applyMode(file: File, mode: Int) {
        if (!file.isFile) return
        val executable = (mode and 0b001_001_001) != 0
        file.setExecutable(executable, false)
        file.setReadable(true, false)
        file.setWritable((mode and 0b010_000_000) != 0, true)
    }

    private fun unwrapSingleRoot(staging: File): File {
        val children = staging.listFiles().orEmpty().filterNot { it.name == "." || it.name == ".." }
        return if (children.size == 1 && children[0].isDirectory) children[0] else staging
    }

    private fun verifyToolchainIntegrity(): Result<Unit> {
        val requiredDirs = listOf(
            layout.rootfsDir,
            layout.optDshDir,
            layout.dshHomeDir,
            layout.cacheDir,
            layout.safDir
        )
        for (dir in requiredDirs) {
            if (!dir.exists()) {
                return Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Missing core runtime directory: ${dir.name}", "Нет основной папки среды: ${dir.name}", "缺少核心运行时目录: ${dir.name}")))
            }
        }
        return Result.success(Unit)
    }

    private fun digestMatches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return false
        return calculateSha256(file).equals(expected.removePrefix("sha256:"), ignoreCase = true)
    }

    private fun markerFile(): File = RootfsNaming.markerFile(layout.baseDir)

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 进程级安装锁：RootfsInstaller 会被多处各自 new 出来。 */
        val INSTALL_IN_PROGRESS = java.util.concurrent.atomic.AtomicBoolean(false)
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        const val MIB = 1024L * 1024
        /** 解包之外预留的空间（DSH_HOME 合并、日志、npm 缓存）。 */
        const val SPACE_MARGIN_BYTES = 256L * MIB
    }

    @Serializable
    internal data class InstallMarker(
        val runtimeVersion: String,
        val baseDistro: String,
        val installedAt: Long,
        val archiveBytes: Long,
        val schemaVersion: Int = RootfsNaming.SCHEMA_VERSION,
        val abi: String = "",
        val rootfsName: String = RootfsNaming.ARCHIVE_PREFIX,
        val releaseTag: String = "",
        /** 镜像是否已烧入 Android 补丁；旧标记没有该字段时为 false。 */
        val androidPatched: Boolean = false,
    )
}






