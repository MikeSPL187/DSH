package com.dsh.runtime.host

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HostStartupFailureTest {
    @Test
    fun `missing rootfs publishes the same error as the failed start result`() = runBlocking {
        val baseDir = Files.createTempDirectory("dsh-start-refused").toFile()
        try {
            val runner = HostProcessRunner()
            val manager = DefaultHostRuntimeManager(ContextWrapper(null), RuntimeLayout(baseDir, baseDir), runner)
            val startedAt = System.currentTimeMillis()

            val result = manager.start(baseDir.resolve("workspace").absolutePath)

            assertTrue(result.isFailure)
            val status = manager.getStatus()
            assertEquals(RuntimePhase.ERROR, status.phase)
            assertEquals(result.exceptionOrNull()?.message, status.lastError)
            assertTrue(status.lastError.orEmpty().contains("not installed"))
            assertTrue(status.updatedAtMillis >= startedAt)
            assertTrue(manager.warmUp(baseDir.absolutePath).isFailure)
            assertEquals(status, manager.getStatus())
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
