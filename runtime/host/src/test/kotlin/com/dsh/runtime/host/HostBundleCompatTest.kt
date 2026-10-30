package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HostBundleCompatTest {
    private val module = """
        var __require = (function(x) {
          if (typeof require !== "undefined") return require.apply(this, arguments);
          throw Error('Dynamic require of "' + x + '" is not supported');
        });
        await Promise.resolve().then(() => {
          const assert = __require('node:assert');
          assert.equal(__require('./fixture.cjs').answer, 42);
          assert.equal(typeof __require('node:stream').Readable, 'function');
          assert.equal(typeof globalThis.require, 'undefined');
        });
        console.log('HOST_BUNDLE_OK');
    """.trimIndent()

    @Test
    fun `missing bundle keeps the bin entry fallback without creating files`() {
        val root = Files.createTempDirectory("dsh-no-host-bundle").toFile()
        try {
            HostBundleCompat.ensure(root).getOrThrow()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ESM CommonJS startup fails before repair and resolves builtins and relative modules after it`() {
        verifyRepair(module)
    }

    @Test
    fun `repair preserves the leading shebang and works from another directory`() {
        verifyRepair("#!/usr/bin/env node\n$module")
    }

    @Test
    fun `image with a build time banner is not patched twice`() {
        val root = Files.createTempDirectory("dsh-fixed-host-bundle").toFile()
        try {
            val bundle = File(root, HostBundleCompat.BUNDLE_PATH).apply { parentFile!!.mkdirs() }
            val source = "/* dsh-host-bundle-require-v1 */ import { createRequire as __dshHostCreateRequire } from 'node:module'; var require = __dshHostCreateRequire(import.meta.url);\n$module"
            bundle.writeText(source)
            File(bundle.parentFile, "fixture.cjs").writeText("module.exports = { answer: 42 };\n")
            HostBundleCompat.ensure(root).getOrThrow()
            assertEquals(source, bundle.readText())
            val result = runNode(bundle, root)
            assertEquals(result.second, 0, result.first)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifyRepair(source: String) {
        val root = Files.createTempDirectory("dsh-host-bundle").toFile()
        try {
            val bundle = File(root, HostBundleCompat.BUNDLE_PATH).apply { parentFile!!.mkdirs() }
            bundle.writeText(source)
            File(bundle.parentFile, "fixture.cjs").writeText("module.exports = { answer: 42 };\n")
            val before = runNode(bundle, root)
            assertTrue(before.second, before.first != 0)
            assertTrue(before.second, before.second.contains("Dynamic require of \"node:assert\" is not supported"))

            HostBundleCompat.ensure(root).getOrThrow()

            val after = runNode(bundle, root)
            assertEquals(after.second, 0, after.first)
            assertTrue(after.second, after.second.contains("HOST_BUNDLE_OK"))
            assertEquals(source.startsWith("#!"), bundle.readText().startsWith("#!/usr/bin/env node\n"))
            val repaired = bundle.readBytes()
            HostBundleCompat.ensure(root).getOrThrow()
            assertTrue(repaired.contentEquals(bundle.readBytes()))
            assertFalse(bundle.parentFile!!.listFiles().orEmpty().any { it.name.startsWith(".dsh-host-") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runNode(bundle: File, cwd: File): Pair<Int, String> {
        val process = ProcessBuilder("node", bundle.absolutePath).directory(cwd).redirectErrorStream(true).start()
        try {
            assertTrue("Node не завершил проверку bundle за 15 с", process.waitFor(15, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            process.destroyForcibly()
        }
    }
}
