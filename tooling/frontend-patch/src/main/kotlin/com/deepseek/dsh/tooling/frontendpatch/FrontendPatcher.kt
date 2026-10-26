package com.deepseek.dsh.tooling.frontendpatch

import java.io.File

/**
 * Автоматическая вставка фронтенд-патча: native-surface.js перед скриптом запуска в dist/index.html.
 */
object FrontendPatcher {

    fun injectPatch(
        htmlFile: File,
        patchScriptContent: String,
        outputHtmlFile: File = htmlFile
    ): Result<Unit> {
        if (!htmlFile.exists()) {
            return Result.failure(IllegalArgumentException("Target HTML file not found: ${htmlFile.absolutePath}"))
        }

        try {
            val originalHtml = htmlFile.readText(Charsets.UTF_8)
            if (originalHtml.contains("__DSH_NATIVE_SURFACE__")) {
                // Уже вставлено — выход.
                return Result.success(Unit)
            }

            // Встроенный патч — перед </head>.
            val patchTag = "<script>\n$patchScriptContent\n</script>\n</head>"
            val patchedHtml = if (originalHtml.contains("</head>", ignoreCase = true)) {
                originalHtml.replace("</head>", patchTag)
            } else {
                "<script>\n$patchScriptContent\n</script>\n$originalHtml"
            }

            outputHtmlFile.writeText(patchedHtml, Charsets.UTF_8)
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
