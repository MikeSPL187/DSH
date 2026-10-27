package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Согласованность встроенного манифеста APK и вида строки версии.
 *
 * Здесь сходятся три репозитория: тег выпуска репозитория плагина, `runtimeVersion`,
 * записанный скриптом сборки rootfs, и `rootfs-manifest.json` в APK. Расхождение в любом месте сломает
 * решение «плагин обязателен / образ необязателен», поэтому на стороне клиента это закреплено.
 */
class RootfsManifestContractTest {

    /** Найти файл от корня репозитория (работает и при запуске тестов из папки модуля). */
    private fun locate(relative: String): File? {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun `the bundled rootfs manifest declares all three version segments`() {
        val file = locate("app/src/main/assets/rootfs-manifest.json")
            ?: return // При сборке только этого модуля файл не обязателен.
        val text = file.readText()
        val version = RootfsVersion.parse(
            Regex("\"runtimeVersion\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1),
        )
        assertNotNull("runtimeVersion must parse into segments", version)
        assertNotNull("the rootfs segment must be present", version!!.rootfs)
        assertNotNull("the dsh segment must be present", version.dsh)
        assertNotNull("the bridge segment must be present", version.bridge)
    }

    @Test
    fun `the bridge version in the manifest agrees with its own field`() {
        val file = locate("app/src/main/assets/rootfs-manifest.json") ?: return
        val text = file.readText()
        val runtimeVersion = Regex("\"runtimeVersion\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val bridgeField = Regex("\"bridgeVersion\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val parsed = RootfsVersion.parse(runtimeVersion)!!
        assertEquals(
            "bridgeVersion must match the -bridge segment of runtimeVersion",
            parsed.bridge,
            bridgeField,
        )
    }

    @Test
    fun `the bundled plugin manifest names the repository the client installs from`() {
        val file = locate("app/src/main/assets/bridge-plugin.json") ?: return
        val text = file.readText()
        val packageName = Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val repository = Regex("\"repository\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertEquals("dsh-mobile-bridge-plugin", packageName)
        assertNotNull(repository)
        assertTrue(
            "the repository must be a GitHub URL the installer can append a tag to",
            repository!!.startsWith("https://github.com/"),
        )
        assertTrue("the version must be a plain semver triple", Regex("""^\d+\.\d+\.\d+$""").matches(version!!))
    }

    @Test
    fun `the plugin manifest version is never older than the version baked into the image`() {
        // APK может быть новее образа: плагин обновляется на месте, и копия клиента обычно опережает
        // следующую сборку образа. Наоборот нельзя: клиент с плагином старее образа сразу после запуска
        // сообщил бы «плагин нужно обновить».
        val rootfs = locate("app/src/main/assets/rootfs-manifest.json") ?: return
        val plugin = locate("app/src/main/assets/bridge-plugin.json") ?: return
        val imageBridge = RootfsVersion.parse(
            Regex("\"runtimeVersion\"\\s*:\\s*\"([^\"]+)\"").find(rootfs.readText())?.groupValues?.get(1),
        )?.bridge ?: return
        val manifestBridge = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(plugin.readText())?.groupValues?.get(1)
        assertNotNull(manifestBridge)
        assertTrue(
            "the APK's plugin version $manifestBridge must not be older than the image's $imageBridge",
            compareVersions(manifestBridge!!, imageBridge) >= 0,
        )
    }

    /** Сравнение `major.minor.patch` по частям; неразбираемая часть — 0. */
    private fun compareVersions(left: String, right: String): Int {
        val a = left.split(".").map { it.toIntOrNull() ?: 0 }
        val b = right.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
            if (delta != 0) return delta
        }
        return 0
    }

    /**
     * Копия плагина в `third_party/bridge-plugin` — то, что уходит в образ и в архив релиза: zip с одной верхней
     * папкой (как собирает `infra/bridge-plugin-zip.py`) проходит распаковку приложения, и хэш содержимого
     * совпадает с `contentSha256` из APK.
     */
    @Test
    fun `the vendored bridge plugin unpacks and matches the pinned content hash`() {
        val plugin = locate("app/src/main/assets/bridge-plugin.json") ?: return
        val source = locate("third_party/bridge-plugin/package.json")?.parentFile ?: return
        val text = plugin.readText()
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        val pinned = Regex("\"contentSha256\"\\s*:\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            source.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach { file ->
                out.putNextEntry(java.util.zip.ZipEntry("dsh-mobile-bridge-plugin-$version/" + file.relativeTo(source).invariantSeparatorsPath))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
        val destination = kotlin.io.path.createTempDirectory("bridge").toFile()
        try {
            BridgePluginArchive.extract(zip.toByteArray().inputStream(), destination, "dsh-mobile-bridge-plugin", version)
            assertEquals(pinned, BridgePluginArchive.contentDigest(destination))
        } finally {
            destination.deleteRecursively()
        }
    }

    /** G-41, G-42: всё, что ставится в Linux из сети, закреплено хэшем в APK. */
    @Test
    fun `the bundled manifests pin every download with sha256`() {
        val rootfs = locate("app/src/main/assets/rootfs-manifest.json") ?: return
        assertNotNull("every rootfs archive must be https with a pinned SHA-256", RootfsManifestSource.parseBundledManifest(rootfs.readText()))
        val plugin = locate("app/src/main/assets/bridge-plugin.json") ?: return
        val sha = Regex("\"contentSha256\"\\s*:\\s*\"([^\"]*)\"").find(plugin.readText())?.groupValues?.get(1).orEmpty()
        assertTrue("bridge-plugin.json must pin contentSha256", Regex("^[0-9a-f]{64}$").matches(sha))
    }

    @Test
    fun `a manifest without a pinned hash is refused`() {
        val unpinned = """{"runtimeVersion":"1.0.11-dsh0.2.1-rc.2-bridge2.0.7","downloadUrls":{"arm64-v8a":"https://example.com/r.bin"}}"""
        assertEquals(null, RootfsManifestSource.parseBundledManifest(unpinned))
        val http = """{"runtimeVersion":"1","downloadUrls":{"x":"http://example.com/r.bin"},"downloadSha256":{"x":"${"a".repeat(64)}"}}"""
        assertEquals(null, RootfsManifestSource.parseBundledManifest(http))
    }
}
