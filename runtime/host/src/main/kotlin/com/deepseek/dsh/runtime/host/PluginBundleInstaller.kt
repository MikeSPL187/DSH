package com.deepseek.dsh.runtime.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Запасной установщик плагинов на стороне приложения.
 *
 * pnpm в Host под proot получает `ENOSYS` (mkdir/rename/unlink недоступны,
 * проверено; см. то же в `BridgePluginInstaller`), поэтому «Добавить плагин» не может полагаться только
 * на Host. Здесь то же делается файлами и сетью процесса приложения:
 *
 * 1. разобрать spec (репозиторий GitHub / имя npm / абсолютная папка в rootfs);
 * 2. получить содержимое пакета (архив исходников GitHub, tarball npm или локальная папка);
 * 3. рекурсивно поставить зависимости времени выполнения (только `dependencies`, peer даёт окружение);
 * 4. разложить в `node_modules` профиля;
 * 5. подключить `cordis.patch.yml` пакета к пользовательскому слою патчей профиля.
 *
 * Новый плагин загрузится после перезапуска Host — это делает вызывающий.
 */
class PluginBundleInstaller(
    private val context: Context,
    private val layout: RuntimeLayout,
    /**
     * Папка, к которой в Host привязан `/workspace` (userWorkspacePath в [ProotCommandBuilder]).
     * Локальная установка `/workspace/...` должна разбираться в ту же папку, иначе поставятся другие файлы.
     */
    private val workspaceDir: () -> File? = { null },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val profileDir: File
        get() = File(layout.rootfsDir, "root/.dsh/profiles/web")
    private val nodeModules: File
        get() = File(profileDir, "node_modules")

    /**
     * Установить плагин по spec.
     * @param rawSpec spec, введённый в окне.
     * @param onProgress ход и подписи шагов.
     * @return при успехе `имя@версия`.
     */
    suspend fun install(rawSpec: String, onProgress: (Float, String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            val spec = PluginInstallSpec.parse(rawSpec)
                ?: return@withContext Result.failure(
                    IllegalStateException(HostStrings.get(R.string.pi_bad_spec, "Unrecognized plugin address: %1\$s", rawSpec))
                )
            if (!profileDir.isDirectory) {
                return@withContext Result.failure(
                    IllegalStateException(HostStrings.get(R.string.pi_no_profile, "The Host profile is not initialized yet: start the Host once first"))
                )
            }
            ensurePresetLink()
            onProgress(0.02f, HostStrings.get(R.string.pi_resolving, "Resolving plugin address…"))
            val budget = InstallBudget()
            val staged = try {
                when (spec) {
                    is PluginInstallSpec.GitHub -> stageFromGitHub(spec, onProgress, budget)
                    is PluginInstallSpec.Registry -> stageFromRegistry(spec.name, spec.range, onProgress, budget)
                    is PluginInstallSpec.LocalPath -> stageFromLocalPath(spec.path, onProgress, budget)
                }
            } catch (error: Exception) {
                return@withContext Result.failure(
                    IllegalStateException(HostStrings.get(R.string.pi_fetch_failed, "Could not fetch the plugin package: %1\$s", error.message))
                )
            }
            staged.use { packageDir ->
                val manifest = readManifest(packageDir.dir)
                    ?: return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_no_manifest, "The plugin package has no readable package.json"))
                    )
                val name = manifest.name?.takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_no_name, "The plugin package.json has no name"))
                    )
                // Имя пакета входит в путь node_modules, по нему удаляются и заменяются папки: сначала проверка (аудит F-01).
                if (!PluginInstallSpec.isValidPackageName(name)) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_bad_name, "Invalid plugin package name: %1\$s", name))
                    )
                }
                if (spec is PluginInstallSpec.Registry && name != spec.name) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_name_mismatch, "The registry returned package %1\$s instead of the requested %2\$s", name, spec.name))
                    )
                }
                val version = manifest.version.orEmpty()
                val patchPath = manifest.patch
                if (patchPath.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_no_patch, "%1\$s does not declare dsh.bundle.patch and cannot be installed as a plugin", name))
                    )
                }
                // Патч проверяется до изменения текущей установки: при ошибке старая версия остаётся как есть (аудит F-05/N-09).
                val patchText = runCatching { safeChild(packageDir.dir, patchPath) }.getOrNull()
                    ?.takeIf { it.isFile && it.length() <= MAX_PATCH_BYTES }
                    ?.readText()
                if (patchText.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_patch_unreadable, "The bundle patch of %1\$s cannot be read", name))
                    )
                }
                onProgress(0.55f, HostStrings.get(R.string.pi_deps, "Installing runtime dependencies…"))
                val plan = DependencyPlan(File(packageDir.dir, "node_modules"), budget)
                val missing = runCatching {
                    installDependencies(packageDir.dir, manifest, onProgress, plan, depth = 0)
                }.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
                if (missing.isNotEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_deps_failed, "Dependency installation failed: %1\$s", missing.joinToString()))
                    )
                }
                onProgress(0.8f, HostStrings.get(R.string.pi_writing, "Writing to the profile…"))
                val committed = runCatching {
                    commitPackage(packageDir.dir, name) { mountWebAppPatch(name, patchText) }
                }
                committed.exceptionOrNull()?.let { error ->
                    return@withContext Result.failure(
                        IllegalStateException(HostStrings.get(R.string.pi_commit_failed, "Could not write the plugin; the previous version was restored: %1\$s", error.message))
                    )
                }
                onProgress(1f, HostStrings.get(R.string.pi_installed, "Installed %1\$s@%2\$s", name, version))
                Result.success(if (version.isBlank()) name else "$name@$version")
            }
        }

    /**
     * Атомарно заменить подготовленный пакет в `node_modules/<name>`.
     *
     * 1. подготовленная папка переносится во временную на том же диске внутри node_modules (атомарен только rename);
     * 2. старая версия переименовывается в резервную копию;
     * 3. новая встаёт на место через rename, затем [afterSwap] (подключить патч);
     * при ошибке любого шага новое удаляется, резервная копия возвращается — старый плагин работает.
     */
    private fun commitPackage(source: File, name: String, afterSwap: () -> Unit) {
        nodeModules.mkdirs()
        val target = safeChild(nodeModules, name)
        target.parentFile?.mkdirs()
        val stamp = System.nanoTime()
        val incoming = File(nodeModules, ".dsh-incoming-$stamp")
        val backup = File(nodeModules, ".dsh-backup-$stamp")
        var swapped = false
        try {
            if (!source.renameTo(incoming)) {
                copyTreeLimited(source, incoming, budget = null)
            }
            if (target.exists() && !target.renameTo(backup)) {
                error(com.deepseek.dsh.core.model.Tr.t("Cannot back up installed $name", "Не удалось сделать резервную копию $name"))
            }
            if (!incoming.renameTo(target)) {
                error(com.deepseek.dsh.core.model.Tr.t("Cannot move $name into place", "Не удалось переместить $name на место"))
            }
            swapped = true
            afterSwap()
        } catch (error: Throwable) {
            if (swapped) SafeFiles.deleteTree(target)
            if (backup.exists()) backup.renameTo(target)
            SafeFiles.deleteTree(incoming)
            throw error
        }
        SafeFiles.deleteTree(backup)
    }

    /**
     * Пакеты, подключённые установщиком приложения (по меткам `android-installed` в патче web-app).
     *
     * Так официальные плагины «из установки» отличаются от официальных пакетов, поставленных через «Добавить плагин».
     *
     * @returns имена пакетов; без меток — пусто.
     */
    fun managedPackages(): Set<String> {
        val patchFile = webAppPatchFile()
        if (!patchFile.isFile) return emptySet()
        val marker = "# android-installed: "
        return patchFile.readLines()
            .mapNotNull { line ->
                line.trim().takeIf { it.startsWith(marker) }?.removePrefix(marker)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .toSet()
    }

    /**
     * Убрать весь блок подключения `# android-installed: <пакет>` из патча web-app.
     *
     * Раньше удалялась только строка `name:`, а заглушка `- id:` оставалась; Loader всё равно создавал строку,
     * а пакета уже нет → при запуске веб-клиента запись не активируется, и весь интерфейс браузера застревает на
     * «Failed to load plugins». Именно так шло удаление строковых плагинов (без записи bundle).
     * Теперь блок убирается целиком: ни id, ни заглушки — Loader его не создаст.
     *
     * @param packageName имя пакета, записанное при установке.
     * @returns изменился ли патч (убран блок или его строка name).
     */
    suspend fun stripRowMountName(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val patchFile = webAppPatchFile()
        if (!patchFile.isFile) return@withContext false
        val marker = "# android-installed: $packageName"
        var changed = false
        runCatching {
            val text = patchFile.readText()
            if (!text.contains(marker)) return@runCatching
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trim() == marker }
            if (start < 0) return@runCatching
            val end = lines.withIndex()
                .firstOrNull { (index, line) -> index > start && line.startsWith("# android-") }
                ?.index ?: lines.size
            val next = (lines.subList(0, start) + lines.subList(end, lines.size)).toMutableList()
            while (next.isNotEmpty() && next.last().isBlank()) next.removeAt(next.lastIndex)
            writeTextAtomically(patchFile, next.joinToString("\n") + "\n")
            changed = true
        }
        changed
    }

    /**
     * Удалить плагин, поставленный приложением.
     *
     * У строковых плагинов нет записи bundle, `removeBundle` Host не подходит; здесь обратное установке:
     * удалить папку пакета в профиле и блок подключения в патче web-app. Строка Loader исчезнет
     * после перезапуска Host вызывающим.
     *
     * @param packageName имя пакета, записанное при установке.
     * @return итог удаления; если папки нет — тоже успех (повтор безопасен).
     */
    suspend fun uninstall(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(PluginInstallSpec.isValidPackageName(packageName)) { com.deepseek.dsh.core.model.Tr.t("Invalid plugin package name: $packageName", "Недопустимое имя пакета плагина: $packageName") }
            val target = safeChild(nodeModules, packageName)
            if (target.exists() && !SafeFiles.deleteTree(target)) {
                error(com.deepseek.dsh.core.model.Tr.t("Failed to delete plugin directory: $packageName", "Не удалось удалить папку плагина: $packageName"))
            }
            val patchFile = webAppPatchFile()
            if (patchFile.isFile) {
                val marker = "# android-installed: $packageName"
                val text = patchFile.readText()
                if (text.contains(marker)) {
                    writeTextAtomically(patchFile, removeMarkerBlock(text, marker))
                }
            }
        }
    }

    /** Снять заголовок блока для [removeMarkerBlock]: от строки метки до следующей метки (или конца файла). */
    private fun removeMarkerBlock(text: String, marker: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == marker }
        if (start < 0) return text
        val end = lines.withIndex()
            .firstOrNull { (index, line) -> index > start && line.startsWith("# android-") }
            ?.index ?: lines.size
        val kept = (lines.subList(0, start) + lines.subList(end, lines.size))
            .toMutableList()
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.lastIndex)
        return kept.joinToString("\n") + "\n"
    }

    // ── Источники пакетов ──────────────────────────────────────────────

    /** Архив исходников GitHub; тег или ветка — в `#ref`, по умолчанию HEAD. */
    private fun stageFromGitHub(
        spec: PluginInstallSpec.GitHub,
        onProgress: (Float, String) -> Unit,
        budget: InstallBudget,
    ): StagedPackage {
        val refSegment = spec.ref?.let { "refs/tags/$it" } ?: "HEAD"
        val url = "https://codeload.github.com/${spec.owner}/${spec.repo}/zip/$refSegment"
        onProgress(0.05f, HostStrings.get(R.string.pi_dl_github, "Downloading the GitHub archive…"))
        val bytes = download(url)
        onProgress(0.3f, HostStrings.get(R.string.pi_unpacking, "Extracting the plugin…"))
        val source = extractZipToStaging(bytes, budget)
        // Архив GitHub — это исходники: результатов сборки (lib/) там обычно нет. Если та же версия есть
        // в npm, папка исходников заменяется собранным tarball — иначе у поставленного
        // плагина нет входного файла, и Host сразу сообщит об ошибке import.
        val manifest = readManifest(source.dir) ?: return source
        val name = manifest.name?.takeIf { PluginInstallSpec.isValidPackageName(it) }
        val version = manifest.version
        val mainFile = runCatching { safeChild(source.dir, manifest.main) }.getOrNull()
        if (name != null && mainFile?.isFile != true) {
            val published = if (version != null) {
                findPublishedTarball(name, version)
            } else {
                null
            }
            if (published != null) {
                onProgress(0.35f, HostStrings.get(R.string.pi_dl_built, "Downloading the built %1\$s@%2\$s…", name, version))
                source.close()
                return extractTarGzToStaging(download(published), budget)
            }
            source.close()
            throw IllegalStateException(
                HostStrings.get(R.string.pi_no_build, "The source archive of %1\$s has no build output (%2\$s) and npm has no matching version; install it by npm package name or install a published version", name, manifest.main)
            )
        }
        return source
    }

    /** Адрес tarball версии пакета; такой версии нет — null. */
    private fun findPublishedTarball(name: String, version: String): String? {
        val metadata = fetchRegistryMetadata(name) ?: return null
        return metadata["versions"]?.jsonObjectOrNull()
            ?.get(version)?.jsonObjectOrNull()
            ?.get("dist")?.jsonObjectOrNull()
            ?.get("tarball")?.jsonPrimitive?.contentOrNull
    }

    /** Реестр npm: по metadata выбрать версию, затем скачать tarball. */
    private fun stageFromRegistry(
        name: String,
        range: String?,
        onProgress: (Float, String) -> Unit,
        budget: InstallBudget,
    ): StagedPackage {
        onProgress(0.05f, HostStrings.get(R.string.pi_query, "Looking up %1\$s versions…", name))
        val metadata = fetchRegistryMetadata(name)
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_not_in_registry, "%1\$s was not found in the registry", name))
        val version = selectVersion(metadata, range)
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_no_version, "%1\$s has no version matching %2\$s", name, range ?: "latest"))
        val tarball = metadata["versions"]?.jsonObjectOrNull()
            ?.get(version)?.jsonObjectOrNull()
            ?.get("dist")?.jsonObjectOrNull()
            ?.get("tarball")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_no_tarball, "%1\$s@%2\$s has no tarball URL", name, version))
        onProgress(0.1f, HostStrings.get(R.string.pi_dl, "Downloading %1\$s@%2\$s…", name, version))
        val bytes = download(tarball)
        onProgress(0.35f, HostStrings.get(R.string.pi_unpack_ver, "Extracting %1\$s@%2\$s…", name, version))
        return extractTarGzToStaging(bytes, budget)
    }

    /**
     * Версия по правилам npm: range — dist-tag, тогда тег; если `latest` подходит под range — он;
     * иначе самая высокая подходящая. Подходящей нет — null (fail closed, аудит A-01/N-10).
     */
    private fun selectVersion(metadata: JsonObject, range: String?): String? {
        val versions = metadata["versions"]?.jsonObjectOrNull()?.keys.orEmpty()
        val tags = metadata["dist-tags"]?.jsonObjectOrNull()
            ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val wanted = range?.trim().orEmpty()
        tags[wanted.ifEmpty { "latest" }]?.takeIf { it in versions }?.let { tagged ->
            if (wanted.isEmpty() || wanted in tags) return tagged
        }
        val latest = tags["latest"]?.takeIf { it in versions }
        if (latest != null && Semver.satisfies(latest, wanted.ifEmpty { "*" })) return latest
        return Semver.maxSatisfying(versions, wanted)
    }

    /** Локальная папка внутри rootfs (сопоставляется с путём, видимым приложению). */
    private fun stageFromLocalPath(
        path: String,
        onProgress: (Float, String) -> Unit,
        budget: InstallBudget,
    ): StagedPackage {
        val source = resolveLocalPath(path)
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_local_outside, "The local path is outside the folders the app can access: %1\$s", path))
        if (!File(source, "package.json").isFile) {
            throw IllegalStateException(HostStrings.get(R.string.pi_local_no_manifest, "The local folder has no package.json: %1\$s", path))
        }
        onProgress(0.3f, HostStrings.get(R.string.pi_local_copy, "Copying the local plugin folder…"))
        val staging = StagedPackage(newStagingDir())
        try {
            copyTreeLimited(source, staging.dir, budget)
        } catch (error: Throwable) {
            staging.close()
            throw error
        }
        return staging
    }

    /**
     * Сопоставить абсолютный путь внутри rootfs с папкой, доступной приложению.
     *
     * `/workspace` совпадает с привязкой Host (аудит A-04); после нормализации путь обязан оставаться внутри корня,
     * выход через `..` или символическую ссылку отклоняется.
     */
    private fun resolveLocalPath(path: String): File? {
        val mappings = listOf(
            "/root/.dsh" to layout.dshHomeDir,
            "/cache" to layout.cacheDir,
            "/workspace" to (workspaceDir() ?: File(layout.rootfsDir, "workspace")),
            "/tmp" to File(layout.rootfsDir, "tmp"),
        )
        for ((prefix, appDir) in mappings) {
            if (path == prefix || path.startsWith("$prefix/")) {
                return File(appDir, path.removePrefix(prefix).trimStart('/')).insideOrNull(appDir)
            }
        }
        if (path.startsWith(layout.rootfsDir.absolutePath + "/")) {
            return File(path).insideOrNull(layout.rootfsDir)
        }
        return null
    }

    private fun File.insideOrNull(root: File): File? {
        val canonical = canonicalFile
        return canonical.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
    }

    // ── Зависимости ────────────────────────────────────────────────────

    /**
     * Раскладка зависимостей одной установки: по возможности поднимаются в `node_modules` корня плагина ([rootModules]),
     * при конфликте версий — вкладываются в нуждающийся пакет. Node ищет вверх от вложенного пакета, поэтому поднятые
     * зависимости видны всему дереву — раньше дубли убирались по имени глобально, но ставились только под первым, и соседи их не находили.
     */
    private class DependencyPlan(val rootModules: File, val budget: InstallBudget) {
        /** Поднятые в [rootModules]: имя пакета → версия. */
        val hoisted = mutableMapOf<String, String>()
    }

    /**
     * Рекурсивно поставить `dependencies`.
     *
     * Peer-зависимости даёт окружение (ссылка `@deepseek-ai` в профиле). Ошибка на любом уровне поднимается
     * наверх (аудит A-02/N-11), превышение пределов глубины или количества — ошибка, а не тихий успех.
     * @return описание неудавшихся зависимостей; пусто — всё поставлено.
     */
    private fun installDependencies(
        packageDir: File,
        manifest: PackageManifest,
        onProgress: (Float, String) -> Unit,
        plan: DependencyPlan,
        depth: Int,
    ): List<String> {
        if (manifest.dependencies.isEmpty()) return emptyList()
        if (depth > MAX_DEPENDENCY_DEPTH) {
            return listOf("${manifest.name ?: packageDir.name} (" + com.deepseek.dsh.core.model.Tr.t("dependency depth exceeds $MAX_DEPENDENCY_DEPTH", "глубина зависимостей больше $MAX_DEPENDENCY_DEPTH") + ")")
        }
        val failures = mutableListOf<String>()
        val dependencies = manifest.dependencies
        var index = 0
        for ((name, range) in dependencies) {
            index += 1
            if (!PluginInstallSpec.isValidPackageName(name)) {
                failures.add("$name (" + com.deepseek.dsh.core.model.Tr.t("invalid package name", "недопустимое имя пакета") + ")")
                continue
            }
            if (File(packageDir, "node_modules/$name").isDirectory) continue
            val hoistedVersion = plan.hoisted[name]
            if (hoistedVersion != null && Semver.satisfies(hoistedVersion, range.ifBlank { "*" })) continue
            if (hoistedVersion == null && resolvableElsewhere(name)) continue
            if (depth == 0) {
                onProgress(
                    (0.55f + (index.toFloat() / (dependencies.size + 1)) * 0.2f).coerceAtMost(0.78f),
                    HostStrings.get(R.string.pi_dep, "Installing dependency %1\$s…", name),
                )
            }
            val parentModules = if (hoistedVersion == null) plan.rootModules else File(packageDir, "node_modules")
            val result = runCatching {
                installOneDependency(parentModules, name, range, onProgress, plan, depth)
            }
            result.exceptionOrNull()?.let { failures.add("$name(${it.message})") }
        }
        return failures
    }

    /** Поставить зависимость из реестра (с её транзитивными); любая транзитивная ошибка выбрасывается. */
    private fun installOneDependency(
        parentModules: File,
        name: String,
        range: String,
        onProgress: (Float, String) -> Unit,
        plan: DependencyPlan,
        depth: Int,
    ) {
        if (++plan.budget.packages > MAX_PACKAGES) {
            throw IllegalStateException(HostStrings.get(R.string.pi_too_many, "More than %1\$d dependencies", MAX_PACKAGES))
        }
        val metadata = fetchRegistryMetadata(name)
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_not_in_registry, "%1\$s was not found in the registry", name))
        val version = selectVersion(metadata, range)
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_no_version_range, "No version matches %1\$s", range))
        val tarball = metadata["versions"]?.jsonObjectOrNull()
            ?.get(version)?.jsonObjectOrNull()
            ?.get("dist")?.jsonObjectOrNull()
            ?.get("tarball")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException(HostStrings.get(R.string.pi_no_tarball_short, "No tarball URL"))
        val bytes = download(tarball)
        val staging = extractTarGzToStaging(bytes, plan.budget)
        staging.use { extracted ->
            parentModules.mkdirs()
            val destination = safeChild(parentModules, name)
            destination.parentFile?.mkdirs()
            SafeFiles.deleteTree(destination)
            if (!extracted.dir.renameTo(destination)) {
                copyTreeLimited(extracted.dir, destination, budget = null)
                SafeFiles.deleteTree(extracted.dir)
            }
            // Сначала записать, потом рекурсия: цикл (A→B→A) сразу найдёт поднятую версию.
            if (parentModules == plan.rootModules) plan.hoisted[name] = version
            val nested = readManifest(destination) ?: return@use
            val nestedFailures = installDependencies(destination, nested, onProgress, plan, depth + 1)
            if (nestedFailures.isNotEmpty()) {
                throw IllegalStateException(nestedFailures.joinToString())
            }
        }
    }

    /**
     * Разбирается ли зависимость Node из папки плагина.
     *
     * Считается только `node_modules` профиля: `@deepseek-ai/<name>` разбирается через ссылку
     * в профиле, остальные пакеты должны реально быть на пути поиска. Вложенные зависимости папки установки DSH
     * в цепочку поиска плагина не входят и предоставленными не считаются.
     */
    private fun resolvableElsewhere(name: String): Boolean =
        File(nodeModules, name).exists()

    // ── Подключение к Host ─────────────────────────────────────────────

    /**
     * Дописать патч bundle плагина в слой патчей `dsh-web-app`.
     *
     * Почему не пользовательский слой профиля: на этом устройстве Host при каждом запуске пересоздаёт
     * `package.json`/`cordis.patch.yml` профиля (проверено, в журнале запуска виден вызов
     * `initProfile`), и подключение в профиле пропадает при следующем запуске. Патч самого web-app —
     * «состояние из установки», пересоздание его не трогает; этот же слой использует
     * bridge-плагин.
     *
     * Повтор безопасен: метка уже есть — ничего не дописывается.
     */
    private fun mountWebAppPatch(packageName: String, patchText: String) {
        val patchFile = webAppPatchFile()
        if (!patchFile.isFile) error(HostStrings.get(R.string.pi_no_patch_layer, "The dsh-web-app patch layer is missing; cannot mount %1\$s", packageName))
        val marker = "# android-installed: $packageName"
        val body = patchText.trimEnd()
        // При переустановке или обновлении блок заменяется новым патчем (раньше устаревший оставался).
        val current = patchFile.readText().let { text ->
            if (text.contains(marker)) removeMarkerBlock(text, marker) else text
        }
        val next = if (current.trim() == "[]" || current.isBlank()) {
            "$marker\n$body\n"
        } else {
            current.trimEnd() + "\n\n$marker\n" + body + "\n"
        }
        writeTextAtomically(patchFile, next)
    }

    /** Сначала временный файл в той же папке и fsync, затем rename поверх: сбой питания или падение не оставят половину патча. */
    private fun writeTextAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            java.io.FileOutputStream(tmp).use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) error(com.deepseek.dsh.core.model.Tr.t("Cannot write ${file.name}", "Не удалось записать ${file.name}"))
        } finally {
            tmp.delete()
        }
    }

    /** Файл патча `dsh-web-app`: слой подключения сторонних плагинов. */
    private fun webAppPatchFile(): File = File(
        layout.rootfsDir,
        "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-web-app/cordis.patch.yml",
    )

    // ── HTTP / архивы / манифест ───────────────────────────────────────

    /** Скачать URL; не больше 64 МБ. */
    private fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "dsh-android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: $url")
            val body = response.body ?: throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Empty response: $url", "Пустой ответ: $url"))
            val buffer = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException(HostStrings.get(R.string.pi_dl_too_big, "Download is larger than 64 MB: %1\$s", url))
                    }
                }
            }
            return buffer.toByteArray()
        }
    }

    /** Metadata реестра (сокращённый формат для установки). */
    private fun fetchRegistryMetadata(name: String): JsonObject? {
        val encoded = name.replace("/", "%2F")
        val request = Request.Builder()
            .url("https://registry.npmjs.org/$encoded")
            .header("Accept", "application/vnd.npm.install-v1+json")
            .header("User-Agent", "dsh-android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
    }

    /**
     * Общий бюджет распаковки одной установки (со всеми зависимостями): пределы суммы байтов, числа записей и пакетов —
     * защита от zip/tar-бомб, заполняющих память (аудит A-03/N-12). Считаются реально записанные байты, заголовкам архива не верим.
     */
    private class InstallBudget {
        var bytes = 0L
        var entries = 0
        var packages = 0

        fun addEntry() {
            if (++entries > MAX_ENTRIES) throw IllegalStateException(HostStrings.get(R.string.pi_too_many_entries, "The archive has more than %1\$d entries", MAX_ENTRIES))
        }

        fun addBytes(count: Long) {
            bytes += count
            if (bytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw IllegalStateException(HostStrings.get(R.string.pi_too_large, "Extracted size exceeds %1\$d MB", MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)))
            }
        }
    }

    /** Скопировать запись с пределами; превышение предела файла или общего бюджета — сразу стоп. */
    private fun copyLimited(input: java.io.InputStream, target: File, budget: InstallBudget?) {
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                written += read
                if (written > MAX_ENTRY_BYTES) {
                    throw IllegalStateException(HostStrings.get(R.string.pi_entry_too_large, "A file in the archive exceeds %1\$d MB", MAX_ENTRY_BYTES / (1024 * 1024)))
                }
                budget?.addBytes(read.toLong())
                output.write(buffer, 0, read)
            }
        }
    }

    /** Скопировать дерево папок: по символическим ссылкам не ходим, всё в бюджет. */
    private fun copyTreeLimited(source: File, target: File, budget: InstallBudget?) {
        val sourcePath = source.toPath()
        java.nio.file.Files.walk(sourcePath).use { stream ->
            stream.forEach { path ->
                if (java.nio.file.Files.isSymbolicLink(path)) return@forEach
                val relative = sourcePath.relativize(path).toString()
                val destination = if (relative.isEmpty()) target else File(target, relative)
                if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    destination.mkdirs()
                } else if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    budget?.addEntry()
                    destination.parentFile?.mkdirs()
                    java.nio.file.Files.newInputStream(path).use { input -> copyLimited(input, destination, budget) }
                }
            }
        }
    }

    /** Распаковать zipball GitHub: убрать единственную верхнюю папку, выходящие наружу пути отклонить. */
    private fun extractZipToStaging(bytes: ByteArray, budget: InstallBudget): StagedPackage {
        val staging = StagedPackage(newStagingDir())
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var rootPrefix: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    budget.addEntry()
                    val name = entry.name
                    rootPrefix = rootPrefix ?: name.substringBefore('/', "") + "/"
                    val relative = name.removePrefix(rootPrefix).trimEnd('/')
                    if (relative.isNotBlank()) {
                        val target = safeChild(staging.dir, relative)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            copyLimited(zip, target, budget)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            staging.close()
            throw error
        }
        return staging
    }

    /**
     * Распаковать tarball npm: убрать префикс `package/`, выходящие наружу пути отклонить.
     * Только обычные файлы; символические и жёсткие ссылки и файлы устройств пропускаются (пакетам npm они не нужны).
     */
    private fun extractTarGzToStaging(bytes: ByteArray, budget: InstallBudget): StagedPackage {
        val staging = StagedPackage(newStagingDir())
        try {
            GzipCompressorInputStream(bytes.inputStream()).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        budget.addEntry()
                        if (!entry.isFile) continue
                        val name = entry.name.removePrefix("package/")
                        if (name.isBlank()) continue
                        val target = safeChild(staging.dir, name)
                        target.parentFile?.mkdirs()
                        copyLimited(tar, target, budget)
                    }
                }
            }
        } catch (error: Throwable) {
            staging.close()
            throw error
        }
        return staging
    }

    /** Склеить подпуть и не дать выйти из папки пакета (проверка `..` по сегментам, затем нормализованного пути). */
    private fun safeChild(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." } || normalized.contains('\u0000')) {
            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Archive path escapes the root: $relative", "Путь в архиве выходит за пределы папки: $relative"))
        }
        val file = File(root, normalized)
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IllegalStateException(com.deepseek.dsh.core.model.Tr.t("Archive path escapes the target directory: $relative", "Путь в архиве выходит за целевую папку: $relative"))
        }
        return file
    }

    /** Прочитать манифест плагина. */
    private fun readManifest(dir: File): PackageManifest? {
        val file = File(dir, "package.json")
        if (!file.isFile) return null
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val dsh = root["dsh"]?.jsonObjectOrNull()
            val bundle = dsh?.get("bundle")?.jsonObjectOrNull()
            val patch = bundle?.get("patch")?.jsonPrimitive?.contentOrNull
            val dependencies = root["dependencies"]?.jsonObjectOrNull()
                ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull ?: "" }
                .orEmpty()
            PackageManifest(
                name = root["name"]?.jsonPrimitive?.contentOrNull,
                version = root["version"]?.jsonPrimitive?.contentOrNull,
                main = root["main"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "lib/index.js",
                patch = patch,
                dependencies = dependencies,
            )
        }.getOrNull()
    }

    /** Новая временная папка для этой установки. */
    private fun newStagingDir(): File {
        val root = File(layout.cacheDir, "plugin-install").apply { mkdirs() }
        return File(root, "package-${System.nanoTime()}").apply { mkdirs() }
    }

    /** Убедиться, что есть ссылка @deepseek-ai в профиле (опора разбора peer-зависимостей). */
    private fun ensurePresetLink() {
        ensureProfilePresetLink(profileDir)
    }

    private fun kotlinx.serialization.json.JsonElement?.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    /** Папки пакетов одной установки; удаляются в конце. */
    private class StagedPackage(val dir: File) : AutoCloseable {
        override fun close() {
            SafeFiles.deleteTree(dir)
        }
    }

    /** Поля манифеста, нужные установке. */
    private data class PackageManifest(
        val name: String?,
        val version: String?,
        val main: String,
        val patch: String?,
        val dependencies: Map<String, String>,
    )

    private companion object {
        /** Предел одной загрузки; плагины и зависимости — в пределах нескольких МБ. */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        /** Предел суммы распакованных байтов одной установки (со всеми зависимостями). */
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        /** Предел одного файла в архиве. */
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        /** Предел общего числа записей архивов одной установки. */
        const val MAX_ENTRIES = 60_000
        /** Предел числа пакетов-зависимостей одной установки. */
        const val MAX_PACKAGES = 600
        /** Наибольшая глубина дерева зависимостей; глубже — ошибка, а не тихий успех. */
        const val MAX_DEPENDENCY_DEPTH = 24
        /** Предел файла патча bundle. */
        const val MAX_PATCH_BYTES = 1L * 1024 * 1024
    }
}
