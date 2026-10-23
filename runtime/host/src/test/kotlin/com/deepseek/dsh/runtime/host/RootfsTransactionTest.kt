package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Audit F-02/N-08 + F-12: rootfs replacement is transactional and never follows links outward. */
class RootfsTransactionTest {

    private fun tempRoot(): File = Files.createTempDirectory("rootfs-tx").toFile()

    private fun archive(
        file: File,
        withOsRelease: Boolean = true,
        symlink: Pair<String, String>? = null,
        marker: String = "new",
    ): File {
        FileOutputStream(file).use { out ->
            GzipCompressorOutputStream(out).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    fun put(name: String, content: String) {
                        val bytes = content.toByteArray()
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 0b110_100_100 })
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    if (withOsRelease) put("etc/os-release", "ID=debian\n")
                    put("usr/bin/env", "#!/bin/sh\n")
                    put("etc/image", marker)
                    symlink?.let { (name, target) ->
                        tar.putArchiveEntry(
                            TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = target },
                        )
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return file
    }

    @Test
    fun deleteTreeDoesNotFollowSymlinkedDirectories() {
        val root = tempRoot()
        try {
            val outside = File(root, "shared-storage").apply { mkdirs() }
            File(outside, "photo.jpg").writeText("precious")
            val tree = File(root, "rootfs/root").apply { mkdirs() }
            Files.createSymbolicLink(File(tree, "storage").toPath(), outside.toPath())

            assertTrue(SafeFiles.deleteTree(File(root, "rootfs")))
            assertFalse(File(root, "rootfs").exists())
            assertTrue("link target must survive", File(outside, "photo.jpg").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun upgradePreservesHomeAndReplacesImage() = runBlocking {
        val root = tempRoot()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
            val installer = RootfsInstaller(layout)
            assertTrue(installer.verifyAndInstall(archive(File(root, "v1.tar.gz"), marker = "old")) {}.isSuccess)
            File(layout.dshHomeDir, "sessions.db").writeText("user-data")

            val result = installer.verifyAndInstall(archive(File(root, "v2.tar.gz"), marker = "new")) {}
            assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
            assertEquals("new", File(layout.rootfsDir, "etc/image").readText())
            assertEquals("user-data", File(layout.dshHomeDir, "sessions.db").readText())
            assertFalse(File(root, "rootfs.previous").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun brokenArchiveKeepsOldEnvironment() = runBlocking {
        val root = tempRoot()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
            val installer = RootfsInstaller(layout)
            assertTrue(installer.verifyAndInstall(archive(File(root, "v1.tar.gz"), marker = "old")) {}.isSuccess)
            File(layout.dshHomeDir, "sessions.db").writeText("user-data")

            // Incomplete image and a corrupt gzip: both must fail without touching the old rootfs.
            assertTrue(installer.verifyAndInstall(archive(File(root, "bad.tar.gz"), withOsRelease = false).also {
                File(root, "bad.tar.gz").delete(); it.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }) {}.isFailure)
            val incomplete = File(root, "incomplete.tar.gz")
            FileOutputStream(incomplete).use { out ->
                GzipCompressorOutputStream(out).use { gz ->
                    TarArchiveOutputStream(gz).use { tar ->
                        val bytes = "x".toByteArray()
                        tar.putArchiveEntry(TarArchiveEntry("readme").apply { size = 1 })
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
            assertTrue(installer.verifyAndInstall(incomplete) {}.isFailure)

            assertEquals("old", File(layout.rootfsDir, "etc/image").readText())
            assertEquals("user-data", File(layout.dshHomeDir, "sessions.db").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun wrongDigestIsRejectedBeforeAnyChange() = runBlocking {
        val root = tempRoot()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
            val installer = RootfsInstaller(layout)
            assertTrue(installer.verifyAndInstall(archive(File(root, "v1.tar.gz"), marker = "old")) {}.isSuccess)
            val result = installer.verifyAndInstall(archive(File(root, "v2.tar.gz")), expectedSha256 = "0".repeat(64)) {}
            assertTrue(result.isFailure)
            assertEquals("old", File(layout.rootfsDir, "etc/image").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedSwapIsRolledBackOnNextStart() {
        val root = tempRoot()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
            // Simulate a kill right after the old rootfs was moved aside and a partial new one appeared.
            File(root, "rootfs.previous/etc").mkdirs()
            File(root, "rootfs.previous/etc/image").writeText("old")
            File(layout.rootfsDir, "etc").mkdirs()
            File(layout.rootfsDir, "etc/image").writeText("partial")
            File(layout.runtimeMetaDir, "rootfs-install.pending").apply { parentFile!!.mkdirs(); writeText("v2") }

            RootfsInstaller(layout).recoverInterruptedInstall()

            assertEquals("old", File(layout.rootfsDir, "etc/image").readText())
            assertFalse(File(root, "rootfs.previous").exists())
            assertFalse(File(layout.runtimeMetaDir, "rootfs-install.pending").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkEscapingRootIsRejected() = runBlocking {
        val root = tempRoot()
        try {
            val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
            val installer = RootfsInstaller(layout)
            val evil = archive(File(root, "evil.tar.gz"), symlink = "usr/lib/x" to "../../../../../outside")
            assertTrue(installer.verifyAndInstall(evil) {}.isFailure)
            val ok = archive(File(root, "ok.tar.gz"), symlink = "lib" to "usr/lib")
            assertTrue(installer.verifyAndInstall(ok) {}.isSuccess)
            assertTrue(Files.isSymbolicLink(File(layout.rootfsDir, "lib").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkTargetPolicy() {
        val installer = RootfsInstaller(RuntimeLayout(File("/nonexistent"), File("/nonexistent")))
        assertTrue(installer.isSafeSymlinkTarget("lib", "usr/lib"))
        assertTrue(installer.isSafeSymlinkTarget("usr/bin/python", "python3.11"))
        assertTrue(installer.isSafeSymlinkTarget("usr/lib/a/b", "../../share/x"))
        assertTrue(installer.isSafeSymlinkTarget("etc/localtime", "/usr/share/zoneinfo/UTC"))
        assertFalse(installer.isSafeSymlinkTarget("usr/x", "../../escape"))
        assertFalse(installer.isSafeSymlinkTarget("x", "a\u0000b"))
        assertFalse(installer.isSafeSymlinkTarget("x", ""))
    }
}
