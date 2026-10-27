package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellCommandTest {
    private fun sh(argv: List<String>): String {
        val process = ProcessBuilder("/bin/sh", "-c", shellCommand(argv)).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    @Test
    fun `аргументы с метасимволами оболочки остаются строками`() {
        val marker = File.createTempFile("pwned", ".txt").apply { delete() }
        val hostile = listOf(
            "a;touch ${marker.path}",
            "\$(touch ${marker.path})",
            "`touch ${marker.path}`",
            "it's | & > <",
            "",
            "two words",
        )
        val out = sh(listOf("printf", "[%s]") + hostile)
        assertEquals(hostile.joinToString("") { "[$it]" }, out)
        assertTrue("оболочка выполнила аргумент", !marker.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `путь с двоеточием не монтируется`() {
        val root = kotlin.io.path.createTempDirectory("prootcfg").toFile()
        val workspace = File(root, "evil:etc").apply { mkdirs() }
        val layout = RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib"))
        ProotCommandBuilder.build(ProotCommandBuilder.LaunchConfig(layout = layout, userWorkspacePath = workspace.absolutePath))
    }
}
