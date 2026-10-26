package com.deepseek.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeHostTest {

    @Test
    fun testProcessOutputParser() {
        val line1 = "dsh web: listening on http://127.0.0.1:45321/?token=sec_abc12345"
        val parsed1 = ProcessOutputParser.parseLine(line1)
        assertNotNull(parsed1)
        assertEquals(45321, parsed1!!.port)
        assertEquals("sec_abc12345", parsed1.token)

        val line2 = "dsh web: listening on http://localhost:8080/?token=token_xyz"
        val parsed2 = ProcessOutputParser.parseLine(line2)
        assertNotNull(parsed2)
        assertEquals(8080, parsed2!!.port)
        assertEquals("token_xyz", parsed2.token)

        val line3 = "Unrelated log line from runtime"
        assertNull(ProcessOutputParser.parseLine(line3))
    }

    @Test
    fun testProotCommandBuilder() {
        val baseDir = File("/data/user/0/com.deepseek.dsh/files")
        val nativeDir = File("/data/app/com.deepseek.dsh/lib/arm64")
        val layout = RuntimeLayout(baseDir = baseDir, nativeLibraryDir = nativeDir)
        // workspace должна быть настоящей папкой: сборщик пропускает несуществующие источники привязки.
        val workspace = File.createTempFile("dsh-workspace", "").apply {
            delete()
            mkdirs()
        }

        try {
            val config = ProotCommandBuilder.LaunchConfig(
                layout = layout,
                userWorkspacePath = workspace.absolutePath,
                port = 0
            )

            val cmd = ProotCommandBuilder.build(config)
            assertTrue(cmd.contains("-r"))
            assertTrue(cmd.contains(layout.rootfsDir.absolutePath))
            assertTrue(cmd.contains("${workspace.absolutePath}:/workspace"))
            // Процессу Host нарочно не даётся --link2symlink: он ломает атомарную публикацию hardlink
            // инструмента write на рабочей папке FUSE (вместо этого fs-link-fallback.cjs).
            assertFalse(cmd.contains("--link2symlink"))
            // Три пункта как в DSHA: мнимый root, завершение гостя при выходе launcher, исправление lstat symlink.
            assertTrue(cmd.contains("-0"))
            assertTrue(cmd.contains("--kill-on-exit"))
            assertTrue(cmd.contains("-L"))
            // Relative plugin storage such as GenUI's .dsh/genui must resolve below
            // the Harness home, never the root-owned /.dsh directory.
            assertEquals("/root", cmd[cmd.indexOf("-w") + 1])
            assertTrue(cmd.contains("HOME=/root"))
            assertTrue(cmd.contains("DSH_HOME=/root/.dsh"))
            // Запускается прямо bin.js dsh, а не обёртка /opt/dsh/bin/dsh: обёртка направила бы
            // import.meta.url на себя, и опора разбора bundle профиля сбилась бы.
            assertTrue(cmd.contains("/usr/local/bin/node"))
            // Движок читает внутренние модули Node через --expose-internals, без зависимости от отсутствующего
            // нативного дополнения node-addon-require-builtin (прямая причина незапуска Host).
            assertTrue(cmd.contains("--expose-internals"))
            assertTrue(cmd.indexOf("--expose-internals") < cmd.indexOf("/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"))
            assertTrue(cmd.any { it.endsWith("@deepseek-ai/dsh/lib/bin.js") })
            assertTrue(cmd.contains("--profile"))
            assertTrue(cmd.contains("web"))
        } finally {
            workspace.deleteRecursively()
        }
    }
}
