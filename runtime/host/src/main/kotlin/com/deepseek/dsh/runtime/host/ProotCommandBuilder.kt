package com.deepseek.dsh.runtime.host

import java.io.File

/**
 * Сборка команды запуска.
 *
 * proot -0 -r <rootfs> -b /proc -b /dev -b /sys \
 *   -b /storage/emulated/0:/mnt/phone \
 *   -b <user-workspace>:/workspace \
 *   -b <appfiles>/cache:/cache \
 *   -b <appfiles>/saf:/saf \
 *   --link2symlink <rootfs>/usr/bin/env -i HOME=/root DSH_HOME=/root/.dsh ... \
 *   <rootfs>/opt/dsh/bin/dsh --profile web --no-open --port <случайный>
 */
object ProotCommandBuilder {

    data class LaunchConfig(
        val layout: RuntimeLayout,
        val userWorkspacePath: String,
        val internalStoragePath: String = "/storage/emulated/0",
        val port: Int = 0, // 0 — случайный порт
        val customEnv: Map<String, String> = emptyMap()
    )

    /**
     * Команда к выполнению: argv и переменные окружения, нужные самому proot.
     *
     * Окружение отдаётся вместе с командой, потому что `PROOT_LOADER` должен прийти из окружения процесса вызывающего,
     * а не из аргументов командной строки; при запуске Host так же.
     */
    data class ExecCommand(
        val command: List<String>,
        val environment: Map<String, String>,
    )

