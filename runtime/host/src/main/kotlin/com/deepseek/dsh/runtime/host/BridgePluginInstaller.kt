package com.deepseek.dsh.runtime.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Сведения о выпуске bridge-плагина (встроены в APK).
 *
 * Сам плагин в APK не входит: он ставится из архива исходников тега на GitHub. В APK только этот маленький список:
 * «откуда ставить» и «базовая версия при первой сборке», поэтому выпуск плагина не требует ни новой сборки APK,
 * ни новой загрузки rootfs.
 */
@Serializable
data class BridgePluginManifest(
    /** Имя пакета; должно совпадать с name в `package.json` репозитория плагина. */
    val packageName: String = DEFAULT_PACKAGE,
    /**
     * Базовая версия, то же, что часть `-bridge` в строке версии rootfs.
     *
     * Это **не** цель обновления: цель всегда — последний тег выпуска в репозитории плагина; этот список
     * выходит вместе с APK и отстаёт от репозитория. Поле оставлено, чтобы без сети сказать, «какая версия была при сборке».
     */
    val version: String = "",
    /** Источник: адрес репозитория GitHub; при установке скачивается архив тега `v<version>` из `<repo>`. */
    val repository: String = "",
) {
    companion object {
        /** Имя пакета по умолчанию — как в репозитории плагина. */
        const val DEFAULT_PACKAGE = "dsh-mobile-bridge-plugin"

        /** Путь встроенного списка в assets. */
        const val ASSET = "bridge-plugin.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Прочитать встроенный в APK список.
         * @param context контекст приложения.
         * @return список; нет или повреждён — null, тогда вход обновления скрывается.
         */
        fun load(context: Context): BridgePluginManifest? = runCatching {
            val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(BridgePluginManifest.serializer(), raw)
        }.getOrNull()
    }
}

/**
 * Установщик bridge-плагина внутри rootfs.
 *
 * Установка = скачать архив исходников выпущенного тега → проверить → распаковать в `node_modules` профиля.
 * `dsh plugin add` в Host не запускается: этот путь зависит от git/pnpm и DNS внутри proot и на телефоне
 * не работает (проверено: github.com из proot не разрешается), а pnpm в Host переписывает список
 * профиля — плагин на деле подключается строкой патча слоя web-app, список профиля ему не дом.
 */
