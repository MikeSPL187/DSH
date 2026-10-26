package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("safezip").toFile()

    @Test
    fun `обычный архив распаковывается`() {
        val dest = File(tempDir(), "out")
        val count = SafeZip.extract(zip("pkg/package.json" to "{}".toByteArray(), "pkg/lib/a.js" to "1".toByteArray()).inputStream(), dest)
        assertEquals(2, count)
        assertEquals("1", File(dest, "pkg/lib/a.js").readText())
    }

    @Test
    fun `выход из папки отклоняется`() {
        val base = tempDir()
        val dest = File(base, "out")
        for (bad in listOf("../evil.txt", "a/../../evil.txt", "/etc/evil", "a\\..\\evil")) {
            val failed = runCatching { SafeZip.extract(zip(bad to "x".toByteArray()).inputStream(), dest) }.isFailure
            assertTrue("принят путь $bad", failed)
        }
        assertFalse(File(base, "evil.txt").exists())
    }

    @Test
    fun `папка-ссылка наружу не даёт писать за пределы`() {
        val base = tempDir()
        val outside = File(base, "outside").apply { mkdirs() }
        val dest = File(base, "out").apply { mkdirs() }
        Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())
        val failed = runCatching { SafeZip.extract(zip("link/evil.txt" to "x".toByteArray()).inputStream(), dest) }.isFailure
        assertTrue(failed)
        assertFalse(File(outside, "evil.txt").exists())
    }

    @Test(expected = IOException::class)
    fun `zip-бомба останавливается на пределе`() {
        val big = ByteArray(1024 * 1024)
        SafeZip.extract(
            zip("a" to big, "b" to big).inputStream(),
            File(tempDir(), "out"),
            SafeZip.Limits(maxTotalBytes = 1536L * 1024),
        )
    }

    @Test(expected = IOException::class)
    fun `число записей ограничено`() {
        val entries = (1..5).map { "f$it" to ByteArray(1) }.toTypedArray()
        SafeZip.extract(zip(*entries).inputStream(), File(tempDir(), "out"), SafeZip.Limits(maxEntries = 4))
    }

    /** Восстановление копии: чужой архив ничего не пишет, секреты пропускаются. */
    @Test
    fun restoreRejectsForeignArchiveAndSkipsSecrets() = runBlocking {
        val root = tempDir()
        val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
        val tool = RuntimeBackupAndDiagnostics(layout)

        val foreign = zip("dsh_home/settings.json" to "{}".toByteArray(), "snapshot_meta.txt" to ByteArray(0))
        assertTrue(tool.restoreUserDataBackup(foreign.inputStream()).isFailure)
        assertFalse(File(layout.dshHomeDir, "settings.json").exists())

        val own = zip(
            "snapshot_meta.txt" to "DSH".toByteArray(),
            "dsh_home/settings.json" to "{}".toByteArray(),
            "dsh_home/auth.json" to "secret".toByteArray(),
            "other/file" to "x".toByteArray(),
        )
        assertEquals(1, tool.restoreUserDataBackup(own.inputStream()).getOrThrow())
        assertTrue(File(layout.dshHomeDir, "settings.json").isFile)
        assertFalse(File(layout.dshHomeDir, "auth.json").exists())
    }
}
