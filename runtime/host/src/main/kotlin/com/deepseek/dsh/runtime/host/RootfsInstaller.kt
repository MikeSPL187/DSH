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

/** Ход установки: 0..1 и подпись шага для пользователя. */
data class InstallProgress(
    val step: String,
    val progress: Float
)

/**
 * Контракт `rootfs-manifest.json`: настоящие адреса образа и контрольные суммы пишет конвейер выпуска,
 * приложение только скачивает, проверяет, распаковывает и ставит метку установки.
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
    /** ABI → путь архива rootfs, встроенного в assets (необязательно, в основном для отладки без сети). */
    val assetArchives: Map<String, String> = emptyMap(),
    /** ABI → SHA-256 архива. */
    val assetSha256: Map<String, String> = emptyMap(),
    /** ABI → адрес первой загрузки (для выпуска; большого rootfs в приложении больше нет). */
    val downloadUrls: Map<String, String> = emptyMap(),
    /** ABI → SHA-256 скачанного архива. */
    val downloadSha256: Map<String, String> = emptyMap(),
    val downloadUrl: String = "",
    val sha256: String = "",
    val archiveSizeBytes: Long = 0L,
    val prootLibrary: String = "libproot.so",
    /**
     * Применены ли патчи окружения Android уже при сборке rootfs.
     *
     * Если да, часть DSH образа не обязана совпадать с целью полнофайловых патчей этого APK: файлы
     * в образе уже «последний DSH + патчи», приложение просто пропускает замену при запуске.
     */
    val androidPatched: Boolean = false,
)

