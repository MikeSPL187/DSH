package com.deepseek.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Проверка и распаковка архива выпуска плагина (zipball GitHub).
 *
 * Путь установки один: скачать архив тега → проверить → разложить в профиль. Эти тесты закрепляют
 * границы безопасности: «не совпали имя пакета, версия или вход — отказ; пути не выходят из целевой папки».
 */
class BridgePluginArchiveTest {
    // Сообщения проверяются по английскому тексту: фиксируем язык, а не берём локаль машины.
    @org.junit.Before
    fun pinLanguage() {
        com.deepseek.dsh.core.model.Tr.language = { "en" }
    }


    private val packageName = "dsh-mobile-bridge-plugin"

    /**
     * G-42: хэш содержимого совпадает с `scripts/bridge-plugin-digest.py` (значение посчитано им для
     * дерева `a.txt` = "A\n", `lib/b.js` = "B") и меняется от правки любого файла.
     */
    @Test
    fun `content digest matches the pinning script and detects a changed file`() {
        val root = Files.createTempDirectory("bridge-digest").toFile()
        try {
            File(root, "a.txt").writeText("A\n")
            File(root, "lib").mkdirs()
            File(root, "lib/b.js").writeText("B")
            assertEquals("2dbeeb24566b8faf9a22a1d4b3bffddb00b754bb0089f7f6212f887e41c133f6", BridgePluginArchive.contentDigest(root))
            File(root, "lib/b.js").writeText("B; fetch('https://evil.example')")
            assertTrue(BridgePluginArchive.contentDigest(root) != "2dbeeb24566b8faf9a22a1d4b3bffddb00b754bb0089f7f6212f887e41c133f6")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extracts a single-root archive and reads its manifest`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo-1.1.0/package.json" to manifestJson("1.1.0"),
                "repo-1.1.0/cordis.patch.yml" to "- insert: []\n",
                "repo-1.1.0/lib/index.js" to "export default {};\n",
                "repo-1.1.0/lib/providers/client-slots.js" to "export {};\n",
            )
            val contents = BridgePluginArchive.extract(
                archive = archive.inputStream(),
                destination = destination,
                expectedPackage = packageName,
                expectedVersion = "1.1.0",
            )
            assertEquals(packageName, contents.packageName)
            assertEquals("1.1.0", contents.version)
            assertEquals("lib/index.js", contents.main)
            assertEquals("cordis.patch.yml", contents.patch)
            assertEquals(setOf("@deepseek-ai/schemastery"), contents.dependencies.keys)
            assertTrue(File(destination, "lib/providers/client-slots.js").isFile)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects an archive for another version`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo-1.2.0/package.json" to manifestJson("1.2.0"),
                "repo-1.2.0/cordis.patch.yml" to "- insert: []\n",
                "repo-1.2.0/lib/index.js" to "export default {};\n",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                BridgePluginArchive.extract(archive.inputStream(), destination, packageName, "1.1.0")
            }
            assertTrue(error.message.orEmpty().contains("Version mismatch"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects an archive for another package`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo/package.json" to manifestJson("1.1.0").replace(packageName, "other-plugin"),
                "repo/cordis.patch.yml" to "- insert: []\n",
                "repo/lib/index.js" to "export default {};\n",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                BridgePluginArchive.extract(archive.inputStream(), destination, packageName, "1.1.0")
            }
            assertTrue(error.message.orEmpty().contains("Package mismatch"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects an archive without a bundle patch`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo/package.json" to manifestJson("1.1.0").replace(
                    "\"dsh\": { \"bundle\": { \"patch\": \"./cordis.patch.yml\" } },",
                    "",
                ),
                "repo/lib/index.js" to "export default {};\n",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                BridgePluginArchive.extract(archive.inputStream(), destination, packageName, "1.1.0")
            }
            assertTrue(error.message.orEmpty().contains("dsh.bundle.patch"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects an archive whose patch file is missing`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo/package.json" to manifestJson("1.1.0"),
                "repo/lib/index.js" to "export default {};\n",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                BridgePluginArchive.extract(archive.inputStream(), destination, packageName, "1.1.0")
            }
            assertTrue(error.message.orEmpty().contains("bundle patch"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects entries that escape the destination`() {
        val destination = Files.createTempDirectory("bridge-archive").toFile()
        try {
            val archive = zip(
                "repo/package.json" to manifestJson("1.1.0"),
                "repo/cordis.patch.yml" to "- insert: []\n",
                "repo/lib/index.js" to "export default {};\n",
                "repo/../../escape.txt" to "nope\n",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                BridgePluginArchive.extract(archive.inputStream(), destination, packageName, "1.1.0")
            }
            assertTrue(error.message.orEmpty().contains("escapes the root"))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `builds a codeload archive url for a released version only`() {
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/tags/v1.1.0",
            BridgeReleaseDownloader.archiveUrl("https://github.com/owner/repo", "1.1.0"),
        )
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/tags/v1.1.0",
            BridgeReleaseDownloader.archiveUrl("https://github.com/owner/repo.git/", "1.1.0"),
        )
        assertNull(BridgeReleaseDownloader.archiveUrl("https://github.com/owner/repo", "main"))
        assertNull(BridgeReleaseDownloader.archiveUrl("https://github.com/owner/repo", "1.1.0;rm"))
        assertNull(BridgeReleaseDownloader.archiveUrl("https://example.com/owner/repo", "1.1.0"))
        assertEquals("owner/repo", BridgeReleaseDownloader.repositorySlug("git@github.com:owner/repo.git"))
    }

    /** Минимальный манифест плагина; поля как у настоящего пакета выпуска. */
    private fun manifestJson(version: String): String = """
        {
          "name": "$packageName",
          "version": "$version",
          "type": "module",
          "main": "lib/index.js",
          "dsh": { "bundle": { "patch": "./cordis.patch.yml" } },
          "dependencies": { "@deepseek-ai/schemastery": "^3.18.5" }
        }
    """.trimIndent()

    /** Упаковать несколько путей и содержимое в zip. */
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
