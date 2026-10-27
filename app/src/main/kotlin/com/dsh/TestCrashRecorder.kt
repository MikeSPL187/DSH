package com.dsh

import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Только в debug (тестовой) сборке: при необработанном исключении стек и свежий logcat процесса
 * пишутся в `Documents/DSH-Test-Logs/` — тестировщик найдёт их в файловом менеджере без компьютера и adb.
 *
 * Перед записью вычищаются `token=`, `Bearer` и ключи вида `sk-`; в release не ставится.
 */
internal object TestCrashRecorder {

    private val SECRET = Regex(
        """((?:token|auth|access_token|api[_-]?key)=)[^&\s"']+|(Bearer\s+)[A-Za-z0-9._\-]+|\bsk-[A-Za-z0-9]{8,}""",
        RegexOption.IGNORE_CASE,
    )

    fun install(app: android.app.Application) {
        if (!BuildConfig.DEBUG) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(app: android.app.Application, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val report = buildString {
            appendLine("DSH test crash report $stamp")
            appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
            appendLine("----- logcat (this process) -----")
            append(recentLogcat())
        }
        val text = SECRET.replace(report) { m ->
            (m.groups[1]?.value ?: m.groups[2]?.value ?: "") + "***"
        }
        val dir = listOf(
            File(Environment.getExternalStorageDirectory(), "Documents/DSH-Test-Logs"),
            File(app.getExternalFilesDir(null), "crash"),
        ).firstOrNull { it.mkdirs() || it.isDirectory } ?: return
        File(dir, "crash-$stamp.txt").writeText(text)
    }

    private fun recentLogcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-t", "600", "--pid=${android.os.Process.myPid()}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.takeLast(256 * 1024)
    }.getOrDefault("(logcat unavailable)")
}