/**
 * Конвейер установки rootfs:
 * загрузка (можно пропустить) → проверка SHA-256 → проверка места → распаковка в staging (старый rootfs не трогаем) →
 * старая папка переименовывается в резерв → новая встаёт на место → слияние DSH_HOME → проверка инструментов → метка установки
 * → удаление резерва. Ошибка любого шага (и убийство процесса с продолжением при следующем запуске) возвращает старый rootfs (аудит F-02/N-08).
 *
 * При разработке образ можно положить в `cache/rootfs-local.tar.gz` (без сети); если в манифесте
 * `sha256` пуст, проверка пропускается, но в журнал пишется предупреждение.
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

    fun installedVersion(): String? {
        val marker = markerFile()
        if (!marker.isFile) return null
        return runCatching { json.decodeFromString<InstallMarker>(marker.readText()).runtimeVersion }
            .getOrNull()
    }

    /** Прочитать метку установки; нет или повреждена — null. */
    internal fun readMarker(): InstallMarker? {
        val marker = markerFile()
        if (!marker.isFile) return null
        return runCatching { json.decodeFromString<InstallMarker>(marker.readText()) }.getOrNull()
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

    /** Установить переданный архив (из assets или через SAF) по контракту манифеста. */
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

    /** Установить по манифесту; `cache/rootfs-local.tar.gz` — в первую очередь для отладки без сети. */
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
                // Сетевой источник — только HTTPS и с SHA-256: удалённый манифест без контрольной суммы не ставится.
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

    /** Журнал незавершённой установки (есть файл — установка не закончена). */
    private fun journalFile(): File = File(layout.runtimeMetaDir, "rootfs-install.pending")

    /** Старый rootfs, сохранённый на время замены. */
    private fun previousRootfsDir(): File = File(layout.baseDir, "${RootfsNaming.INSTALL_DIR}.previous")

    /**
     * Если прошлая установка оборвалась на замене (kill, отключение питания), вернуть старый rootfs.
     * Метка установки пишется последней, поэтому при наличии журнала текущий rootfs точно не полная новая версия.
     */
    internal fun recoverInterruptedInstall() {
        // Пока установка идёт в этом процессе, журнал и резерв — нормальное состояние «в процессе», откатывать нельзя.
        if (INSTALL_IN_PROGRESS.get()) return
        // Распаковка, прерванная до свапа (kill, выключение), оставляла staging на сотни МБ
        // в cache. Повторная установка той же версии его чистила, другой — нет.
        layout.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith(RootfsNaming.STAGING_PREFIX) }
            ?.forEach { SafeFiles.deleteTree(it) }
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

            // Старый и новый rootfs какое-то время существуют вместе: если места мало — ошибка до того, как тронем старое окружение.
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

            // Архив rootfs обычно содержит корень; если есть лишний уровень — спускаемся.
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
                throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Cannot back up the old rootfs", "Не удалось сохранить старый rootfs"))
            }
            swapped = true
            if (!source.renameTo(layout.rootfsDir)) {
                // staging и rootfs оба в baseDir, rename обычно не падает; запасной путь — копирование.
                SafeFiles.copyTree(source, layout.rootfsDir)
            }
            SafeFiles.deleteTree(staging)
            layout.optDshDir.mkdirs()
            layout.dshHomeDir.mkdirs()

            val previousHome = File(previous, "root/.dsh")
            if (previousHome.isDirectory) {
                // Вернуть старый DSH_HOME (сессии, учётные данные, настройки), но node_modules слить отдельно:
                // node_modules нового образа несёт новый мост и официальные зависимости, и возврат старой папки целиком
                // потерял бы новый мост («строка есть, пакета нет»); но в старой папке есть и сторонние плагины пользователя,
                // и полное исключение потеряло бы их. Поэтому: 1) при возврате node_modules пропускается; 2) затем из старой
                // node_modules добавляются пакеты, **которых нет в новом образе** (плагины пользователя), при совпадении имён побеждает новый образ.
                // Ошибка — исключение и откат: продолжать с потерянными данными пользователя нельзя.
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
            // pnpm-lock старого профиля может зафиксировать старый DSH; удаляем, новый разберёт заново.
            runCatching {
                File(layout.dshHomeDir, "profiles/web/pnpm-lock.yaml").delete()
            }

            onProgress(InstallProgress(HostStrings.get(R.string.rt_selfcheck, "Checking the runtime toolchain…"), 0.95f))
            val toolchain = verifyToolchainIntegrity()
            if (toolchain.isFailure) throw toolchain.exceptionOrNull() ?: IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Toolchain self-check failed", "Самопроверка инструментов не прошла"))

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
            // Точка фиксации: метка записана, журнал и старый rootfs удаляются.
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
     * Оценка размера после распаковки: большее из хвоста gzip ISIZE (исходный размер mod 2^32) и тройного размера архива;
     * только для проверки места — если места реально мало, распаковка упадёт и откатится.
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
                val body = response.body ?: return Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs download response is empty", "Пустой ответ при загрузке rootfs")))
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
            else Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Failed to save the downloaded rootfs", "Не удалось сохранить загруженный rootfs")))
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
                        // В архивах часто префикс "./"; сначала нормализуем, затем проверяем выход за пределы.
                        val name = entry.name.replace('\\', '/')
                            .split('/')
                            .filter { it.isNotEmpty() && it != "." }
                            .joinToString("/")
                        if (name.isEmpty()) continue
                        val target = File(destination, name)
                        val canonicalDest = destination.canonicalPath + File.separator
                        if (!target.canonicalPath.startsWith(canonicalDest)) {
                            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an invalid path: $name", "Архив rootfs содержит недопустимый путь: $name"))
                        }
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                // Цель ссылки — в смысле гостя (proot отображает абсолютные пути внутрь rootfs).
                                // Относительная цель не может выйти из rootfs через `..`; все операции с деревом на стороне приложения по ссылкам не ходят
                                // (см. SafeFiles), поэтому абсолютная цель на стороне Android не используется по ошибке.
                                if (!isSafeSymlinkTarget(name, entry.linkName)) {
                                    throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an escaping symlink: $name -> ${entry.linkName}", "Архив rootfs содержит выходящую наружу ссылку: $name -> ${entry.linkName}"))
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
                                    throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs archive contains an escaping hard link: $name -> ${entry.linkName}", "Архив rootfs содержит выходящую наружу жёсткую ссылку: $name -> ${entry.linkName}"))
                                }
                                if (linkSource.isFile) {
                                    target.parentFile?.mkdirs()
                                    linkSource.copyTo(target, overwrite = true)
                                    // Жёстким ссылкам тоже возвращается бит исполнения (от него зависят pnpm и другие программы).
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
     * Относительная ссылка разбирается от своей папки по сегментам, `..` не может выйти за корень rootfs;
     * абсолютная внутри гостя всегда попадает в rootfs — разрешена. Байты NUL отклоняются всегда.
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
                return Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Missing core runtime directory: ${dir.name}", "Нет основной папки среды: ${dir.name}")))
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
        /** Блокировка установки на процесс: RootfsInstaller создаётся в нескольких местах отдельно. */
        val INSTALL_IN_PROGRESS = java.util.concurrent.atomic.AtomicBoolean(false)
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        const val MIB = 1024L * 1024
        /** Запас места сверх распаковки (слияние DSH_HOME, журналы, кэш npm). */
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
        /** Вшиты ли в образ патчи Android; в старой метке поля нет — false. */
        val androidPatched: Boolean = false,
    )
}






