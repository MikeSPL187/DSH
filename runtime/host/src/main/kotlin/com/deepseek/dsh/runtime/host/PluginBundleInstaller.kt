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
 * App 侧的插件安装兜底。
 *
 * Host 内的 pnpm 在 proot 下会拿到 `ENOSYS`（mkdir/rename/unlink 都不可用，
 * 实测；见 `BridgePluginInstaller` 的同类说明），所以「添加插件」不能只靠
 * Host。这里用 App 进程自己的文件与网络能力完成同一件事：
 *
 * 1. 解析 spec（GitHub 仓库 / npm 包名 / rootfs 内绝对目录）；
 * 2. 取包内容（GitHub 源码归档、npm tarball 或本地目录）；
 * 3. 递归安装运行时依赖（只做 `dependencies`，peer 由运行时提供）；
 * 4. 铺进 profile 的 `node_modules`；
 * 5. 把包自带的 `cordis.patch.yml` 挂进 profile 的用户 patch 层。
 *
 * 装完需要重启 Host 才会加载新插件；调用方负责这一步。
 */
class PluginBundleInstaller(
    private val context: Context,
    private val layout: RuntimeLayout,
    /**
     * Host 里 `/workspace` 实际绑定的目录（[ProotCommandBuilder] 的 userWorkspacePath）。
     * 本地安装 `/workspace/...` 必须解析到同一个目录，否则装的是另一份文件。
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
     * 安装一个插件 spec。
     * @param rawSpec 对话框输入的原始 spec。
     * @param onProgress 进度与步骤文案。
     * @return 成功时给出 `包名@版本`。
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
                // 包名会被拼进 node_modules 路径并在其上删除/替换目录：先校验（审计 F-01）。
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
                // 在动现有安装之前就验证 patch，失败时旧版本保持原样（审计 F-05/N-09）。
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
     * 原子地把已暂存的包换到 `node_modules/<name>`。
     *
     * 1. 暂存目录先移到 node_modules 下的同盘临时目录（rename 才是原子的）；
     * 2. 旧版本 rename 成备份；
     * 3. 新版本 rename 到位，再执行 [afterSwap]（挂 patch）；
     * 任一步失败都删除新内容并把备份 rename 回去，旧插件保持可用。
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
                error("无法备份已安装的 $name")
            }
            if (!incoming.renameTo(target)) {
                error("无法把 $name 移动到位")
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
     * 列出 App 安装器挂载过的包名（读 web-app patch 里的 `android-installed` 标记）。
     *
     * 用来区分「随安装自带」的官方插件和用户后来通过「添加插件」装进来的官方包。
     *
     * @returns 包名集合；没有标记时为空。
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
     * 持久化一条 Loader 行的启停状态。
     *
     * Host 的 `setPluginEnabled` 把 `disabled` 覆盖写进 profile patch；而这台设备
     * 每次启动都会重建 profile patch，覆盖会丢。这里再写一份到 web-app patch，
     * 重启后仍然生效；开启时删除这份覆盖。
     *
     * @param patchId Loader 行的 patch id（entryId 去掉 `include:` 前缀）。
     * @param enabled 目标状态。
     */
    suspend fun setRowEnabled(patchId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val patchFile = webAppPatchFile()
            if (!patchFile.isFile) return@runCatching
            val marker = "# android-row-override: $patchId"
            var text = patchFile.readText()
            if (text.contains(marker)) {
                text = removeMarkerBlock(text, marker)
            }
            if (!enabled) {
                text = text.trimEnd() + "\n\n$marker\n- id: $patchId\n  disabled: true\n"
            }
            writeTextAtomically(patchFile, text)
        }
    }

    /**
     * 摘掉 web-app patch 里 `# android-installed: <包名>` 的整块挂载。
     *
     * 旧实现只删 `name:` 行、留一个 `- id:` 占位；Loader 仍会创建这一行，
     * 而包已经不存在 → Web 客户端启动时该 entry 激活失败，整个浏览器界面停在
     * 「Failed to load plugins」。行插件（没有 bundle 记录）卸载后正是这条路。
     * 现在整块摘掉：没有 id、没有占位，Loader 也就不会再创建它。
     *
     * @param packageName 安装时写入的包名。
     * @returns 是否改动了 patch（摘掉了挂载块或其中的 name 行）。
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
     * 卸载一个由 App 侧安装的插件。
     *
     * 行插件没有 bundle 记录，Host 的 `removeBundle` 不适用；这里做安装的逆操作：
     * 删掉 profile 里的包目录，并摘掉 web-app patch 里的挂载块。调用方随后重启
     * Host 才会真正少掉这一条 Loader 行。
     *
     * @param packageName 安装时写入的包名。
     * @return 删除结果；目录不存在也视为成功（幂等）。
     */
    suspend fun uninstall(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(PluginInstallSpec.isValidPackageName(packageName)) { "插件包名不合法：$packageName" }
            val target = safeChild(nodeModules, packageName)
            if (target.exists() && !SafeFiles.deleteTree(target)) {
                error("删除插件目录失败：$packageName")
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

    /** 摘掉 [removeMarkerBlock] 使用的挂载块头：从标记行到下一个标记行（或文件尾）。 */
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

    // ── 包来源 ────────────────────────────────────────────────────────────

    /** GitHub 源码归档；tag/分支由 `#ref` 指定，缺省用 HEAD。 */
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
        // GitHub 归档是源码仓库：构建产物（lib/）通常不在里面。能在 npm 上
        // 找到同一版本时，用已构建的 tarball 替换源码目录 —— 否则装上去的
        // 插件没有入口文件，Host 会直接报 import 失败。
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

    /** 查一个包某个版本的 tarball 地址；没有该版本时返回 null。 */
    private fun findPublishedTarball(name: String, version: String): String? {
        val metadata = fetchRegistryMetadata(name) ?: return null
        return metadata["versions"]?.jsonObjectOrNull()
            ?.get(version)?.jsonObjectOrNull()
            ?.get("dist")?.jsonObjectOrNull()
            ?.get("tarball")?.jsonPrimitive?.contentOrNull
    }

    /** npm registry：拿 metadata 定版本，再下 tarball。 */
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
     * 按 npm 规则选版本：range 是 dist-tag 时取 tag；`latest` 满足 range 时优先它；
     * 否则取满足 range 的最高版本。没有满足项时 null（fail closed，审计 A-01/N-10）。
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

    /** rootfs 内的本地目录（映射 App 可见路径）。 */
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
     * 把 rootfs 内的绝对路径映射到 App 可直接访问的目录。
     *
     * `/workspace` 与 Host 的绑定保持一致（审计 A-04）；结果必须规范化后仍在映射根目录内，
     * `..` 或符号链接逃逸一律拒绝。
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

    // ── 依赖 ──────────────────────────────────────────────────────────────

    /**
     * 一次安装的依赖布局：尽量把依赖提升到插件根的 `node_modules`（[rootModules]），
     * 版本冲突时才嵌套到需要它的包下面。Node 从嵌套包向上逐级解析，所以提升后的
     * 依赖对整棵树可见——旧实现按名字全局去重却只装在第一个依赖方下面，兄弟包解析不到。
     */
    private class DependencyPlan(val rootModules: File, val budget: InstallBudget) {
        /** 已提升到 [rootModules] 的 包名 → 版本。 */
        val hoisted = mutableMapOf<String, String>()
    }

    /**
     * 递归安装 `dependencies`。
     *
     * peer 依赖交给运行时（profile 的 `@deepseek-ai` 链接）。任何一层的失败都会向上
     * 汇总（审计 A-02/N-11），超出深度/数量上限视为失败而不是静默成功。
     * @return 失败的依赖描述；空表示全部装好。
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
            return listOf("${manifest.name ?: packageDir.name}(依赖层级超过 $MAX_DEPENDENCY_DEPTH)")
        }
        val failures = mutableListOf<String>()
        val dependencies = manifest.dependencies
        var index = 0
        for ((name, range) in dependencies) {
            index += 1
            if (!PluginInstallSpec.isValidPackageName(name)) {
                failures.add("$name(包名不合法)")
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

    /** 从 registry 装一个依赖（含它的传递依赖）；任何传递失败都会抛出。 */
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
            // 先登记再递归：循环依赖（A→B→A）会直接命中已提升的版本。
            if (parentModules == plan.rootModules) plan.hoisted[name] = version
            val nested = readManifest(destination) ?: return@use
            val nestedFailures = installDependencies(destination, nested, onProgress, plan, depth + 1)
            if (nestedFailures.isNotEmpty()) {
                throw IllegalStateException(nestedFailures.joinToString())
            }
        }
    }

    /**
     * 依赖是否已经能被 Node 从插件目录解析到。
     *
     * 只有 profile 的 `node_modules` 算数：`@deepseek-ai/<name>` 通过 profile 里的
     * 链接解析，其余包必须真的在解析路径上。DSH 安装目录自己嵌套的依赖不在
     * 插件的解析链里，不能算作已提供。
     */
    private fun resolvableElsewhere(name: String): Boolean =
        File(nodeModules, name).exists()

    // ── Host 挂载 ─────────────────────────────────────────────────────────

    /**
     * 把插件的 bundle patch 追加进 `dsh-web-app` 的 patch 层。
     *
     * 为什么不是 profile 的用户 patch 层：这台设备上 Host 每次启动都会重建
     * profile 的 `package.json`/`cordis.patch.yml`（实测，启动日志里能抓到
     * `initProfile` 被调用），写进 profile 的挂载下一轮启动就没了。web-app
     * 自己的 patch 属于「随安装发布的状态」，不受那次重建影响 —— bridge 插件
     * 用的也是这一层。
     *
     * 幂等：标记已存在就不再追加。
     */
    private fun mountWebAppPatch(packageName: String, patchText: String) {
        val patchFile = webAppPatchFile()
        if (!patchFile.isFile) error(HostStrings.get(R.string.pi_no_patch_layer, "The dsh-web-app patch layer is missing; cannot mount %1\$s", packageName))
        val marker = "# android-installed: $packageName"
        val body = patchText.trimEnd()
        // 重装/升级时用新 patch 替换旧挂载块（旧实现保留过期 patch）。
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

    /** 先写同目录临时文件、fsync，再 rename 覆盖：断电/崩溃时不会留下半截 patch。 */
    private fun writeTextAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            java.io.FileOutputStream(tmp).use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) error("无法写入 ${file.name}")
        } finally {
            tmp.delete()
        }
    }

    /** `dsh-web-app` 的 patch 文件：第三方插件的挂载层。 */
    private fun webAppPatchFile(): File = File(
        layout.rootfsDir,
        "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-web-app/cordis.patch.yml",
    )

    // ── HTTP / 归档 / 清单 ────────────────────────────────────────────────

    /** 下载一个 URL；上限 64MB。 */
    private fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "dsh-android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}：$url")
            val body = response.body ?: throw IllegalStateException("空响应：$url")
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

    /** registry metadata（安装用的精简格式）。 */
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
     * 一次安装（含全部依赖）共享的解压预算：限制总解压字节、条目数与包数量，
     * 防止 zip/tar bomb 填满存储（审计 A-03/N-12）。按实际写出的字节计数，不信任归档头。
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

    /** 带上限地复制一个条目；超过单文件上限或总预算立即中止。 */
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

    /** 复制目录树：不跟随符号链接，计入预算。 */
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

    /** 解压 GitHub zipball：去掉单一顶层目录，拒绝越界路径。 */
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
     * 解压 npm tarball：去掉 `package/` 前缀，拒绝越界路径。
     * 只解出普通文件；符号链接/硬链接/设备文件一律跳过（npm 包不需要它们）。
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

    /** 拼接子路径并拒绝逃出包目录（按路径段检查 `..`，再做规范路径校验）。 */
    private fun safeChild(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." } || normalized.contains('\u0000')) {
            throw IllegalStateException("归档含越界路径：$relative")
        }
        val file = File(root, normalized)
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IllegalStateException("归档路径逃出目标目录：$relative")
        }
        return file
    }

    /** 读插件清单。 */
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

    /** 新建本次安装的临时目录。 */
    private fun newStagingDir(): File {
        val root = File(layout.cacheDir, "plugin-install").apply { mkdirs() }
        return File(root, "package-${System.nanoTime()}").apply { mkdirs() }
    }

    /** 保证 profile 的 @deepseek-ai 链接存在（peer 依赖的解析锚点）。 */
    private fun ensurePresetLink() {
        ensureProfilePresetLink(profileDir)
    }

    private fun kotlinx.serialization.json.JsonElement?.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    /** 一次安装里的包目录，结束时清理。 */
    private class StagedPackage(val dir: File) : AutoCloseable {
        override fun close() {
            SafeFiles.deleteTree(dir)
        }
    }

    /** 安装需要的清单字段。 */
    private data class PackageManifest(
        val name: String?,
        val version: String?,
        val main: String,
        val patch: String?,
        val dependencies: Map<String, String>,
    )

    private companion object {
        /** 单个下载上限；插件与依赖都在几 MB 内。 */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        /** 一次安装（含全部依赖）解压出的总字节上限。 */
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        /** 归档内单个文件上限。 */
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        /** 一次安装的归档条目总数上限。 */
        const val MAX_ENTRIES = 60_000
        /** 一次安装的依赖包数量上限。 */
        const val MAX_PACKAGES = 600
        /** 依赖树最大深度；超过视为失败而非静默成功。 */
        const val MAX_DEPENDENCY_DEPTH = 24
        /** bundle patch 文件上限。 */
        const val MAX_PATCH_BYTES = 1L * 1024 * 1024
    }
}
