package com.dsh.runtime.host

import java.io.File

/**
 * Папки локального окружения.
 *
 * files/
 * ├─ rootfs/                 # Debian slim (по умолчанию) / Ubuntu minimal (запасной)
 * │  ├─ opt/dsh/             # Node, pnpm, вход dsh
 * │  └─ root/.dsh/           # DSH_HOME: настройки, сессии, плагины, учётные данные
 * ├─ runtime/                # список версий, патчи, контрольные суммы
 * ├─ workspace-link/         # привязка рабочей папки
 * ├─ cache/                  # кэш зависимостей и сборки (/cache)
 * ├─ saf/                    # корень зеркала SAF
 * ├─ logs/                   # журналы окружения
 * └─ backups/                # снимки данных пользователя
 */
data class RuntimeLayout(
    val baseDir: File,
    val nativeLibraryDir: File
) {
    val rootfsDir: File get() = File(baseDir, RootfsNaming.INSTALL_DIR)
    val optDshDir: File get() = File(rootfsDir, "opt/dsh")
    val dshHomeDir: File get() = File(rootfsDir, "root/.dsh")
    /** `~/.agents` в Linux: общий корень агентов; `AGENTS.md` в нём — память агента (`AgentMemory`). */
    val agentsHomeDir: File get() = File(rootfsDir, "root/.agents")

    /**
     * Файл токена моста устройства.
     *
     * Те же байты, что `/root/.dsh/.bridge_token` у гостя; приложение меняет его при каждом запуске Host,
     * патчи Host передают его в запросах. Файл лежит в личной папке приложения, другие приложения Android его не прочитают
     * и не смогут через мост loopback выполнять команды на устройстве.
     */
    val bridgeTokenFile: File get() = File(dshHomeDir, BridgeTokenFileName)

    val runtimeMetaDir: File get() = File(baseDir, "runtime")
    val cacheDir: File get() = File(baseDir, "cache")
    val safDir: File get() = File(baseDir, "saf")
    val logsDir: File get() = File(baseDir, "logs")
    val backupsDir: File get() = File(baseDir, "backups")

    val prootBinary: File get() = File(nativeLibraryDir, "libproot.so")

    /** Файл, подменяющий /etc/passwd в rootfs: uid Android нет в passwd образа. */
    val passwdOverlay: File get() = File(runtimeMetaDir, "etc/passwd")
    /** Файл, подменяющий /etc/group в rootfs. */
    val groupOverlay: File get() = File(runtimeMetaDir, "etc/group")

    /**
     * Записать подмены passwd/group.
     *
     * Без -0 у proot uid гостя — это uid Android (например, 10227), а в /etc/passwd образа rootfs
     * только root и системные учётные записи. Когда node-pty запускает shell, libuv вызывает
     * `uv_os_get_passwd` (getpwuid) — не найдя, получает ENOENT, и терминал
     * пишет `A system error occurred`. Здесь текущие uid/gid добавляются в passwd/group
     * и привязываются через proot -b.
     */
    /** Папка preload-патчей Node перед запуском (пока только запасной fs.link). */
    val patchDir: File get() = File(dshHomeDir, "patches")

    /** Preload-патч: публикация файла на Android сохраняет источник и не заменяет существующую цель. */
    val linkFallbackPatch: File get() = File(patchDir, "fs-link-fallback.cjs")

    /** Замер времени запуска: моменты preload, медленных модулей и готовности dsh web. */
    val startupTracePatch: File get() = File(patchDir, "startup-trace.cjs")

    /**
     * Поставить preload-патчи Node.
     *
     * Инструмент `write` при создании файла атомарно публикует его через `link(temp, target)`; внешняя память
     * Android — FUSE, жёсткая ссылка сразу даёт EPERM (--link2symlink у proot не перехватывает
     * путь linkat из Node), и перекрывающая запись агента всегда падает, остаётся только shell. Здесь
     * preload-патч публикует копию через COPYFILE_EXCL до входа в эмуляцию hardlink PRoot.
     * Источник остаётся для последующего unlink вызывающим; существующая цель не заменяется.
     * libc-команды (dpkg) сохраняют полноценную эмуляцию hardlink в PRoot.
     */
    fun ensureNodePatches() {
        patchDir.mkdirs()
        val source = """
            |'use strict'
            |// dsh-file-publish-copy-excl-v2: do not enter PRoot link2symlink for Node publication.
            |const fs = require('node:fs')
            |const fsp = require('node:fs/promises')
            |const flags = fs.constants.COPYFILE_EXCL
            |// The source remains until its caller unlinks it; an existing target is never replaced.
            |// Android disallows real hardlinks. PRoot's emulation may relocate a temporary source
            |// before reporting an error on FUSE, so attempting link first is not safe here.
            |fs.linkSync = function (existingPath, newPath) {
            |  return fs.copyFileSync(existingPath, newPath, flags)
            |}
            |fs.link = function (existingPath, newPath, callback) {
            |  return fs.copyFile(existingPath, newPath, flags, callback)
            |}
            |fsp.link = function (existingPath, newPath) {
            |  return fsp.copyFile(existingPath, newPath, flags)
            |}
            |require('node:module').syncBuiltinESMExports()
            |""".trimMargin()
        if (!linkFallbackPatch.isFile || linkFallbackPatch.readText() != source) {
            linkFallbackPatch.writeText(source)
        }
        runCatching { linkFallbackPatch.setReadable(true, false) }

        val traceSource = """
            |'use strict'
            |const Module = require('node:module')
            |// При force-stop процесса хуки exit не вызываются, кэш компиляции нужно регулярно сбрасывать самим,
            |// иначе папка NODE_COMPILE_CACHE всегда пуста. Здесь тихо, без журнала времени.
            |const flush = () => { try { Module.flushCompileCache?.() } catch {} }
            |const flushTimer = setInterval(flush, 5000)
            |flushTimer.unref()
            |""".trimMargin()
        if (!startupTracePatch.isFile || startupTracePatch.readText() != traceSource) {
            startupTracePatch.writeText(traceSource)
        }
        runCatching { startupTracePatch.setReadable(true, false) }
    }

    /**
     * Исправить в уже установленном в Host provider subprocess дефект пустого канала control.
     *
     * Под proot `controlPipe()` может вернуть null; provider проверяет только `!== undefined`,
     * поэтому кладёт null в controlChannels и вызывает `control.once(...)` —
     * запуск worker workflow падает с `Cannot read properties of null (reading 'once')`.
     * Здесь проверка лишь расширяется на null/undefined; повтор безопасен, действует с версией приложения.
     */
    fun ensureHostRuntimePatches() {
        val provider = java.io.File(
            rootfsDir,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
                "@deepseek-ai/dsh-subprocess-local/lib/index.js",
        )
        if (!provider.isFile) return
        runCatching {
            val text = provider.readText()
            val patched = text.replace(
                "if (control !== void 0) {",
                "if (control != null) {",
            )
            if (patched != text) provider.writeText(patched)
        }
    }

    /** Пользовательский файл патча профиля (переключатель плагинов Host дописывает сюда строки `- id:`). */
    val profilePatchFile: File
        get() = File(rootfsDir, "root/.dsh/profiles/web/cordis.patch.yml")

    /**
     * Исправить патч профиля, испорченный старыми версиями.
     *
     * setPluginEnabled в Host дописывал `- id: ...` сразу после файла с пустым массивом (`[]`),
     * получался неверный YAML «`[]` и за ним элементы списка», и следующий запуск падал в loadOverlayPatches.
     * Если в файле уже есть элементы списка, отдельная строка `[]` удаляется; если только `[]` — без изменений.
     */
    fun repairProfilePatch() {
        val file = profilePatchFile
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val lines = text.lines()
        val hasListItems = lines.any { it.trimStart().startsWith("- ") }
        if (!hasListItems) return
        val fixed = lines.filterNot { it.trim() == "[]" }.joinToString("\n")
        if (fixed != text) {
            runCatching { file.writeText(fixed) }
        }
    }

    fun ensureIdentityOverlays() {
        runtimeMetaDir.mkdirs()
        passwdOverlay.parentFile?.mkdirs()
        val uid = android.os.Process.myUid()
        val gid = runCatching { android.system.Os.getgid() }.getOrDefault(uid)
        // Keep Debian service accounts: replacing the file with root/dsh breaks apt's
        // _apt sandbox and package scripts resolving system users and groups.
        val passwd = identityOverlay(File(rootfsDir, "etc/passwd").takeIf { it.isFile }?.readText()
            ?: "root:x:0:0:root:/root:/bin/sh\n", "dsh:x:$uid:$gid:dsh:/root:/bin/sh", passwdOverlay.takeIf { it.isFile }?.readText())
        val group = identityOverlay(File(rootfsDir, "etc/group").takeIf { it.isFile }?.readText()
            ?: "root:x:0:\n", "dsh:x:$gid:", groupOverlay.takeIf { it.isFile }?.readText())
        if (!passwdOverlay.isFile || passwdOverlay.readText() != passwd) {
            passwdOverlay.writeText(passwd)
        }
        if (!groupOverlay.isFile || groupOverlay.readText() != group) {
            groupOverlay.writeText(group)
        }
        runCatching {
            passwdOverlay.setReadable(true, false)
            groupOverlay.setReadable(true, false)
        }
    }

    fun ensureDirectories() {
        baseDir.mkdirs()
        rootfsDir.mkdirs()
        optDshDir.mkdirs()
        dshHomeDir.mkdirs()
        runtimeMetaDir.mkdirs()
        cacheDir.mkdirs()
        safDir.mkdirs()
        logsDir.mkdirs()
        backupsDir.mkdirs()
        ensureIdentityOverlays()
        ensureNodePatches()
    }

    private companion object {
        /** Как `BridgeAuth.TOKEN_FILE_NAME`; модуль host не зависит от модуля bridge в обратную сторону. */
        const val BridgeTokenFileName = ".bridge_token"
    }
}



/** Add the Android identity without deleting the distro's package/service accounts. */
internal fun identityOverlay(base: String, androidEntry: String, previous: String? = null): String {
    val entries = base.lineSequence().filter { it.isNotBlank() && it.substringBefore(':') != "dsh" }.associateByTo(linkedMapOf()) { it.substringBefore(':') }
    previous?.lineSequence()?.filter { it.isNotBlank() && it.substringBefore(':') != "dsh" }?.forEach { line ->
        entries.putIfAbsent(line.substringBefore(':'), line)
    }
    return (entries.values + androidEntry).joinToString("\n", postfix = "\n")
}
