package com.dsh.runtime.host

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** ESM-bundle содержит CommonJS-зависимости: им нужен require с опорой на сам файл, а не cwd. */
internal object HostBundleCompat {
    internal const val BUNDLE_PATH = "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/host.bundle.mjs"
    private const val MARKER = "/* dsh-host-bundle-require-v1 */"
    private const val PRELUDE = "$MARKER\n" +
        "import { createRequire as __dshHostCreateRequire } from 'node:module';\n" +
        "var require = __dshHostCreateRequire(import.meta.url);\n"

    fun ensure(rootfsDir: File): Result<Unit> = runCatching {
        val bundle = File(rootfsDir, BUNDLE_PATH)
        if (!bundle.isFile) return@runCatching
        bundle.bufferedReader().use { reader ->
            val first = reader.readLine()
            val banner = if (first?.startsWith("#!") == true) reader.readLine() else first
            if (banner?.startsWith(MARKER) == true) return@runCatching
        }
        val source = bundle.readText()
        // Shebang допустим только в первой строке; добавляем код сразу после него.
        val split = if (source.startsWith("#!")) source.indexOf('\n').let { if (it < 0) source.length else it + 1 } else 0
        val shebang = source.take(split).let { if (it.isNotEmpty() && !it.endsWith('\n')) "$it\n" else it }
        val patched = shebang + PRELUDE + source.drop(split)
        val temp = Files.createTempFile(bundle.toPath().parent, ".dsh-host-", ".mjs")
        try {
            temp.toFile().writeText(patched)
            // Прерванное обновление APK не должно оставить половину исполняемого bundle.
            try {
                Files.move(temp, bundle.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, bundle.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
