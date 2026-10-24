package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 本地目录安装（无网络）：验证包名校验、patch 预校验与失败时保留旧版本。
 */
class PluginBundleInstallerTest {

    private class Fixture(val root: File) {
        val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = root)
        val workspace = File(root, "workspace-public").apply { mkdirs() }
        val nodeModules = File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules")
        val webAppPatch = File(
            layout.rootfsDir,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml",
        )
        val installer = PluginBundleInstaller(
            context = android.content.ContextWrapper(null),
            layout = layout,
            workspaceDir = { workspace },
        )

        init {
            nodeModules.mkdirs()
            webAppPatch.parentFile!!.mkdirs()
            webAppPatch.writeText("[]\n")
        }

        fun writePackage(dir: String, name: String, patch: String? = "cordis.patch.yml", version: String = "1.0.0"): String {
            val packageDir = File(workspace, dir).apply { mkdirs() }
            val patchJson = if (patch == null) "" else """, "dsh": { "bundle": { "patch": "$patch" } }"""
            File(packageDir, "package.json").writeText(
                """{ "name": "$name", "version": "$version"$patchJson }""",
            )
            File(packageDir, "cordis.patch.yml").writeText("- id: ${name.replace('/', '-')}\n  name: $name\n")
            File(packageDir, "index.js").writeText("module.exports = '$version'\n")
            return "/workspace/$dir"
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("plugin-installer-test").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installsLocalPackageFromBoundWorkspaceAndMountsPatch() = withFixture { f ->
        val spec = f.writePackage("good", "dsh-plugin-good")
        val result = runBlocking { f.installer.install(spec) { _, _ -> } }
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(File(f.nodeModules, "dsh-plugin-good/index.js").isFile)
        assertTrue(f.webAppPatch.readText().contains("# android-installed: dsh-plugin-good"))
        assertEquals(setOf("dsh-plugin-good"), f.installer.managedPackages())
    }

    @Test
    fun rejectsTraversalPackageNameWithoutTouchingOutsideFiles() = withFixture { f ->
        // Audit F-01: a malicious package.json name must not reach File(nodeModules, name).deleteRecursively().
        val victim = File(f.layout.rootfsDir, "root/.dsh/victim").apply { mkdirs() }
        File(victim, "keep.txt").writeText("important")
        val spec = f.writePackage("evil", "../../victim")
        val result = runBlocking { f.installer.install(spec) { _, _ -> } }
        assertTrue(result.isFailure)
        assertTrue(File(victim, "keep.txt").isFile)
    }

    @Test
    fun rejectsPatchPathOutsidePackage() = withFixture { f ->
        val spec = f.writePackage("escape", "dsh-plugin-escape", patch = "../../../../etc/passwd")
        val result = runBlocking { f.installer.install(spec) { _, _ -> } }
        assertTrue(result.isFailure)
        assertFalse(File(f.nodeModules, "dsh-plugin-escape").exists())
    }

    @Test
    fun failedReinstallKeepsPreviousVersion() = withFixture { f ->
        // Audit F-05/N-09: the old plugin must survive an update that fails validation.
        val v1 = f.writePackage("v1", "dsh-plugin-keep", version = "1.0.0")
        assertTrue(runBlocking { f.installer.install(v1) { _, _ -> } }.isSuccess)
        val broken = f.writePackage("v2", "dsh-plugin-keep", patch = "missing.yml", version = "2.0.0")
        val result = runBlocking { f.installer.install(broken) { _, _ -> } }
        assertTrue(result.isFailure)
        assertEquals("module.exports = '1.0.0'\n", File(f.nodeModules, "dsh-plugin-keep/index.js").readText())
    }

    @Test
    fun failedMountRollsBackToPreviousVersion() = withFixture { f ->
        val v1 = f.writePackage("v1", "dsh-plugin-roll", version = "1.0.0")
        assertTrue(runBlocking { f.installer.install(v1) { _, _ -> } }.isSuccess)
        // Patch layer disappears (e.g. broken rootfs): the swap must be undone.
        f.webAppPatch.delete()
        val v2 = f.writePackage("v2", "dsh-plugin-roll", version = "2.0.0")
        val result = runBlocking { f.installer.install(v2) { _, _ -> } }
        assertTrue(result.isFailure)
        assertEquals("module.exports = '1.0.0'\n", File(f.nodeModules, "dsh-plugin-roll/index.js").readText())
        assertTrue(f.nodeModules.listFiles().orEmpty().none { it.name.startsWith(".dsh-") })
    }

    @Test
    fun localPathCannotEscapeMappedRoot() = withFixture { f ->
        File(f.root, "secret").mkdirs()
        val result = runBlocking { f.installer.install("/workspace/../secret") { _, _ -> } }
        assertTrue(result.isFailure)
    }
}
