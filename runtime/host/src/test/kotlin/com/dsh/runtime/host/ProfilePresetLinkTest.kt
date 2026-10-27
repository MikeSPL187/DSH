package com.dsh.runtime.host

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfilePresetLinkTest {
    @Test
    fun `merges installed packages into a real scope directory`() {
        val root = Files.createTempDirectory("dsh-profile-preset").toFile()
        try {
            val profileDir = File(root, "root/.dsh/profiles/web")
            val scope = File(profileDir, "node_modules/@deepseek-ai").apply { mkdirs() }
            // Поднятые пакеты из нового rootfs должны остаться.
            File(scope, "cosmokit").mkdirs()

            val installed = File(
                profileDir,
                "../../../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
            ).canonicalFile
            File(installed, "cosmokit").mkdirs()
            File(installed, "dsh-agent-preset").mkdirs()

            ensureProfilePresetLink(profileDir)

            assertTrue(File(scope, "cosmokit").isDirectory)
            assertTrue(
                "Официальный bundle должен быть в области profile",
                File(scope, "dsh-agent-preset").isDirectory,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
