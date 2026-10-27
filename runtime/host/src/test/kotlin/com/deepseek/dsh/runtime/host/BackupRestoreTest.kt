package com.deepseek.dsh.runtime.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** G-05: копия подписана ключом телефона; чужая или изменённая видна до записи, восстановление откатывается при сбое. */
class BackupRestoreTest {
    private fun signer(key: String) = BackupSigner { data ->
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }.doFinal(data)
    }

    private fun layout(): RuntimeLayout {
        val root = Files.createTempDirectory("dsh-backup").toFile()
        return RuntimeLayout(baseDir = root, nativeLibraryDir = File(root, "lib")).also { it.ensureDirectories() }
    }

    /** Переписать архив, заменив содержимое записи [name]. */
    private fun tamper(zip: File, name: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(zip.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val bytes = if (entry.name == name) content.toByteArray() else zis.readBytes()
                    zos.putNextEntry(ZipEntry(entry.name)); zos.write(bytes); zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun ownBackupIsTrustedForeignOrTamperedIsNot() = runBlocking {
        val phone = layout()
        File(phone.dshHomeDir, "settings.json").writeText("""{"preset":"read_only"}""")
        File(phone.dshHomeDir, "plugins").mkdirs()
        File(phone.dshHomeDir, "plugins/enabled.json").writeText("[]")
        val tool = RuntimeBackupAndDiagnostics(phone, signer("this-phone"))
        val backup = tool.createUserDataBackup().getOrThrow()

        val plan = tool.prepareRestore(backup.inputStream()).getOrThrow()
        assertTrue(plan.trusted)
        assertTrue(plan.files.containsAll(listOf("plugins/enabled.json", "settings.json")))
        assertEquals(plan.files, plan.replaced)
        tool.discardRestore(plan)

        // Подменённый пресет прав в той же копии — подпись не сходится.
        val tampered = tamper(backup, "dsh_home/settings.json", """{"preset":"danger_full_access"}""")
        assertFalse(tool.prepareRestore(tampered.inputStream()).getOrThrow().also(tool::discardRestore).trusted)

        // Копия с другого телефона (другой ключ) и без подписи — тоже не «своя».
        assertFalse(RuntimeBackupAndDiagnostics(phone, signer("other-phone")).prepareRestore(backup.inputStream()).getOrThrow().trusted)
        val unsigned = RuntimeBackupAndDiagnostics(phone).createUserDataBackup().getOrThrow()
        assertFalse(tool.prepareRestore(unsigned.inputStream()).getOrThrow().trusted)
    }

    @Test
    fun prepareDoesNotTouchHostDataAndFailedApplyRollsBack() = runBlocking {
        val phone = layout()
        File(phone.dshHomeDir, "a.json").writeText("old-a")
        File(phone.dshHomeDir, "b.json").writeText("old-b")
        val tool = RuntimeBackupAndDiagnostics(phone, signer("k"))
        val backup = tool.createUserDataBackup().getOrThrow()
        File(phone.dshHomeDir, "a.json").writeText("current-a")

        val plan = tool.prepareRestore(backup.inputStream()).getOrThrow()
        assertEquals("current-a", File(phone.dshHomeDir, "a.json").readText())

        // Сбой на втором файле: первый уже перенесён — откат возвращает прежнее состояние.
        File(phone.dshHomeDir.parentFile, ".dsh-restore/home/b.json").delete()
        assertTrue(tool.applyRestore(plan).isFailure)
        assertEquals("current-a", File(phone.dshHomeDir, "a.json").readText())
        assertEquals("old-b", File(phone.dshHomeDir, "b.json").readText())
        assertFalse(File(phone.dshHomeDir.parentFile, ".dsh-restore").exists())

        val ok = tool.prepareRestore(backup.inputStream()).getOrThrow()
        assertEquals(ok.files.size, tool.applyRestore(ok).getOrThrow())
        assertEquals("old-a", File(phone.dshHomeDir, "a.json").readText())
    }
}