class BridgePluginInstaller(
    private val context: Context,
    private val layout: RuntimeLayout,
    private val downloader: BridgeReleaseDownloader = BridgeReleaseDownloader(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Папка профиля; постоянное место DSH в rootfs. */
    private val profileDir: File
        get() = File(layout.rootfsDir, "root/.dsh/profiles/web")

    /**
     * Версия плагина, установленного в rootfs.
     *
     * Читается `package.json` в папке установки: это то, что реально на диске, быстрее, чем спрашивать DSH,
     * и не зависит от того, запущен ли Host.
     * @param packageName имя пакета.
     * @return версия; не установлено или не прочитать — null.
     */
    fun installedVersion(packageName: String = BridgePluginManifest.DEFAULT_PACKAGE): String? {
        val manifest = File(profileDir, "node_modules/$packageName/package.json")
        if (!manifest.isFile) return null
        return runCatching {
            val node = json.parseToJsonElement(manifest.readText())
            (node as? JsonObject)
                ?.get("version")
                ?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Поставить в rootfs выпущенную версию плагина.
     *
     * Целевая версия — последний выпуск репозитория плагина, а не встроенный список APK: список выходит с APK
     * и неизбежно отстаёт. До установки проверяются имя пакета, версия и вход в архиве; ошибка любого шага
     * установленный плагин не меняет.
     * @param repository адрес репозитория GitHub (`repository` из списка APK).
     * @param version выпущенная версия, например `1.1.0`.
     * @param onProgress ход и подписи шагов.
     * @return версия плагина, прочитанная после успеха.
     */
    suspend fun installFromRelease(
        repository: String,
        version: String,
        onProgress: (Float, String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetVersion = version.trim()
        val rootfsInstalled = File(layout.rootfsDir, "etc/os-release").isFile ||
            File(layout.rootfsDir, "usr/bin/env").isFile
        if (!rootfsInstalled) {
            return@withContext Result.failure(IllegalStateException(com.deepseek.dsh.core.model.Tr.t("rootfs is not installed; cannot update the plugin", "rootfs не установлен — обновить плагин нельзя")))
        }
        if (!profileDir.isDirectory) {
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Host profile is not initialized: start the Host once before updating the plugin", "Профиль Host не инициализирован: запустите Host хотя бы раз, затем обновите плагин"))
            )
        }
        if (BridgeReleaseDownloader.archiveUrl(repository, targetVersion) == null) {
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Plugin source or target version is unavailable: $repository#v$targetVersion", "Источник плагина или версия недоступны: $repository#v$targetVersion"))
            )
        }

        ensureProfilePresetLink(profileDir)

        // Прерванный прошлый раз мог оставить папки staging/backup; это не часть установки — сначала убрать,
        // чтобы сломанная полуустановка не сошла за текущее состояние при следующей замене.
        val nodeModules = File(profileDir, "node_modules").apply { mkdirs() }
        nodeModules.listFiles()
            ?.filter { it.name.startsWith(".dsh-plugin-") }
            ?.forEach { runCatching { SafeFiles.deleteTree(it) } }

        onProgress(0.02f, com.deepseek.dsh.core.model.Tr.t("Preparing to download plugin $targetVersion…", "Подготовка загрузки плагина $targetVersion…"))
        val archive = try {
            downloader.download(repository, targetVersion) { fraction, step ->
                onProgress(0.02f + fraction * 0.5f, step)
            }
        } catch (error: Exception) {
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Plugin archive download failed: ${error.message}", "Не удалось скачать архив плагина: ${error.message}"))
            )
        }

        onProgress(0.55f, com.deepseek.dsh.core.model.Tr.t("Verifying plugin archive…", "Проверка архива плагина…"))
        val target = File(nodeModules, BridgePluginManifest.DEFAULT_PACKAGE)
        val staging = File(nodeModules, ".dsh-plugin-incoming")
        SafeFiles.deleteTree(staging)
        val contents = try {
            BridgePluginArchive.extract(
                archive = archive.inputStream(),
                destination = staging,
                expectedPackage = BridgePluginManifest.DEFAULT_PACKAGE,
                expectedVersion = targetVersion,
            )
        } catch (error: Exception) {
            SafeFiles.deleteTree(staging)
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Plugin archive verification failed: ${error.message}", "Архив плагина не прошёл проверку: ${error.message}"))
            )
        }

        // Зависимости времени выполнения должны уже быть на пути разбора модулей Host, иначе плагин
        // упадёт на import. Здесь ошибка раньше, а установленный плагин остаётся нетронутым.
        val missingDependencies = contents.dependencies.keys.filterNot { dependency ->
            File(nodeModules, dependency).exists()
        }
        if (missingDependencies.isNotEmpty()) {
            SafeFiles.deleteTree(staging)
            return@withContext Result.failure(
                IllegalStateException(
                    com.deepseek.dsh.core.model.Tr.t(
                        "Plugin dependencies ${missingDependencies.joinToString()} are not shipped with the runtime; update the Rootfs image first, then the plugin",
                        "Зависимости плагина ${missingDependencies.joinToString()} отсутствуют в среде; сначала обновите образ Rootfs, затем плагин"
                    )
                )
            )
        }

        onProgress(0.8f, com.deepseek.dsh.core.model.Tr.t("Replacing the installed plugin…", "Замена установленного плагина…"))
        try {
            replaceDirectory(target = target, staging = staging)
        } catch (error: Exception) {
            SafeFiles.deleteTree(staging)
            return@withContext Result.failure(
                IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Failed to replace plugin files: ${error.message}", "Не удалось заменить файлы плагина: ${error.message}"))
            )
        }

        onProgress(0.92f, com.deepseek.dsh.core.model.Tr.t("Reading installed version…", "Чтение установленной версии…"))
        val installed = installedVersion()
        if (installed != targetVersion) {
            return@withContext Result.failure(
                IllegalStateException(
                    com.deepseek.dsh.core.model.Tr.t(
                        "Plugin files were replaced, but the installed version reads ${installed ?: "unknown"} (expected $targetVersion)",
                        "Файлы плагина заменены, но установлена версия ${installed ?: "неизвестно"} (ожидалась $targetVersion)"
                    )
                )
            )
        }
        onProgress(1f, com.deepseek.dsh.core.model.Tr.t("Plugin updated to $installed", "Плагин обновлён до $installed"))
        Result.success(installed)
    }

    /**
     * Записать ли bridge-плагин как bundle профиля.
     *
     * Мост подключается в `dsh-web-app/cordis.patch.yml` и не зависит от `package.json`/`bundles`
     * профиля; запись в профиль при перезапуске стёрла бы следы установок пользователя и официальных
     * (раньше из-за этого профиль при каждом запуске возвращался к шаблону). Поэтому здесь только чтение,
     * без записи: достаточно убедиться, что папка плагина есть, а профиль пишет сам Host своим путём установки.
     *
     * @param packageName имя пакета.
     * @return есть ли папка плагина.
     */
    fun ensureProfileEntry(
        packageName: String = BridgePluginManifest.DEFAULT_PACKAGE,
        @Suppress("UNUSED_PARAMETER") version: String? = null,
    ): Boolean {
        val inProfile = File(profileDir, "node_modules/$packageName")
        return File(inProfile, "package.json").isFile
    }

    /**
     * Заменить [target] целиком папкой [staging].
     *
     * Сначала старая папка переименовывается в сторону, потом новая встаёт на место: при ошибке старая возвращается,
     * поэтому «установленный плагин» — либо старый, либо новый, но не пропадает.
     * @param target папка установленного плагина.
     * @param staging проверенная папка нового плагина.
     */
    private fun replaceDirectory(target: File, staging: File) {
        val parent = target.parentFile ?: throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Plugin directory has no parent", "У папки плагина нет родительской папки"))
        val backup = File(parent, ".dsh-plugin-previous-${System.nanoTime()}")
        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(backup)) {
            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Cannot move the installed plugin directory aside", "Не удалось отодвинуть папку установленного плагина"))
        }
        if (!staging.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Cannot move the new plugin directory into place", "Не удалось поместить новую папку плагина на место"))
        }
        if (hadPrevious) SafeFiles.deleteTree(backup)
    }
}

