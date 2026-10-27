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

    /** Preload-патч: жёсткая ссылка на FUSE всегда даёт EPERM — заменяем семантикой copy+rename. */
    val linkFallbackPatch: File get() = File(patchDir, "fs-link-fallback.cjs")

    /** Замер времени запуска: моменты preload, медленных модулей и готовности dsh web. */
    val startupTracePatch: File get() = File(patchDir, "startup-trace.cjs")

    /**
     * Поставить preload-патчи Node.
     *
     * Инструмент `write` при создании файла атомарно публикует его через `link(temp, target)`; внешняя память
     * Android — FUSE, жёсткая ссылка сразу даёт EPERM (--link2symlink у proot не перехватывает
     * путь linkat из Node), и перекрывающая запись агента всегда падает, остаётся только shell. Здесь
     * маленький preload-патч: при EPERM/ENOTSUP/EXDEV link заменяется на «копия + удаление временного файла»,
     * с сохранением правила «успех, только если цели не было».
     */
    fun ensureNodePatches() {
        patchDir.mkdirs()
        val source = """
            |'use strict'
            |const fs = require('node:fs')
            |const fsp = require('node:fs/promises')
            |const FALLBACK_CODES = new Set(['EPERM', 'ENOTSUP', 'EOPNOTSUPP', 'EXDEV', 'EACCES'])
            |function fallbackSync(existingPath, newPath) {
            |  // Только копия, источник не удаляется: после успешного link вызывающий обычно сам делает unlink источника
            |  // (так публикуют атомарно attachment store и fs-local), раннее удаление дало бы
            |  // на том шаге ENOENT, обёрнутый в ошибку записи.
            |  fs.copyFileSync(existingPath, newPath, fs.constants.COPYFILE_EXCL || 1)
            |}
            |const originalLinkSync = fs.linkSync
            |fs.linkSync = function (existingPath, newPath) {
            |  try { return originalLinkSync(existingPath, newPath) } catch (e) {
            |    if (!e || !FALLBACK_CODES.has(e.code)) throw e
            |    return fallbackSync(existingPath, newPath)
            |  }
            |}
            |const originalLink = fs.link
            |fs.link = function (existingPath, newPath, callback) {
            |  const done = typeof callback === 'function' ? callback : function () {}
            |  return originalLink.call(fs, existingPath, newPath, function (error) {
            |    if (!error || !FALLBACK_CODES.has(error.code)) return done(error)
            |    try { fallbackSync(existingPath, newPath); done(null) } catch (fallbackError) { done(fallbackError) }
            |  })
            |}
            |const originalLinkPromise = fsp.link
            |fsp.link = async function (existingPath, newPath) {
            |  try { return await originalLinkPromise(existingPath, newPath) } catch (e) {
            |    if (!e || !FALLBACK_CODES.has(e.code)) throw e
            |    fallbackSync(existingPath, newPath)
            |  }
            |}
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
        val passwd = """
            root:x:0:0:root:/root:/bin/sh
            dsh:x:$uid:$gid:dsh:/root:/bin/sh
        """.trimIndent() + "\n"
        val group = """
            root:x:0:
            dsh:x:$gid:
        """.trimIndent() + "\n"
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


