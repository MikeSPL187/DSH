package com.dsh.runtime.host

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Тесты [HostProcessRunner] на JVM: без Android и без proot.
 *
 * Два настоящих пути сбоев:
 * 1. Нормальный запуск: из строки `dsh web:` берутся порт и токен, после `markReady` — RUNNING;
 * 2. Выход посреди запуска: сторож выхода переводит в ERROR с кодом выхода и хвостом stderr
 *    (именно это было причиной «окружение навсегда в запуске»).
 */
class HostProcessRunnerTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun `endpoint line reaches starting then running after markReady`() = runBlocking {
        val runner = HostProcessRunner()
        val workingDir = Files.createTempDirectory("dsh-host-runner-ok").toFile()
        try {
            val launch = runner.launchProcess(
                command = endpointCommand(),
                workingDir = workingDir,
            )
            assertTrue(launch.exceptionOrNull()?.message.orEmpty(), launch.isSuccess)

            val starting = withTimeout(15_000L) {
                runner.statusFlow.first { status ->
                    status.phase == RuntimePhase.STARTING && status.port == 12345
                }
            }
            assertEquals(12345, starting.port)
            assertEquals("abc", starting.token)
            // Токен должен быть вычищен до попадания в состояние и интерфейс.
            assertTrue(
                "logTail должен содержать URL без секретов, факт=${starting.logTail}",
                starting.logTail.any { it.contains("token=REDACTED") && !it.contains("token=abc") },
            )

            runner.markReady()
            val running = withTimeout(5_000L) {
                runner.statusFlow.first { it.phase == RuntimePhase.RUNNING }
            }
            assertEquals(12345, running.port)
            assertEquals("abc", running.token)
        } finally {
            runner.terminate()
            workingDir.deleteRecursively()
        }
    }

    @Test
    fun `process exit during startup becomes error with exit code and log tail`() = runBlocking {
        val runner = HostProcessRunner()
        val workingDir = Files.createTempDirectory("dsh-host-runner-exit").toFile()
        try {
            val launch = runner.launchProcess(
                command = failingCommand(),
                workingDir = workingDir,
            )
            assertTrue(launch.exceptionOrNull()?.message.orEmpty(), launch.isSuccess)

            val error = withTimeout(15_000L) {
                runner.statusFlow.first { it.phase == RuntimePhase.ERROR }
            }
            assertEquals(3, error.exitCode)
            assertTrue(
                "lastError должен содержать код выхода, факт=${error.lastError}",
                error.lastError.orEmpty().contains("exit=3"),
            )
            assertTrue(
                "logTail должен содержать boom из stderr, факт=${error.logTail}",
                error.logTail.any { it.contains("boom") },
            )
        } finally {
            runner.terminate()
            workingDir.deleteRecursively()
        }
    }

    /** Напечатать строку с Web URL DSH и жить, пока тест не завершит. */
    private fun endpointCommand(): List<String> = if (isWindows) {
        listOf(
            "cmd",
            "/c",
            "echo dsh web: listening on http://127.0.0.1:12345/?token=abc & ping -n 8 127.0.0.1 >nul",
        )
    } else {
        listOf(
            "sh",
            "-c",
            "echo 'dsh web: listening on http://127.0.0.1:12345/?token=abc'; sleep 10",
        )
    }

    /** Напечатать строку в stderr и выйти с кодом 3. */
    private fun failingCommand(): List<String> = if (isWindows) {
        listOf("cmd", "/c", "echo boom 1>&2 & exit /b 3")
    } else {
        listOf("sh", "-c", "echo boom 1>&2; exit 3")
    }
}
