package com.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Копия «без API-ключей» действительно без ключей (DSH-13); посторонний ZIP — не копия (DSH-14). */
class BackupSecretsTest {
    private fun layout(): RuntimeLayout {
        val root = Files.createTempDirectory("dsh-backup-secrets").toFile()
        return RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib")).also { it.ensureDirectories() }
    }

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().asSequence().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).reader().readText() }
    }

    @Test
    fun `export without API keys keeps no key values`() = runBlocking {
        val home = layout().also { it.dshHomeDir.mkdirs() }
        File(home.dshHomeDir, "config.json").writeText("""{"apiKey":"sk-QA-SYNTHETIC-ONLY-01234567890123456789","model":"deepseek"}""")
        File(home.dshHomeDir, "notes.md").writeText("DEEPSEEK_API_KEY=sk-QA-SYNTHETIC-ONLY-ABCDEFGHIJ\nok\n")
        File(home.dshHomeDir, "history.jsonl").writeText(
            """{"sessionId":"session-1","tokenUsage":{"outputTokens":5},"text":"use ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"}""" + "\n",
        )
        val zip = RuntimeBackupAndDiagnostics(home).createUserDataBackup().getOrThrow()
        val all = entries(zip)
        val text = all.values.joinToString("\n")
        assertFalse("backup labelled 'without API keys' contains a synthetic key", text.contains("SYNTHETIC-ONLY"))
        assertFalse(text.contains("ghp_ABCDEFGHIJ"))
        // Остальное содержимое не портится: id сессии, счётчики, обычные поля.
        val config = all.getValue("dsh_home/config.json")
        assertEquals("""{"apiKey":"","model":"deepseek"}""", config)
        val history = all.getValue("dsh_home/history.jsonl")
        assertTrue(history.contains(""""sessionId":"session-1""""))
        assertTrue(history.contains(""""outputTokens":5"""))
        assertTrue(history.endsWith("\n"))
        assertEquals("DEEPSEEK_API_KEY=\nok\n", all.getValue("dsh_home/notes.md"))
    }

    @Test
    fun `files without secrets are copied byte for byte`() = runBlocking {
        val home = layout().also { it.dshHomeDir.mkdirs() }
        val crlf = "line one\r\nline two\r\n"
        File(home.dshHomeDir, "plain.txt").writeText(crlf)
        val binary = byteArrayOf(0, 1, 2, 's'.code.toByte(), 'k'.code.toByte(), '-'.code.toByte(), 0)
        File(home.dshHomeDir, "blob.bin").writeBytes(binary)
        val zip = RuntimeBackupAndDiagnostics(home).createUserDataBackup().getOrThrow()
        ZipFile(zip).use { z ->
            assertEquals(crlf, z.getInputStream(z.getEntry("dsh_home/plain.txt")).reader().readText())
            assertTrue(binary.contentEquals(z.getInputStream(z.getEntry("dsh_home/blob.bin")).readBytes()))
        }
    }

    @Test
    fun `scrubbed backup stays trusted on the same phone`() = runBlocking {
        val home = layout().also { it.dshHomeDir.mkdirs() }
        File(home.dshHomeDir, "config.json").writeText("""{"apiKey":"sk-QA-SYNTHETIC-ONLY-01234567890123456789"}""")
        val signer = BackupSigner { data -> Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("phone".toByteArray(), "HmacSHA256")) }.doFinal(data) }
        val tool = RuntimeBackupAndDiagnostics(home, signer)
        val zip = tool.createUserDataBackup().getOrThrow()
        val plan = tool.prepareRestore(zip.inputStream()).getOrThrow()
        assertTrue(plan.trusted)
        tool.discardRestore(plan)
    }

    @Test
    fun `empty foreign zip is rejected as not a backup`() = runBlocking {
        val home = layout()
        val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        val result = RuntimeBackupAndDiagnostics(home).restoreUserDataBackup(empty.inputStream())
        assertTrue("ZIP without the snapshot marker was reported as a restored DSH backup: $result", result.isFailure)
        assertFalse(File(home.dshHomeDir.parentFile, ".dsh-restore").exists())
    }

    @Test
    fun `secret field names are matched exactly`() {
        assertEquals(""""sessionId":"s-1"""", BackupSecrets.scrubLine(""""sessionId":"s-1""""))
        assertEquals(""""tokenUsage":{"input":1}""", BackupSecrets.scrubLine(""""tokenUsage":{"input":1}"""))
        assertEquals(""""maxTokens":"4096"""", BackupSecrets.scrubLine(""""maxTokens":"4096""""))
        assertEquals(""""access_token":""""", BackupSecrets.scrubLine(""""access_token":"abc\"def""""))
        assertEquals(""""openaiApiKey": """"", BackupSecrets.scrubLine(""""openaiApiKey": "xyz""""))
        assertEquals(""""password":""""", BackupSecrets.scrubLine(""""password":"hunter2""""))
    }
}
