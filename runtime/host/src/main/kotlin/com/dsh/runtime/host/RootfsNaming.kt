package com.dsh.runtime.host

import java.io.File

/**
 * Имена rootfs (единственный источник правды).
 *
 * Папка: files/rootfs
 * Метка: files/runtime/rootfs-installed.json
 * Кэш: files/cache/dsh-rootfs-<runtimeVersion>-<abi>.tar.gz
 * Подготовка: files/cache/dsh-rootfs-staging-<runtimeVersion>
 * Выпуск: rootfs-v<runtimeVersion> / dsh-rootfs-<runtimeVersion>-<abi>.tar.gz
 *
 * При обновлении новый runtimeVersion — новый rootfs; смена ABI (другой телефон, восстановление, новый Android)
 * требует переустановки, чтобы не использовать rootfs другой архитектуры.
 */
object RootfsNaming {
    const val SCHEMA_VERSION = 1
    const val INSTALL_DIR = "rootfs"
    const val MARKER_FILE = "rootfs-installed.json"
    const val MANIFEST_ASSET = "rootfs-manifest.json"
    /** Кэш последнего удачно загруженного манифеста; сам манифест в APK не входит. */
    const val MANIFEST_CACHE_FILE = "rootfs-manifest-cache.json"
    const val ARCHIVE_PREFIX = "dsh-rootfs"
    const val RELEASE_TAG_PREFIX = "rootfs-v"

    fun markerFile(baseDir: File): File = File(baseDir, "runtime/$MARKER_FILE")

    fun releaseTag(runtimeVersion: String): String =
        if (runtimeVersion.startsWith(RELEASE_TAG_PREFIX)) runtimeVersion
        else "$RELEASE_TAG_PREFIX$runtimeVersion"

    fun archiveFileName(runtimeVersion: String, abi: String): String =
        "$ARCHIVE_PREFIX-$runtimeVersion-$abi.tar.gz"

    fun archiveCacheFile(cacheDir: File, runtimeVersion: String, abi: String): File =
        File(cacheDir, archiveFileName(runtimeVersion.replace('/', '-'), abi.replace('/', '-')))

    const val STAGING_PREFIX = "dsh-rootfs-staging-"

    fun stagingDir(cacheDir: File, runtimeVersion: String): File =
        File(cacheDir, STAGING_PREFIX + runtimeVersion.replace('/', '-'))

    /**
     * Часть версии DSH из runtimeVersion.
     *
     * Использует единственный разбор [RootfsVersion]; неверный формат — null.
     * @param runtimeVersion полная строка версии rootfs.
     * @returns версия DSH, например `0.2.1-rc.2`.
     */
    fun dshVersion(runtimeVersion: String): String? = RootfsVersion.parse(runtimeVersion)?.dsh

    /**
     * Совместима ли версия DSH в rootfs с целью патчей текущего APK.
     *
     * Патчи rootfs в APK — **замена файлов целиком**, сделанная под конкретную версию DSH; патчи rc.2 на
     * rootfs rc.3 смешали бы версии в рабочем дереве. Поэтому удалённый или кэшированный манифест годится, только если
     * часть DSH совпадает со встроенным в APK. Без встроенного решить нельзя — прежнее поведение.
     * @param runtimeVersion проверяемая версия rootfs.
     * @param expectedDsh часть DSH встроенного манифеста; null — решить нельзя.
     * @returns true, если подходит.
     */
    fun isDshCompatible(runtimeVersion: String?, expectedDsh: String?): Boolean {
        if (expectedDsh.isNullOrBlank()) return true
        val actual = runtimeVersion?.let(::dshVersion) ?: return false
        return actual == expectedDsh
    }
}