    /**
     * Выполнить произвольную команду внутри rootfs (без запуска Host).
     *
     * Привязки, `--link2symlink` и изоляция окружения — как при запуске Host, поэтому установка плагинов видит
     * ту же файловую систему, что и окружение. Рабочая папка всегда `/`: команды установки разбирают пути
     * по профилю и от cwd не зависят.
     * @param layout папки окружения.
     * @param argv команда внутри rootfs, например `["/usr/local/bin/node", "…/bin.js", "plugin", …]`.
     * @return исполняемый argv и нужные переменные окружения.
     */
    fun buildExec(layout: RuntimeLayout, argv: List<String>, workspacePath: String? = null): ExecCommand {
        val cmd = mutableListOf<String>()
        cmd.add(layout.prootBinary.absolutePath)
        cmd.add("-r")
        cmd.add(layout.rootfsDir.absolutePath)
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/sys")
        cmd.add("--link2symlink")
        cmd.add("-w")
        cmd.add("/")
        // Та же привязка кэша, что при запуске Host: DSH может писать промежуточные файлы в /cache.
        cmd.add("-b")
        cmd.add("${layout.cacheDir.absolutePath}:/cache")
        // Замена passwd/group: чтобы getpwuid(uid Android) разбирался, иначе node-pty не запустит shell.
        cmd.add("-b")
        cmd.add("${layout.passwdOverlay.absolutePath}:/etc/passwd")
        cmd.add("-b")
        cmd.add("${layout.groupOverlay.absolutePath}:/etc/group")
        // Рабочая папка пользователя — как у Host: /workspace.
        if (workspacePath != null && java.io.File(workspacePath).isDirectory) {
            cmd.add("-b")
            cmd.add("$workspacePath:/workspace")
        }
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=/root")
        cmd.add("DSH_HOME=/root/.dsh")
        cmd.add("DSH_PERMISSION_MODE=danger-full-access")
        cmd.add("PATH=/opt/dsh/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("NODE_ENV=production")
        cmd.add("LANG=C.UTF-8")
        cmd.add("TMPDIR=/root/.dsh/tmp")
        cmd.addAll(argv)
        return ExecCommand(
            command = cmd,
            environment = mapOf(
                "PROOT_LOADER" to "${layout.nativeLibraryDir.absolutePath}/libproot-loader.so",
                "PROOT_TMP_DIR" to layout.cacheDir.absolutePath,
                "LD_LIBRARY_PATH" to layout.nativeLibraryDir.absolutePath,
            ),
        )
    }

    fun build(config: LaunchConfig): List<String> {
        val rootfs = config.layout.rootfsDir.absolutePath
        val proot = config.layout.prootBinary.absolutePath
        val cache = config.layout.cacheDir.absolutePath
        val saf = config.layout.safDir.absolutePath

        val cmd = mutableListOf<String>()
        cmd.add(proot)
        // Параметры как у proot Termux в DSHA:
        // -L исправляет размер lstat у symlink; --kill-on-exit завершает гостя при выходе launcher;
        // -0 даёт гостю мнимый root (настоящие права — по-прежнему UID приложения). Вместе с привязками ниже
        // это условие, при котором subprocess в Host запускает bash по правилам Linux.
        cmd.add("-L")
        cmd.add("--kill-on-exit")
        cmd.add("-0")
        cmd.add("-r")
        cmd.add(rootfs)

        fun bind(host: String, guest: String? = null) {
            if (!java.io.File(host).exists()) return
            cmd.add("-b")
            cmd.add(if (guest == null || guest == host) host else "$host:$guest")
        }

        // Псевдофайловые системы Linux. DSHA ещё открывает /system, /apex, /dev/fd —
        // обычные зависимости нативных модулей Node и bash на Android.
        bind("/proc")
        bind("/dev")
        bind("/sys")
        bind("/system")
        bind("/apex")
        bind("/proc/self/fd", "/dev/fd")
        // /dev/random на Android может отсутствовать по умолчанию; bash и openssl его читают.
        bind("/dev/urandom", "/dev/random")

        // Выбранная пользователем рабочая папка привязывается как /workspace (жёсткое требование).
        bind(config.userWorkspacePath, "/workspace")

        // Личный кэш приложения и зеркало SAF.
        bind(cache, "/cache")

        // Замена passwd/group (см. RuntimeLayout.ensureIdentityOverlays):
        // uid в proot — это uid Android, его нет в passwd образа, и getpwuid
        // у node-pty не срабатывает — terminal/create сразу сообщает о системной ошибке.
        bind(config.layout.passwdOverlay.absolutePath, "/etc/passwd")
        bind(config.layout.groupOverlay.absolutePath, "/etc/group")

        // Внимание: процессу Host нельзя давать --link2symlink. С этим флагом proot имитирует link() через
        // промежуточные файлы `.l2s.*` и уносит исходный, и на рабочей папке FUSE атомарная запись инструмента write
        // «временный файл + жёсткая ссылка» падает с ENOENT. Вместо этого — preload-патч Node: link на
        // FUSE получает EPERM и переходит к copy+unlink (см. RuntimeLayout.ensureNodePatches).
        // Команды buildExec (установка плагинов и т. п.) сохраняют --link2symlink — им нужна именно эта семантика.
        cmd.add("-w")
        cmd.add("/root")

        // Изоляция окружения запуска.
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=/root")
        cmd.add("DSH_HOME=/root/.dsh")
        // На Android/proot нет рабочего бэкенда bubblewrap/Landlock; инструменты shell работают только с полным доступом.
        cmd.add("DSH_PERMISSION_MODE=danger-full-access")
        cmd.add("PATH=/opt/dsh/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("NODE_ENV=production")
        // Кэш компиляции V8: при первом запуске пишется на диск, дальше модули берутся из него — холодный старт заметно быстрее.
        cmd.add("NODE_COMPILE_CACHE=/root/.dsh/node-compile-cache")
        cmd.add("LANG=C.UTF-8")
        cmd.add("TMPDIR=/root/.dsh/tmp")

        config.customEnv.forEach { (k, v) ->
            cmd.add("$k=$v")
        }

        // bin.js dsh запускается прямо через node, в обход обёртки /opt/dsh/bin/dsh, —
        // так import.meta.url указывает на настоящую папку установки (иначе опора разбора bundle профиля неверна).
        cmd.add("/usr/local/bin/node")
        // Профиль запуска: найти горячие места холодного старта Host (--cpu-prof только в отладочной сборке).
        if (config.customEnv["DSH_CPU_PROFILE"] == "1") {
            cmd.add("--cpu-prof")
            cmd.add("--cpu-prof-dir=/root/.dsh/cpu-prof")
        }
        // Loader/app-boot движка читают внутренние модули Node через node-addon-require-builtin;
        // если в образе rootfs нет двоичного файла платформы этого нативного дополнения, Host просто не стартует
        // (Cannot find module .../node-addon-require-builtin/...). С открытыми internals
        // движок сначала пробует require('internal/...') и от дополнения не зависит.
        cmd.add("--expose-internals")
        // Запасной путь жёстких ссылок на рабочей папке FUSE (атомарное создание инструментом write).
        cmd.add("--require")
        cmd.add("/root/.dsh/patches/fs-link-fallback.cjs")
        cmd.add("--require")
        cmd.add("/root/.dsh/patches/startup-trace.cjs")
        // Если в образе есть bundle входа запуска — берём его: ~1200 открытий файлов модулей
        // сводятся к одному (под proot это главная цена холодного старта); иначе — исходный bin.js.
        val bundledEntry = java.io.File(
            config.layout.rootfsDir,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/host.bundle.mjs",
        )
        cmd.add(
            if (bundledEntry.isFile) {
                "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/host.bundle.mjs"
            } else {
                "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
            },
        )
        cmd.add("--profile")
        cmd.add("web")
        cmd.add("--no-open")
        cmd.add("--port")
        cmd.add(config.port.toString())

        return cmd
    }
}





