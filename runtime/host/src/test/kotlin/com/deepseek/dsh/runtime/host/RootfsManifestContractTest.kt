package com.deepseek.dsh.runtime.host

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
}
