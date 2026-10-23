package com.deepseek.dsh

import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只在 debug（测试）构建里启用：未捕获异常时把堆栈和本进程最近的 logcat 写到
 * `Documents/DSH-Test-Logs/`，测试者没有电脑/adb 也能在手机文件管理器里找到并转发。
 *
 * 写入前会抹掉 `token=`、`Bearer` 和 `sk-` 样式的密钥；release 构建完全不安装。
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
