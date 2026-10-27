package com.dsh.runtime.host

import java.io.File
import java.nio.file.Files

/**
 * Запасная реализация на чистом JS для `node-addon-require-builtin` в rootfs.
 *
 * Loader и app-boot движка читают через это нативное дополнение внутренние модули Node (загрузчики ESM/CJS
 * и др.). Двоичный файл платформы — optionalDependency: если машина сборки образа и целевой ABI
 * не совпали или установка пропустила необязательные зависимости, в пакете остаётся лишь JS-оболочка, и Host при запуске
 * сразу выходит с `Cannot find module .../node-addon-require-builtin/...`.
 *
 * С `--expose-internals` у Node `require('internal/...')` доступен напрямую, поэтому
 * дополнение можно заменить JS-реализацией `requireBuiltin(id) = require(id)`. Заменяются только
 * «уже сломанные» установки (нет главного файла или пакета платформы), исправные образы не трогаются.
 *
 * Повтор безопасен: итог пишется в `/root/.dsh/.addon-shim-revision`, та же revision повторно не применяется.
 */
internal object RuntimeAddonShim {

    /** Заменяемый пакет. */
    const val PACKAGE = "node-addon-require-builtin"

    /** Версия самой заглушки; растёт при изменении её кода — старые rootfs применят заново. */
    private const val REVISION = "1"

    /**
     * Проверить и (если нужно) поставить заглушку.
     * @param layout папки окружения.
     */
    fun ensure(layout: RuntimeLayout) {
        val globalModules = File(
            layout.rootfsDir,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
        )
        if (!globalModules.isDirectory) return
        val marker = File(layout.rootfsDir, "root/.dsh/.addon-shim-revision")
        if (marker.isFile && marker.readText().trim() == REVISION) return

        val target = File(globalModules, PACKAGE)
        if (needsShim(target, globalModules, layout)) {
            install(target)
        }
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(REVISION)
        }
    }

    /**
     * Не работает ли уже нативное дополнение.
     *
     * Признак: нет пакета или главного файла, или нет ни одного пакета платформы из `optionalDependencies`.
     * Ошибка разбора считается «сломано»: лучше чистый JS, чем незапускающийся Host.
     */
    private fun needsShim(
        base: File,
        globalModules: File,
        layout: RuntimeLayout,
    ): Boolean {
        val manifest = File(base, "package.json")
        if (!manifest.isFile) return true
        // Поиск по тексту, а не org.json: нужны всего два поля, а тесты идут на JVM
        // (org.json в android.jar — нереализованная заглушка).
        val text = runCatching { manifest.readText() }.getOrNull() ?: return true
        val main = MAIN_PATTERN.find(text)?.groupValues?.get(1) ?: "index.js"
        if (!File(base, main).isFile) return true
        val platformPackages = PLATFORM_PATTERN.findAll(text)
            .map { it.groupValues[1] }
            .toSet()
        if (platformPackages.isEmpty()) return false
        // Пакет платформы может быть поднят в dsh/node_modules, лежать внутри пакета или в общей папке rootfs.
        val roots = listOf(
            globalModules,
            File(base, "node_modules"),
            File(layout.rootfsDir, "usr/local/lib/node_modules"),
        )
        return platformPackages.none { name -> roots.any { root -> File(root, name).exists() } }
    }

    /** Поле `main` из `package.json`. */
    private val MAIN_PATTERN = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"")

    /** Имена подпакетов платформы из `optionalDependencies` (`node-addon-require-builtin-<os>-<arch>-<libc>`). */
    private val PLATFORM_PATTERN = Regex("\"($PACKAGE-[a-z0-9-]+)\"")

    /** Заменить целевой пакет реализацией на чистом JS (старая папка переименовывается — для разбора). */
    private fun install(target: File) {
        val parent = target.parentFile ?: return
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            val backup = File(parent, "$PACKAGE.broken-${System.currentTimeMillis()}")
            runCatching { target.renameTo(backup) }
        }
        target.mkdirs()
        File(target, "package.json").writeText(
            """{"name":"$PACKAGE","version":"0.1.9-local-shim","main":"index.js","private":true}"""
        )
        File(target, "index.js").writeText(
            """
            'use strict';
            // Движку нужен только requireBuiltin(id): с --expose-internals у Node
            // require('internal/...') доступен, поэтому нативный двоичный .node больше не нужен.
            function requireBuiltin(id) {
                return require(id);
            }
            module.exports = { requireBuiltin };
            """.trimIndent() + "\n"
        )
    }
}
