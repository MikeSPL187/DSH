package com.deepseek.dsh.runtime.host

import android.content.Context
import android.content.Intent
import com.deepseek.dsh.runtime.bridge.BridgeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Менеджер локального рабочего окружения (вместе с мостом возможностей устройства BridgeServer).
 */
class DefaultHostRuntimeManager(
    private val context: Context,
    private val layout: RuntimeLayout,
    private val runner: HostProcessRunner = HostProcessRunner(),
) : HostRuntimeManager {

    private var bridgeServer: BridgeServer? = null
    private val startMutex = Mutex()

    /** Источник манифеста: свежая загрузка, при ошибке — кэш, в крайнем случае — из APK. */
    private val manifestSourceCache by lazy { RootfsManifestSource(context, layout) }

    private fun manifestSource(): RootfsManifestSource = manifestSourceCache

    override suspend fun getStatus(): RuntimeStatus {
        return runner.statusFlow.value
    }

    override suspend fun isRootfsInstalled(): Boolean = withContext(Dispatchers.IO) {
        // Только локальные файлы и метка установки: вопрос «установлено ли» не зависит от сети.
        runCatching {
            val installer = RootfsInstaller(layout)
            // Сначала откатываем установку, прерванную kill/выключением во время замены
            // rootfs, и убираем брошенный staging. Раньше это делалось только при следующей
            // установке, а проверка при запуске смотрела на недоделанный каталог.
            installer.recoverInterruptedInstall()
            val installed = installer.installedVersion() ?: return@runCatching false
            val marker = installer.readMarker()
            val currentAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            if (!marker?.abi.isNullOrBlank() && currentAbi.isNotBlank() && marker.abi != currentAbi) {
                return@runCatching false
            }
            File(layout.rootfsDir, "etc/os-release").isFile ||
                File(layout.rootfsDir, "usr/bin/env").isFile
        }.getOrDefault(false)
    }

    override suspend fun installedRootfsVersion(): String? = withContext(Dispatchers.IO) {
        RootfsInstaller(layout).installedVersion()
    }

    override suspend fun availableRootfsVersion(): String? = withContext(Dispatchers.IO) {
        // Доступный образ — тот, что закреплён в этом APK (G-41).
        runCatching { manifestSource().resolve()?.runtimeVersion }.getOrNull()
    }

    override suspend fun isRootfsUpdateAvailable(): Boolean = withContext(Dispatchers.IO) {
        val installed = installedRootfsVersion()
        val available = availableRootfsVersion()
        installed != null && available != null && installed != available
    }

    override suspend fun rootfsUpdateKind(): RootfsUpdateKind = withContext(Dispatchers.IO) {
        val available = runCatching { manifestSource().resolve() }.getOrNull()
        decideRootfsUpdateKind(
            installed = installedRootfsVersion(),
            available = available?.runtimeVersion,
            installedPatched = installedAndroidPatched(),
            availablePatched = available?.androidPatched == true,
        )
    }

    /** Вшиты ли в установленный образ патчи Android на этапе сборки. */
    private fun installedAndroidPatched(): Boolean =
        runCatching { RootfsInstaller(layout).readMarker()?.androidPatched == true }.getOrDefault(false)

    override suspend fun installedBridgeVersion(): String? = withContext(Dispatchers.IO) {
        runningBridgeVersion() ?: BridgePluginInstaller(context, layout).installedVersion()
    }

    /**
     * Версия плагина, которая **сейчас работает** в Host.
     *
     * `package.json` на диске может расходиться с работающим кодом (поставили, но не перезапустили),
     * а для обновления важно «какая версия работает». Host не запущен, мост недоступен или плагин не загружен — null,
     * тогда вызывающий берёт версию с диска.
     */
    private suspend fun runningBridgeVersion(): String? = runCatching {
        HostRuntimeHolder.runningBridgeVersionProvider?.invoke()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun assessUpdates(): UpdateAssessment = withContext(Dispatchers.IO) {
        // Каждый шаг терпит ошибки отдельно: ни один сбой не должен превращать все версии на странице в «неизвестно».
        // Цель плагина — версия, закреплённая в APK, без запросов к чужому репозиторию (G-42).
        val manifest = runCatching { BridgePluginManifest.load(context) }.getOrNull()
        val latest = manifest?.version?.takeIf { it.isNotBlank() }
        val installedRootfs = runCatching { installedRootfsVersion() }.getOrNull()
        val availableManifest = runCatching { manifestSource().resolve() }.getOrNull()
        val installedPlugin = runCatching {
            runningBridgeVersion() ?: BridgePluginInstaller(context, layout).installedVersion()
        }.getOrNull()
        assessUpdate(
            installed = installedRootfs,
            available = availableManifest?.runtimeVersion,
            latestPlugin = latest,
            installedPlugin = installedPlugin,
            installedPatched = installedAndroidPatched(),
            availablePatched = availableManifest?.androidPatched == true,
        )
    }

    override suspend fun updateBridgePlugin(
        version: String,
        onProgress: (Float, String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        lastStartResult = null
        val manifest = BridgePluginManifest.load(context)
            ?: return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("The APK has no bundled plugin manifest (assets/${BridgePluginManifest.ASSET})", "В APK нет встроенного манифеста плагинов (assets/${BridgePluginManifest.ASSET})"))
            )
        if (manifest.repository.isBlank()) {
            return@withContext Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Plugin manifest has no install source", "В манифесте плагинов не указан источник")))
        }
        // Ставится только версия, закреплённая в APK, и только с её SHA-256 (G-42).
        if (version != manifest.version) {
            return@withContext Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Only plugin ${manifest.version} pinned in this APK can be installed", "Можно установить только плагин ${manifest.version}, закреплённый в этом APK")))
        }
        BridgePluginInstaller(context, layout).installFromRelease(
            repository = manifest.repository,
            version = version,
            contentSha256 = manifest.contentSha256,
            onProgress = onProgress,
        )
    }

    override suspend fun installRootfs(onProgress: (Float, String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        // Новый rootfs — новая попытка: прошлая ошибка запуска не должна блокировать прогрев.
        lastStartResult = null
        layout.ensureDirectories()
        val installer = RootfsInstaller(layout)
        // Манифест — только встроенный в APK: адреса и SHA-256 образа закреплены нашей сборкой (G-41).
        val manifest = try {
            manifestSource().resolve()
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException(HostStrings.get(R.string.rt_manifest_failed, "Could not read the rootfs manifest: %1\$s", e.message))
            )
        } ?: return@withContext Result.failure(
            IllegalStateException(HostStrings.get(R.string.rt_manifest_missing, "The APK has no valid rootfs manifest with pinned SHA-256"))
        )
        onProgress(0f, HostStrings.get(R.string.rt_prepare, "Preparing to install %1\$s…", manifest.baseDistro.ifBlank { "rootfs" }))

        // Сначала rootfs из APK; иначе архив для первого запуска по ABI.
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull {
            manifest.assetArchives.containsKey(it) || manifest.downloadUrls.containsKey(it)
        }
        val assetPath = abi?.let { manifest.assetArchives[it] }
        val result = if (abi != null && !assetPath.isNullOrBlank()) {
            val cacheArchive = File(layout.cacheDir, "rootfs-$abi.tar.gz")
            val expectedSha = manifest.assetSha256[abi]
            if (!cacheArchive.isFile || cacheArchive.length() == 0L) {
                onProgress(0.02f, HostStrings.get(R.string.rt_bundled_extract, "Extracting the bundled rootfs (%1\$s)…", abi))
                try {
                    context.assets.open(assetPath).use { input ->
                        cacheArchive.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 1 shl 20)
                        }
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.rt_bundled_failed, "Could not extract the bundled rootfs: %1\$s", e.message))
                    )
                }
            }
            installer.installFromArchive(manifest, cacheArchive, expectedSha) { progress ->
                onProgress(progress.progress, progress.step)
            }
        } else {
            installer.install(manifest, abi = abi) { progress ->
                onProgress(progress.progress, progress.step)
            }
        }
        if (result.isSuccess) {
            // Новый образ заменил файлы движка в usr/local, а метки патчей и shim лежат в
            // /root/.dsh (данные пользователя, переживают смену образа). Если не сбросить старые метки,
            // applyBundledRuntimePatches решит, что патчи уже стоят, и выйдет —
            // новый образ запустится с непропатченным движком (под proot снова ENOSYS).
            File(layout.rootfsDir, "root/.dsh/.runtime-patch-revision").delete()
            File(layout.rootfsDir, "root/.dsh/.addon-shim-revision").delete()
            applyBundledRuntimePatches()
            RuntimeAddonShim.ensure(layout)
            runner.markInstalled()
            if (!layout.prootBinary.isFile) {
                onProgress(1f, com.deepseek.dsh.core.model.Tr.t("rootfs installed; ${manifest.prootLibrary} is missing (must be packaged per ABI in jniLibs)", "rootfs установлен; нет ${manifest.prootLibrary} (нужно упаковать в jniLibs под ABI)"))
            }
        }
        result
    }

    /**
     * Убрать процессы proot/node, оставшиеся от прошлого сбоя: несколько proot над одним rootfs вызывают
     * в glibc «double free or corruption».
     */
    private fun killStaleHostProcesses() {
        val procRoot = File("/proc")
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmdline.contains("libproot-userland") || cmdline.contains("dsh/lib/bin.js")) {
                runCatching { android.os.Process.sendSignal(pid, 9) }
            }
        }
        Thread.sleep(50)
    }

    /**
     * Патчи приложения: исправляют в DSH пути, которые под proot/Android дают ENOSYS, — разбор путей,
     * снятие файловых блокировок и временные папки spill — без повторной загрузки всего rootfs.
     */
    private fun applyBundledRuntimePatches() {
        // Образ со вшитыми патчами: файлы DSH уже «исходник + патч», замена целыми файлами из APK
        // вернула бы старый движок. Остаётся только Node preload в /root/.dsh.
        if (installedAndroidPatched()) return
        // Патчи копируются только при смене версии — иначе каждый запуск переписывал бы ~1 МБ JS и тормозил Host.
        val patchMarker = File(layout.rootfsDir, "root/.dsh/.runtime-patch-revision")
        // 34: внутренние модули app-boot — через --expose-internals, в обход
        // node-addon-require-builtin без двоичного файла платформы (прямая причина Cannot find module при запуске Host).
        // 35: таймаут сокета моста у bash / fs-search — с 15 с до 30 мин, долгие команды
        // больше не обрываются android bridge timeout.
        // 36: запасной fs.link только копирует, не удаляя исходник, — исправляет ATTACHMENT_WRITE_FAILED
        // хранилища вложений (после успешного link оно само делает unlink staging).
        // 37: канал управления fd7 подпроцесса снова настоящий pipe (раньше "ignore",
        // и PTC worker workflow получал null control и падал на reading 'on' of null).
        // 38: запросы bash / fs-search к мосту устройства несут локальный токен; мост проверяет его fail-closed —
        // другие приложения на телефоне не выполнят команды через порт loopback.
        // 39: инструмент bash снова выполняется в контейнере Host (не через android_exec_shell); параметры
        // proot Host совпадают с DSHA (-0 / --kill-on-exit / -L / привязки system, apex, fd).
        // fs-search по-прежнему через мост устройства: он делает find/grep в proot на стороне Android.
        val patchRevision = "39"
        if (patchMarker.isFile && patchMarker.readText().trim() == patchRevision) return
        val patches = mapOf(
            "rootfs-patches/dsh-app-boot-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js",
            "rootfs-patches/dsh-spill-local-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-spill-local/lib/index.js",
            "rootfs-patches/dsh-atomic-write-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-atomic-write/lib/index.js",
            "rootfs-patches/dsh-plugin-package-inventory-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-plugin-package-inventory-deepseek/lib/index.js",
            "rootfs-patches/dsh-subprocess-output.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/output.js",
            "rootfs-patches/dsh-subprocess-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/index.js",
            "rootfs-patches/dsh-subprocess-runner-launch.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js",
            "rootfs-patches/dsh-fs-local-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
            "rootfs-patches/dsh-tool-bash-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-bash/lib/index.js",
            "rootfs-patches/dsh-tool-fs-search-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-fs-search/lib/index.js",
            "rootfs-patches/dsh-storage-json-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-storage-json/lib/index.js",
            // Разбор пакетов клиентских модулей через Loader под Android/proot ничего не находит,
            // манифест запуска пуст и оболочка браузера не стартует; патч читает манифест
            // прямо из node_modules дерева.
            "rootfs-patches/dsh-client-modules-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js",
            // Проверка модели SenseVoice через fs.access; proot возвращает ENOSYS — проверяем
            // существование через stat, проверки размера и SHA-256 остаются.
            "rootfs-patches/dsh-speech-sensevoice-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
                "@deepseek-ai/dsh-experimental-speech-to-text-sensevoice/lib/index.js",
            // Под proot и при удалённом вызове spec.workdir у bash может быть пуст, а схема вывода
            // требует cwd; подставляем result.cwd/process.cwd, чтобы результат инструмента не считался invalid.
            "rootfs-patches/dsh-tool-bash-index.js" to
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
                "@deepseek-ai/dsh-tool-bash/lib/index.js",
        )
        patches.forEach { (asset, relative) ->
            val target = File(layout.rootfsDir, relative)
            runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        File(layout.rootfsDir, "root/.dsh/tmp").mkdirs()
        runCatching {
            patchMarker.parentFile?.mkdirs()
            patchMarker.writeText(patchRevision)
        }
    }

    /**
     * Подключить bridge-плагин к слою web-app, а не к bundles профиля.
     *
     * `dsh.profile.bundles` профиля сверяется при каждом запуске: на устройстве нет доступного
     * источника pnpm/git, внешняя зависимость не подтверждается, запись удаляется и маршруты моста дают 404 —
     * воспроизводится одним перезапуском. Патч самого web-app — «состояние из установки», эта сверка
     * его не трогает, поэтому на этом слое плагин держится.
     *
     * Повторный вызов безопасен: уже добавленное не дописывается, файл не растёт.
     */

    /**
     * Добавить строку Loader установленным сторонним плагинам.
     *
     * Пакет в node_modules профиля Host сам не подключает: `dsh.profile.bundles` профиля
     * сверяется при каждом запуске и без источника pnpm/git запись удаляется; `cordis.patch.yml` профиля
     * тоже переписывается. Патч web-app — «состояние из установки», его это не касается, поэтому строки сторонних плагинов — здесь.
     *
     * Только пакеты не из `@deepseek-ai/` и только если в пакете есть клиентская половина (`dsh.client`) —
     * признак «пакет появится в интерфейсе». Повторный вызов безопасен: есть строка — пропуск.
     */
    private fun applyInstalledPluginRows() {
        val webAppPatch = File(
            layout.rootfsDir,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
                "@deepseek-ai/dsh-web-app/cordis.patch.yml",
        )
        if (!webAppPatch.isFile) return
        val profileModules = File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules")
        val packages = profileModules.listFiles()?.filter { it.isDirectory } ?: return
        runCatching {
            var current = webAppPatch.readText()
            // 1. Сначала убрать отдельные строки подключения, которые этот класс добавлял раньше (вид — в prunePluginRows).
            //
            // Без этого два вида сбоев:
            // - строка есть, а пакета нет — Loader создаёт сбойную запись, и весь веб-клиент
            //   застревает на странице ошибки запуска;
            // - один пакет подключён дважды (кавычки scoped-пакета, блок маркера установщика) —
            //   плагин применяется дважды; например, genui второй раз регистрирует одноимённый prompt section,
            //   выбрасывает ошибку, и плагин выключается.
            // Удаляются только «свои» строки: патч, который автор плагина выпустил с пакетом (установщик пишет его
            // по маркеру как есть), выглядит так же — его надо беречь, иначе удалятся строки самого плагина.
            val protectedPatches = readInstalledBundlePatches(profileModules)
            val (pruned, removed) = prunePluginRows(current, protectedPatches)
            if (removed.isNotEmpty()) {
                current = pruned
                webAppPatch.writeText(current)
            }
            // 2. Затем добавить строки установленным плагинам (повтор безопасен). Пакеты, уже подключённые
            // в блоке маркера установщика (с любыми кавычками), пропускаются.
            val appended = StringBuilder()
            for (dir in packages) {
                val name = dir.name
                // Официальные пакеты подключает слой bundle профиля — лишняя строка загрузила бы их дважды;
                // сторонние scoped-пакеты (@scope/name) подключаются, исключён только официальный scope.
                if (name == "@deepseek-ai" || name.startsWith(".")) continue
                if (name.startsWith("@")) {
                    val scopeDir = dir
                    scopeDir.listFiles()?.forEach { scoped ->
                        if (!scoped.isDirectory) return@forEach
                        val scopedName = "${name}/${scoped.name}"
                        if (!declaresClientHalf(scoped)) return@forEach
                        if (rowMounted(current, scopedName) || appended.contains(scopedName)) return@forEach
                        appended.appendLine().append("- insert:").appendLine().append("    - id: \"").append(scopedName).append("\"")
                            .appendLine().append("      name: \"").append(scopedName).append("\"").appendLine()
                    }
                    continue
                }
                if (name == "dsh-mobile-bridge-plugin") continue
                if (rowMounted(current, name) || appended.contains(name)) continue
                if (!declaresClientHalf(dir)) continue
                appended.appendLine().append("- insert:").appendLine().append("    - id: ").append(name)
                    .appendLine().append("      name: ").append(name).appendLine()
            }
            if (appended.isNotEmpty()) webAppPatch.appendText(appended.toString())
        }
    }

    /**
     * Патч cordis, выпущенный с установленным плагином (`dsh.bundle.patch` в `package.json`).
     *
     * Установщик пишет эти тексты в патч web-app как есть (с меткой `# android-installed:`).
     * Они могут совпадать по виду со строками этого класса, поэтому при очистке их защищает содержимое, а не вид.
     */
    private fun readInstalledBundlePatches(profileModules: File): List<String> {
        val texts = mutableListOf<String>()
        fun readPackage(dir: File) {
            val manifest = File(dir, "package.json")
            val text = runCatching { manifest.readText() }.getOrNull() ?: return
            val patchPath = runCatching {
                org.json.JSONObject(text)
                    .optJSONObject("dsh")
                    ?.optJSONObject("bundle")
                    ?.optString("patch")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: return
            val file = File(dir, patchPath)
            runCatching { file.readText() }.getOrNull()?.let { texts += it }
        }
        for (dir in profileModules.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            if (dir.name.startsWith("@")) {
                dir.listFiles()?.filter { it.isDirectory }?.forEach(::readPackage)
            } else {
                readPackage(dir)
            }
        }
        return texts
    }

    /** Подключён ли пакет в патче (`name:` в одинарных, двойных кавычках или без них). */
    private fun rowMounted(patchText: String, packageName: String): Boolean =
        patchText.contains("name: $packageName") ||
            patchText.contains("name: \"$packageName\"") ||
            patchText.contains("name: '$packageName'")

    /**
     * Объявлена ли в `package.json` половина для браузера (`dsh.client`).
     *
     * Проверка по структуре JSON, а не по тексту: подстроки `"dsh"`/`"client"` встречаются в именах зависимостей
     * и description, и ошибка подключила бы чистую службу Host в браузер как плагин интерфейса.
     */
    private fun declaresClientHalf(dir: File): Boolean {
        val manifest = File(dir, "package.json")
        val text = runCatching { manifest.readText() }.getOrNull() ?: return false
        return runCatching {
            val root = org.json.JSONObject(text)
            val dsh = root.optJSONObject("dsh") ?: return@runCatching false
            dsh.has("client")
        }.getOrDefault(false)
    }

    /**
     * Убрать все отдельные блоки подключения, добавленные этим классом (без проверок).
     *
     * Узнаётся только один вид: верхний `- insert:`, сразу две строки `- id: X` / `name: X`, и больше
     * никаких полей (блоки insert из патчей обычно имеют после name ещё config/disabled
     * или несколько строк; блоки маркера установщика несут комментарий `# android-installed:`
     * и многострочный патч плагина, поэтому не удаляются). Пакеты официального scope не трогаются.
     *
     * Затем вызывающий заново строит нужные строки по текущему профилю, поэтому «есть ли ещё пакет» здесь не важно:
     * сначала удаляется всё, чтобы один и тот же вид не копился дублями между запусками и установками.
     *
     * @param text содержимое файла патча.
     * @param protectedPatches патчи, выпущенные с плагинами; остаются как есть.
     * @returns новое содержимое и имена удалённых пакетов.
     */
    private fun prunePluginRows(
        text: String,
        protectedPatches: List<String>,
    ): Pair<String, List<String>> {
        val lines = text.lines().toMutableList()
        val removed = mutableListOf<String>()
        var index = 0
        while (index + 2 < lines.size) {
            if (lines[index] != "- insert:") {
                index += 1
                continue
            }
            val idLine = lines[index + 1]
            val nameLine = lines[index + 2]
            if (!idLine.startsWith("    - id: ") || !nameLine.startsWith("      name: ")) {
                index += 1
                continue
            }
            val following = lines.getOrNull(index + 3)
            val standalone = following == null || following.isBlank() ||
                following.startsWith("- ") || following.startsWith("#")
            if (!standalone) {
                index += 1
                continue
            }
            // Строки, выпущенные автором с пакетом: при полном совпадении остаются.
            val blockText = "${lines[index]}\n${lines[index + 1]}\n${lines[index + 2]}"
            if (protectedPatches.any { it.contains(blockText) }) {
                index += 3
                continue
            }
            val packageName = nameLine.removePrefix("      name: ").trim().trim('"', '\'')
            if (packageName.isEmpty() || packageName.startsWith("@deepseek-ai/")) {
                index += 3
                continue
            }
            removed += packageName
            repeat(3) { if (index < lines.size) lines.removeAt(index) }
            if (index < lines.size && lines[index].isBlank()) lines.removeAt(index)
        }
        return lines.joinToString("\n") to removed
    }
    /**
     * Постоянное место патча слоя web-app в rootfs.
     *
     * Этот слой — «состояние из установки», сверка профиля его не трогает; здесь строки моста и сторонних
     * плагинов. Поэтому же мёртвые строки после удаления или неполной установки остаются здесь навсегда.
     */
    private fun webAppPatchFile(): File = File(
        layout.rootfsDir,
        "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-web-app/cordis.patch.yml",
    )

    /** Общая папка пакетов `@deepseek-ai` в rootfs (опора разбора официальных bundle). */
    private fun rootfsGlobalModulesDir(): File =
        File(layout.rootfsDir, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules")

    /**
     * Установлен ли пакет из `name:` строки подключения.
     *
     * Сначала `node_modules` профиля; `@deepseek-ai/<пакет>` и bridge-плагин могут лежать и в общей папке
     * rootfs (оттуда разбираются официальные bundle). `name:` может быть подпутём пакета (например,
     * `@deepseek-ai/dsh-tool-subagent/model-selection-settings`), поэтому сначала берётся корень пакета —
     * иначе удалились бы законные строки подпутей самого web-app.
     */
    private fun pluginPackageInstalled(packageName: String): Boolean {
        val rootName = packageRootName(packageName)
        if (rootName.isBlank()) return false
        val profileModules = File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules")
        if (File(profileModules, "$rootName/package.json").isFile) return true
        val mayLiveInRootfs = rootName.startsWith("@deepseek-ai/") ||
            rootName == BridgePluginManifest.DEFAULT_PACKAGE
        if (!mayLiveInRootfs) return false
        return File(rootfsGlobalModulesDir(), "$rootName/package.json").isFile
    }

    /** `@scope/pkg/sub/path` / `pkg/sub` → имя корня пакета. */
    private fun packageRootName(specifier: String): String {
        val name = specifier.trim().trim('"', '\'')
        if (name.isEmpty()) return ""
        val parts = name.split('/')
        return when {
            name.startsWith("@") -> parts.take(2).joinToString("/")
            parts[0].isNotEmpty() -> parts[0]
            else -> name
        }
    }

    /**
     * Перед запуском починить висящие строки подключения в патче слоя web-app.
     *
     * Только «свой» вид из трёх строк (`- insert:` + `- id:` + `name:`, за которыми
     * пустая строка, следующая верхняя строка или комментарий); блоки с `config`/`disabled` и другими полями —
     * патчи плагинов, их не трогаем. Удаляются два вида строк:
     * 1. пакета из `name:` больше нет (остаток удаления, неполная установка) — одной такой записи
     *    хватает, чтобы веб-клиент застрял на странице ошибки запуска;
     * 2. повторное подключение того же имени (первое остаётся) — двойное применение плагина сразу падает.
     *
     * В отличие от [prunePluginRows], решает «есть ли пакет», поэтому убирает и висящие строки
     * `@deepseek-ai/<пакет>` (prunePluginRows официальный scope из осторожности не трогает).
     */
    private fun sanitizeWebAppPatchRows() {
        val webAppPatch = webAppPatchFile()
        if (!webAppPatch.isFile) return
        try {
            val original = webAppPatch.readText()
            val profileModules = File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules")
            // Патч, выпущенный с плагином, может выглядеть точно как «своя» строка этого класса —
            // защищаем по содержимому, иначе строки самого плагина удалились бы как остатки.
            val protectedPatches = readInstalledBundlePatches(profileModules)
            // Строка моста идёт с блоком `config:`, не в «своём» виде; если пакета нет, весь блок
            // удаляется — одной записи без import хватает, чтобы веб-клиент не запустился.
            val bridgeInstalled = pluginPackageInstalled(BridgePluginManifest.DEFAULT_PACKAGE)
            val (sanitized, dropped) = dropDanglingMountRows(
                text = original,
                protectedPatches = protectedPatches,
                dropBridgeRow = !bridgeInstalled,
            )
            if (dropped.isEmpty() || sanitized == original) return
            writeTextAtomically(webAppPatch, sanitized)
            dropped.forEach { name ->
                android.util.Log.w(TAG, "web-app patch: removed stale plugin mount line: $name")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "web-app patch: failed to check mount lines", e)
        }
    }

    /** Сначала временный файл, потом переименование: оборванная запись не оставит половину YAML, ломающую запуски. */
    private fun writeTextAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Текстовая часть [sanitizeWebAppPatchRows]: новое содержимое и имена удалённых пакетов.
     *
     * Удаление по строкам, а не пересериализация YAML: в патче много комментариев и выражений `!!js`,
     * они должны остаться как есть — перестановка изменила бы смысл.
     *
     * @param protectedPatches патчи, выпущенные с плагинами; блоки с тем же содержимым остаются.
     * @param dropBridgeRow пакета bridge-плагина нет — удалить весь его блок подключения с `config:`.
     */
    private fun dropDanglingMountRows(
        text: String,
        protectedPatches: List<String> = emptyList(),
        dropBridgeRow: Boolean = false,
    ): Pair<String, List<String>> {
        val bridgeResult = if (dropBridgeRow) {
            dropBridgeMountBlock(text.lines().toMutableList())
        } else {
            text.lines().toMutableList() to mutableListOf<String>()
        }
        val lines = bridgeResult.first
        val dropped = bridgeResult.second
        val keptNames = mutableSetOf<String>()
        var index = 0
        while (index + 2 < lines.size) {
            if (lines[index] != "- insert:") {
                index += 1
                continue
            }
            val idLine = lines[index + 1]
            val nameLine = lines[index + 2]
            if (!idLine.startsWith("    - id: ") || !nameLine.startsWith("      name: ")) {
                index += 1
                continue
            }
            val following = lines.getOrNull(index + 3)
            val standalone = following == null || following.isBlank() ||
                following.startsWith("- ") || following.startsWith("#")
            if (!standalone) {
                // Есть config/disabled и другие поля: патч самого автора плагина, не трогаем.
                index += 1
                continue
            }
            val blockText = "${lines[index]}\n${lines[index + 1]}\n${lines[index + 2]}"
            if (protectedPatches.any { it.contains(blockText) }) {
                // Совпадает с патчем, выпущенным с плагином: это его строка, а не наш остаток.
                index += 3
                continue
            }
            val name = nameLine.removePrefix("      name: ").trim().trim('"', '\'')
            val duplicated = name.isNotEmpty() && name in keptNames
            if (name.isNotEmpty() && !duplicated && pluginPackageInstalled(name)) {
                keptNames += name
                index += 3
                continue
            }
            dropped += name.ifEmpty { idLine.removePrefix("    - id: ").trim().trim('"', '\'') }
            repeat(3) { if (index < lines.size) lines.removeAt(index) }
            if (index < lines.size && lines[index].isBlank()) lines.removeAt(index)
        }
        return lines.joinToString("\n") to dropped
    }

    /**
     * Удалить весь блок подключения bridge-плагина (с `config:`).
     *
     * Узнаётся только блок от верхнего `- insert:` до следующей верхней строки, в котором есть id моста;
     * так не удаляются другие блоки insert и закомментированный текст.
     */
    private fun dropBridgeMountBlock(
        lines: MutableList<String>,
    ): Pair<MutableList<String>, MutableList<String>> {
        val dropped = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (lines[index] != "- insert:") {
                index += 1
                continue
            }
            var end = index + 1
            while (end < lines.size &&
                !lines[end].startsWith("- ") && !lines[end].startsWith("#")
            ) {
                end += 1
            }
            val block = lines.subList(index, end)
            if (block.any { it.contains(BRIDGE_BUNDLE_ID) }) {
                dropped += BridgePluginManifest.DEFAULT_PACKAGE
                repeat(end - index) { lines.removeAt(index) }
                if (index < lines.size && lines[index].isBlank()) lines.removeAt(index)
                continue
            }
            index = end
        }
        return lines to dropped
    }

    private fun applyBridgeBundleRow() {
        val webAppPatch = webAppPatchFile()
        if (!webAppPatch.isFile) return
        // Без пакета строка не добавляется никогда: одной записи Loader без import хватает, чтобы весь веб-клиент
        // не запустился, — это хуже, чем «нет моста». Строка появится при следующем запуске после установки плагина.
        if (!pluginPackageInstalled(BridgePluginManifest.DEFAULT_PACKAGE)) return
        val row = runCatching {
            context.assets.open("rootfs-patches/bridge-bundle-row.yml").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        runCatching {
            val current = webAppPatch.readText()
            if (current.contains(BRIDGE_BUNDLE_ID)) return
            webAppPatch.appendText("\n$row\n")
        }
    }

    private companion object {
        /** Тег Logcat. */
        const val TAG = "DshHostRuntime"

        /** Сколько автоматический прогрев не повторяет неудачный запуск Host. */
        const val AUTO_START_COOLDOWN_MS = 5 * 60_000L

        /** Сколько ждать URL `dsh web:` при запуске; дольше — ошибка запуска, а не вечный STARTING. */
        const val ENDPOINT_TIMEOUT_MS = 90_000L

        /** Интервал опроса состояния, пока ждём порт: при сбое процесса — сразу выход, а не ожидание до таймаута. */
        const val ENDPOINT_POLL_INTERVAL_MS = 200L

        /** Сколько последних строк вывода приложить к тексту ошибки таймаута (как в настройках). */
        const val LOG_TAIL_ON_ERROR = 8

        /** id строки bridge-плагина в дереве cordis — для проверки повторов. */
        const val BRIDGE_BUNDLE_ID = "id: dsh-mobile-bridge-plugin"

        /** Домены HTTPS, объявленные в пакетах плагинов, — для общего предварительного разбора hosts. */
    }

    /** Некритичные задачи запуска (дописать hosts и т. п.) — продолжаются после возврата start. */
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** Разобрать домены и записать в /etc/hosts; неразрешённые пропускаются. */
    private fun writeHostsFile(hosts: List<String>) {
        runCatching {
            val entries = hosts.distinct().take(160).mapNotNull { host ->
                runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull()
                    ?.let { "$it $host" }
            }
            if (entries.isNotEmpty()) {
                File(layout.rootfsDir, "etc/hosts").writeText(entries.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Найти домены, объявленные в JS установленных плагинов (дополнение к предварительному разбору hosts). */
    private fun scanDeclaredHosts(): List<String> = runCatching {
        val urlHost = Regex("""https://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)""")
        val modules = File(layout.rootfsDir, "root/.dsh/profiles/web/node_modules")
        if (!modules.isDirectory) return@runCatching emptyList()
        modules.walkTopDown()
            .maxDepth(3)
            .filter { file ->
                file.isFile && file.length() in 1..2_000_000 &&
                    file.extension.lowercase() in setOf("js", "mjs", "cjs")
            }
            .take(240)
            .flatMap { file ->
                runCatching {
                    urlHost.findAll(file.readText().take(400_000))
                        .map { it.groupValues[1] }
                        .toList()
                }.getOrDefault(emptyList())
            }
            .filter { host -> host.contains('.') }
            .toList()
    }.getOrDefault(emptyList())

    /** Номер последней завершённой попытки запуска и её результат (см. [start]). */
    private val startAttempts = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var lastStartResult: Result<RuntimeStatus>? = null
    @Volatile
    private var lastStartFailedAt = 0L

    override suspend fun warmUp(workspacePath: String): Result<RuntimeStatus> {
        val failure = lastStartResult?.takeIf { it.isFailure }
        if (failure != null && !runner.isRunning() &&
            System.currentTimeMillis() - lastStartFailedAt < AUTO_START_COOLDOWN_MS
        ) {
            return failure
        }
        return start(workspacePath)
    }

    /**
     * При открытии приложения Host запрашивают сразу несколько мест (Application,
     * экран запуска, проверка rootfs). Раньше каждый вызов из очереди за [startMutex]
     * после неудачной попытки заново поднимал proot — несколько тяжёлых запусков
     * подряд и минуты до показа ошибки. Вызовы, которые ждали, пока шла попытка,
     * теперь получают её результат; явный повтор (после ошибки) запускает новую.
     */
    override suspend fun start(workspacePath: String): Result<RuntimeStatus> {
        val observedAttempt = startAttempts.get()
        return startMutex.withLock {
            if (runner.isRunning()) return@withLock Result.success(runner.statusFlow.value)
            val finishedMeanwhile = lastStartResult
            if (startAttempts.get() != observedAttempt && finishedMeanwhile != null) {
                return@withLock finishedMeanwhile
            }
            startLocked(workspacePath).also {
                lastStartResult = it
                if (it.isFailure) lastStartFailedAt = System.currentTimeMillis()
                startAttempts.incrementAndGet()
            }
        }
    }

    private suspend fun startLocked(workspacePath: String): Result<RuntimeStatus> {
        // NonCancellable: если область вызывающего отменят (смена страницы, перекомпоновка, «Назад»), runner
        // не должен застрять в STARTING. Запуск либо доходит до конца, либо — до явной ERROR; страхуют сторож и таймаут.
        val result = withContext(Dispatchers.IO + NonCancellable) {
        layout.ensureDirectories()
        // Прогрев из Application идёт сюда напрямую: откатываем прерванную замену rootfs до запуска.
        runCatching { RootfsInstaller(layout).recoverInterruptedInstall() }

        val hasRootfs = File(layout.rootfsDir, "etc/os-release").isFile ||
            File(layout.rootfsDir, "usr/bin/env").isFile
        if (!hasRootfs) {
            return@withContext Result.failure(
                IllegalStateException(HostStrings.get(R.string.rt_not_installed, "The rootfs is not installed: install it from the runtime page first"))
            )
        }
        // Патчи rootfs из APK заменяют файлы целиком по версии DSH из встроенного манифеста; если установленный образ —
        // другой версии DSH (например, репозиторий rootfs уже выпустил rc.3), патчи rc.2 смешали бы
        // версии в рабочем дереве. Образа со вшитыми патчами это не касается: внутри уже
        // «последний DSH + патчи», при запуске замена файлов просто пропускается.
        val expectedDsh = manifestSource().bundledDshVersion()
        val installedMarker = runCatching { RootfsInstaller(layout).readMarker() }.getOrNull()
        val installedRootfsVersion = installedMarker?.runtimeVersion
        if (installedMarker?.androidPatched != true &&
            installedRootfsVersion != null &&
            !RootfsNaming.isDshCompatible(installedRootfsVersion, expectedDsh)
        ) {
            return@withContext Result.failure(
                IllegalStateException(
                    HostStrings.get(R.string.rt_incompatible, "The rootfs is incompatible with this APK: installed %1\$s, this APK targets dsh%2\$s. Reinstall a matching rootfs in Settings → Runtime.", installedRootfsVersion, expectedDsh)
                )
            )
        }
        applyBundledRuntimePatches()
        // В образе может не быть двоичного файла платформы для node-addon-require-builtin; тогда подставляем
        // чистый JS (с --expose-internals), иначе Host завершится при загрузке Loader.
        RuntimeAddonShim.ensure(layout)
        // Сначала убрать висящие строки после удаления или неполной установки, потом добавлять: наоборот — удалились бы только что добавленные.
        // Одной строки Loader без import хватает, чтобы весь веб-клиент застрял на странице ошибки запуска.
        sanitizeWebAppPatchRows()
        // Слой подключения плагинов сверяется при каждом запуске: строка Loader «id есть, пакета нет» после удаления
        // оставляет весь веб-клиент на странице ошибки запуска (хватает одной сбойной записи), а файл патча
        // постоянный — без починки так и останется сломанным. Сверка безопасна при повторе и стоит одного чтения.
        applyBridgeBundleRow()
        applyInstalledPluginRows()
        // Bridge-плагин — из rootfs или установкой с GitHub; кода в APK больше нет.
        runCatching {
            val installer = BridgePluginInstaller(context, layout)
            installer.ensureProfileEntry()
        }
        killStaleHostProcesses()
        if (!layout.prootBinary.isFile) {
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("${layout.prootBinary.name} is missing: proot for this ABI must be packaged in jniLibs", "Нет ${layout.prootBinary.name}: proot для этого ABI должен быть в jniLibs"))
            )
        }
        // Цели привязок proot должны существовать; папка настроек DSH создаётся заранее, чтобы первый mkdir не упал.
        File(layout.rootfsDir, "root/.dsh/profiles/web").mkdirs()
        // Профилю нужно разбирать bundle @deepseek-ai/* из своей папки — ссылка на общий node_modules rootfs.
        ensureProfilePresetLink(File(layout.rootfsDir, "root/.dsh/profiles/web"))
        File(layout.rootfsDir, "root/.dsh/sessions/--workspace--").mkdirs()
        File(layout.rootfsDir, "root/.dsh/storages").mkdirs()
        File(layout.rootfsDir, "root/.dsh/storages/session-projection").mkdirs()
        // GenUI initializes ArtifactRegistry and DesignStore whenever a profile reload
        // activates it. Create their parent directories on Android first so the loader
        // never depends on proot's recursive-mkdir behavior for a fresh storage root.
        File(layout.rootfsDir, "root/.dsh/genui/.designs").mkdirs()
        // Общий корень хранилища плагинов: подпапки плагины создают сами. Только корень, без привязки к именам плагинов.
        File(layout.rootfsDir, "root/.dsh/plugins").mkdirs()
        File(layout.rootfsDir, "root/.dsh/node-compile-cache").mkdirs()
        File(layout.rootfsDir, "root/.dsh/cpu-prof").mkdirs()
        File(workspacePath).mkdirs()
        // proot должен войти в цель привязки: /workspace в rootfs — папка, в которую можно войти.
        File(layout.rootfsDir, "workspace").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        listOf("spill", "launch", "shell", "staging").forEach {
            File(layout.rootfsDir, "root/.dsh/tmp/$it").mkdirs()
        }
        File(layout.rootfsDir, "root/.dsh/tmp").mkdirs()
        // Общий DNS: сначала DNS-серверы текущей сети Android — разрешается любой домен;
        // публичные DNS — запасной вариант, отдельных записей под каждый плагин или домен больше нет.
        val dnsServers = runCatching {
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            manager?.activeNetwork?.let { network ->
                manager.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val resolvers = (dnsServers + listOf("8.8.8.8", "1.1.1.1"))
            .filter { it.isNotBlank() && !it.contains(':') }
            .distinct()
            .take(3)
        File(layout.rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText(resolvers.joinToString("") { "nameserver $it\n" })
        }
        // Домены разрешаются заранее и пишутся в hosts — в обход EAI_AGAIN у glibc DNS внутри proot.
        //
        // Кроме постоянных доменов установки плагинов и самой платформы, просматриваются https-домены,
        // объявленные в пакетах установленных плагинов. Так любому плагину (OAuth, API, CDN) не нужна своя настройка hosts.
        // На критическом пути запуска — только несколько доменов самой платформы; обход node_modules
        // с доменами плагинов и разбор каждого DNS может занять от сотен миллисекунд до секунд, поэтому
        // это делается в фоне после старта процесса (glibc перечитывает /etc/hosts при каждом lookup, поздняя запись тоже работает).
        val infraHosts = listOf(
            "platform.deepseek.com",
            "api.deepseek.com",
            "github.com",
            "codeload.github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "registry.npmjs.org",
            "registry.npmmirror.com",
        )
        writeHostsFile(infraHosts)
        backgroundScope.launch {
            runCatching {
                val declared = scanDeclaredHosts()
                if (declared.isNotEmpty()) {
                    writeHostsFile((infraHosts + declared).distinct().take(160))
                }
            }
        }
        // После аварийного выхода может остаться блокировка записи credentials — убрать до запуска.
        File(layout.rootfsDir, "root/.dsh").listFiles()
            ?.filter { it.name.startsWith(".credentials.yaml.lock") }
            ?.forEach { it.delete() }

        // 1. Запустить локальный BridgeServer и получить порт loopback; токен пишется в DSH_HOME гостя,
        // патчи Host передают его в запросах (см. BridgeAuth).
        val bridge = BridgeServer(context, layout.bridgeTokenFile, workspacePath = workspacePath)
        val bridgePort = bridge.start()
        bridgeServer = bridge

        // 2. Запустить фоновую службу Android, чтобы процесс жил.
        val serviceIntent = Intent(context, HostService::class.java).apply {
            action = HostService.ACTION_START
        }
        // Когда приложение на переднем плане, обычный startService разрешён и не
        // требует вызвать startForeground за 5 секунд. С startForegroundService
        // медленный главный поток (холодный старт Compose) не успевал создать сервис,
        // и система убивала процесс RemoteServiceException — воспроизведено на
        // эмуляторе Android 8. Из фона startService бросает IllegalStateException,
        // тогда остаётся startForegroundService; сервис сам переходит в foreground.
        val started = runCatching { context.startService(serviceIntent) }.isSuccess
        if (!started && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { context.startForegroundService(serviceIntent) }
                .onFailure { android.util.Log.w(TAG, "HostService не запущен", it) }
        }

        // 3. Передать переменную DSH_ANDROID_BRIDGE_PORT в контейнер proot.
        // Терминалу и подпроцессам нужно разбирать uid в proot — заранее перезаписать passwd/group.
        layout.ensureIdentityOverlays()
        layout.repairProfilePatch()
        layout.ensureNodePatches()
        layout.ensureHostRuntimePatches()
        // Базовая точка git для workspace/changes ставится в фоне после запуска Host: для каждой
        // рабочей папки нужен отдельный proot+git, на критическом пути это заметно замедлило бы запуск.
        // Сделанное после первого хода записывающий тоже использует.
        val launchConfig = ProotCommandBuilder.LaunchConfig(
            layout = layout,
            userWorkspacePath = workspacePath,
            customEnv = mapOf(
                "DSH_ANDROID_BRIDGE_PORT" to bridgePort.toString()
            )
        )
        val cmd = ProotCommandBuilder.build(launchConfig)
        // Запуск через shell: прямой exec proot унаследовал бы окружение компоновщика процесса приложения,
        // и разбор модулей Node в proot отличался бы от ручного запуска.
        val launchCmd = listOf("/system/bin/sh", "-c", shellCommand(cmd))

        val logFile = File(layout.logsDir, "host_${System.currentTimeMillis()}.log")
        logFile.appendText("[CMD] ${cmd.joinToString(" ")}\n")
        val endpointDeferred = CompletableDeferred<ProcessOutputParser.ParsedEndpoint>()

        val launchResult = runner.launchProcess(
            command = launchCmd,
            workingDir = layout.baseDir,
            logFile = logFile,
            environment = mapOf(
                // Новый proot несёт свой loader: он тоже распаковывается из jniLibs в nativeLibraryDir.
                "PROOT_LOADER" to "${layout.nativeLibraryDir.absolutePath}/libproot-loader.so",
                "PROOT_TMP_DIR" to layout.cacheDir.absolutePath,
                // proot динамически зависит от libtalloc/libandroid-shmem — они распаковываются в nativeLibraryDir вместе с ним.
                "LD_LIBRARY_PATH" to layout.nativeLibraryDir.absolutePath,
            ),
            onEndpointFound = { endpoint ->
                        if (!endpointDeferred.isCompleted) {
                    endpointDeferred.complete(endpoint)
                }
            }
        )

        if (launchResult.isFailure) {
            bridge.stop()
            return@withContext Result.failure(launchResult.exceptionOrNull()!!)
        }
        backgroundScope.launch {
            runCatching { ensureWorkspaceGitBaseline(workspacePath) }
        }

        // Пока ждём порт, следим за процессом: сбой запуска DSH (плагин не импортируется) обычно
        // завершает его за секунды, ждать все 90 секунд таймаута незачем; состояние от сторожа уже
        // содержит код выхода и последний вывод — берём его. STOPPED — пользователь сам остановил запуск
        // (смахнул задачу или система забрала фоновую службу): это нормальный конец, не «таймаут запуска».
        val endpoint: ProcessOutputParser.ParsedEndpoint? = withTimeoutOrNull(ENDPOINT_TIMEOUT_MS) {
            while (!endpointDeferred.isCompleted) {
                when (runner.statusFlow.value.phase) {
                    RuntimePhase.ERROR, RuntimePhase.STOPPED -> return@withTimeoutOrNull null
                    else -> kotlinx.coroutines.delay(ENDPOINT_POLL_INTERVAL_MS)
                }
            }
            endpointDeferred.await()
        }

        if (endpoint == null) {
            val failed = runner.statusFlow.value
            if (failed.phase == RuntimePhase.ERROR) {
                // Процесс уже вышел во время запуска: оставить ошибку от сторожа (с кодом выхода и хвостом журнала),
                // а не затирать настоящую причину «таймаутом».
                bridge.stop()
                return@withContext Result.failure(
                    IllegalStateException(failed.lastError ?: HostStrings.get(R.string.rt_host_exited, "The Host process exited during startup"))
                )
            }
            if (failed.phase == RuntimePhase.STOPPED) {
                // Остановлено пользователем: STOPPED, процесс не убиваем, ERROR не пишем и мост не оставляем полузапущенным.
                bridge.stop()
                return@withContext Result.success(failed)
            }
            // Порт так и не появился: убить процесс и записать в состояние последний вывод. Пользователю нужна не
            // фраза «не удалось получить порт», а то, что Host напечатал последним.
            runner.terminate()
            val tail = runner.statusFlow.value.logTail.takeLast(LOG_TAIL_ON_ERROR)
            val detail = if (tail.isEmpty()) HostStrings.get(R.string.rt_no_output, "no output") else tail.joinToString("\n")
            val message = HostStrings.get(R.string.rt_host_timeout, "Host startup timed out (no port or token within %1\$d s):\n%2\$s", ENDPOINT_TIMEOUT_MS / 1000, detail)
            runner.markError(message)
            bridge.stop()
            return@withContext Result.failure(IllegalStateException(message))
        }

        // Напечатан Web URL — сервис уже слушает; дополнительные проверки HTTP/WS под Android
        // может сорвать сетевая политика, поэтому сразу переходим в RUNNING.
        runner.markReady()
        Result.success(runner.statusFlow.value)
        }
        return result
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        bridgeServer?.stop()
        bridgeServer = null

        val result = runner.terminate()
        val serviceIntent = Intent(context, HostService::class.java).apply {
            action = HostService.ACTION_STOP
        }
        // В фоне (например, после смахивания из недавних) startService может выбросить IllegalStateException;
        // процесс Host уже завершён, и остановка службы не должна ронять вызывающего.
        runCatching { context.startService(serviceIntent) }
        result
    }

    /**
     * Создать репозиторий git в каждой папке сессии рабочей области (повтор безопасен).
     *
     * Записывающий `workspace/changes` Host берёт снимок git как базу хода; без репозитория он
     * видит только изменения через файловые инструменты, а bash на телефоне идёт через shell Android — его изменения не видны.
     * В rootfs есть `/usr/bin/git`, поэтому здесь в proot один раз выполняется `git init`.
     */
    private suspend fun ensureWorkspaceGitBaseline(userWorkspacePath: String) = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(userWorkspacePath)
            val directories = root.listFiles()
                ?.filter { it.isDirectory && it.name != ".git" }
                .orEmpty()
            if (directories.isEmpty()) return@withContext
            directories.forEach { directory ->
                val gitDir = File(directory, ".git")
                if (gitDir.exists()) {
                    // Прерванный init оставляет неполный репозиторий без objects/refs (git пишет
                    // "not a git repository", и workspace/changes не получает базу).
                    val complete = File(gitDir, "objects").isDirectory &&
                        File(gitDir, "refs").isDirectory
                    if (complete) return@forEach
                    runCatching { SafeFiles.deleteTree(gitDir) }
                }
                val hostPath = "/workspace/" + directory.name
                val exec = ProotCommandBuilder.buildExec(
                    layout,
                    listOf("/usr/bin/git", "-C", hostPath, "init", "-q"),
                    // Без привязки /workspace git не видит папку проекта.
                    workspacePath = userWorkspacePath,
                )
                val builder = ProcessBuilder(exec.command)
                builder.environment().putAll(exec.environment)
                builder.redirectErrorStream(true)
                val process = builder.start()
                process.inputStream.close()
                process.waitFor()
            }
        }
    }
}





















/**
 * Строка для `sh -c`: каждый аргумент — в одинарных кавычках, кавычка внутри — `'\''`.
 * В одинарных кавычках оболочка ничего не раскрывает: имя папки вроде `a;rm -rf ~` или `$(…)`
 * остаётся именем, а не командой (раньше в кавычки брались только аргументы с пробелами).
 */
internal fun shellCommand(argv: List<String>): String =
    argv.joinToString(" ") { arg -> "'" + arg.replace("'", "'\\''") + "'" }
