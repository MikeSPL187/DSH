package com.deepseek.dsh.feature.settings.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** G-10: очистка не идёт по ссылкам, не трогает временную папку Host и чистит журналы только в папке журналов. */
class StorageCleanTest {
    @Test
    fun cacheCleanDoesNotFollowLinksOrTouchProotTmp() {
        val root = Files.createTempDirectory("storage").toFile()
        val outside = File(root, "outside").apply { mkdirs(); File(this, "keep.txt").writeText("user data") }
        val cache = File(root, "cache").apply { mkdirs() }
        File(cache, "junk.bin").writeText("x".repeat(100))
        File(cache, "proot-tmp").mkdirs(); File(cache, "proot-tmp/live").writeText("in use")
        Files.createSymbolicLink(File(cache, "link").toPath(), outside.toPath())

        StorageScanner.cleanCache(listOf(cache))
        assertTrue(File(outside, "keep.txt").isFile)
        assertTrue(File(cache, "proot-tmp/live").isFile)
        assertEquals(setOf("proot-tmp"), cache.list()!!.toSet())
    }

    @Test
    fun onlyOldFilesOfTheLogsFolderAreLogs() {
        val root = Files.createTempDirectory("logs").toFile()
        val logs = File(root, "logs").apply { mkdirs() }
        val old = System.currentTimeMillis() - 30L * 86_400_000
        File(logs, "host_1.log").apply { writeText("a"); setLastModified(old) }
        File(logs, "host_2.log").writeText("fresh")
        // Журналы сессий Host в другой папке — не наши.
        File(root, "rootfs/root/.dsh/sessions/logs").mkdirs()
        File(root, "rootfs/root/.dsh/sessions/logs/s.log").apply { writeText("session"); setLastModified(old) }
        assertEquals(listOf("host_1.log"), StorageScanner.oldLogs(logs).map { it.name })
    }
}