/**
 * Убедиться, что `node_modules/@deepseek-ai` профиля указывает на @deepseek-ai из установки Host.
 *
 * Bundle профиля и bridge-плагин разбирают `@deepseek-ai/<name>` по имени, а источника pnpm на телефоне
 * нет; эта символическая ссылка — их единственная опора. Она нужна и запуску, и установке плагинов,
 * поэтому определена только здесь.
 * @param profileDir `root/.dsh/profiles/web`.
 */
internal fun ensureProfilePresetLink(profileDir: File) {
    val nodeModules = File(profileDir, "node_modules").apply { mkdirs() }
    val presetLink = File(nodeModules, "@deepseek-ai")
    // Резервные папки от старых версий и ручной отладки надо убрать: Host считает их отдельными пакетами
    // и показывает дубли в настройках и на странице плагинов.
    SafeFiles.deleteTree(File(nodeModules, "@deepseek-ai.merged"))
    // profiles/web -> profiles -> .dsh -> root -> корень rootfs
    val installed = File(
        profileDir,
        "../../../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
    ).canonicalFile
    if (!installed.isDirectory) return

    val presetPath = presetLink.toPath()
    // Висящая ссылка: файл ссылки есть, а цели нет (обновление rootfs заменило папку или
    // ссылка старой относительной глубины). Если не удалить, createSymbolicLink ниже упадёт с «уже существует»,
    // ошибка будет проглочена, и профиль навсегда перестанет находить @deepseek-ai/* — Host при запуске напишет
    // «N required plugins did not activate», и порт не появится никогда.
    if (java.nio.file.Files.isSymbolicLink(presetPath) && !presetLink.exists()) {
        runCatching { java.nio.file.Files.deleteIfExists(presetPath) }
    }

    // Новый rootfs приносит настоящую папку @deepseek-ai (только поднятые пакеты вроде cosmokit/schemastery).
    // Это не ссылка, и Host не разберёт по имени официальные bundle.
    // Копирование всей папки сломало бы раскладку зависимостей профиля, поэтому — ссылка: настоящая папка в резерв,
    // если ссылка не создалась — вернуть её и подстраховаться копированием.
    // Ссылка лежит в `node_modules/@deepseek-ai`, на уровень глубже profileDir.
    val installedLinkTarget = "../../../../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
    if (presetLink.exists() && !java.nio.file.Files.isSymbolicLink(presetPath)) {
        val backup = File(nodeModules, "@deepseek-ai.local")
        if (backup.exists()) SafeFiles.deleteTree(backup)
        if (!presetLink.renameTo(backup)) return
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(
                presetPath,
                java.nio.file.Paths.get(installedLinkTarget),
            )
        }.isSuccess
        if (linked) {
            SafeFiles.deleteTree(backup)
            return
        }
        // Ссылки недоступны (ограничение файловой системы): вернуть исходную папку и добавить недостающие пакеты.
        backup.renameTo(presetLink)
        mergeInstalledPackages(installed, presetLink)
        return
    }
    if (presetLink.exists()) {
        SafeFiles.deleteTree(File(nodeModules, "@deepseek-ai.local"))
        return
    }
    val linked = runCatching {
        java.nio.file.Files.createSymbolicLink(
            presetPath,
            java.nio.file.Paths.get(installedLinkTarget),
        )
    }.isSuccess
    if (linked) return
    // Ссылки недоступны: настоящая папка + копия установленных пакетов. Молча ничего не делать нельзя,
    // иначе в профиле нет официальных bundle, и Host не запустится (пользователь видит вечный STARTING).
    presetLink.mkdirs()
    mergeInstalledPackages(installed, presetLink)
}

/** Скопировать пакеты, которые есть в [installed], но нет в [target] (запасной путь без ссылок). */
private fun mergeInstalledPackages(installed: File, target: File) {
    installed.listFiles()?.forEach { source ->
        val destination = File(target, source.name)
        if (destination.exists()) return@forEach
        runCatching {
            if (source.isDirectory) {
                SafeFiles.copyTree(source, destination, overwrite = false)
            } else {
                source.copyTo(destination, overwrite = false)
            }
        }
    }
}
