package com.deepseek.dsh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File


internal fun formatAmount(raw: String): String {
    val clean = raw.removePrefix("¥").trim()
    val num = clean.toDoubleOrNull() ?: return "¥$raw"
    return String.format(java.util.Locale.US, "¥%.2f", num)
}

/**
 * Host 会话目录名：`--workspace-<escaped>--`，非 ASCII 码点转义为 `~XXXX`。
 * 与 Host 实际落盘规则一致，用于在 Android 侧预建会话目录。
 */
internal fun workspaceSessionDirName(workspaceName: String): String {
    val escaped = buildString {
        workspaceName.codePoints().forEach { codePoint ->
            if (codePoint in 0x21..0x7E && codePoint != 0x2F) {
                appendCodePoint(codePoint)
            } else {
                append('~')
                append(codePoint.toString(16).uppercase().padStart(4, '0'))
            }
        }
    }
    return "--workspace-$escaped--"
}

/** 从 workspace/create|rename 的 response 中取 workspaceId。 */
internal fun parseWorkspaceId(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return Regex("\"workspaceId\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
}

/**
 * 通用插件目录自愈：插件在 proot 里第一次 mkdir 可能失败（ENOENT/ENOSYS），
 * 从错误里取出它要创建的目录，用 App 进程创建后重试一次。
 *
 * 不按插件名适配：只接受 rootfs 内的 `.dsh/` 路径，任何插件都适用。
 */
internal fun ensureMissingPluginDir(context: android.content.Context, message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val guestPath = Regex("mkdir '([^']+)'").find(message)?.groupValues?.getOrNull(1) ?: return false
    if (!guestPath.startsWith("/root/.dsh/")) return false
    val relative = guestPath.removePrefix("/")
    val target = File(context.filesDir, "rootfs/$relative")
    return runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
}


/** App 侧记住的实验性 bundle 选择（profile 清单会被 Host 启动重置）。 */
