package com.dsh.plugin.nativehost

import android.content.Context
import com.deepseek.dsh.plugin.nativesdk.contract.DshUiPlugin

/**
 * Загрузчик компаньона: классы плагина из APK компаньона загружаются в процесс приложения.
 *
 * Компаньон — отдельный процесс; `SlotRegistry.register(...)` в его блоке `init` действует только
 * в его процессе. Чтобы рисовать в местах вставки приложения, классы нужно загрузить в **процесс приложения**:
 * `createPackageContext` даёт ClassLoader компаньона, рефлексией создаётся класс плагина
 * его Service, и регистрация выполняется в процессе приложения.
 *
 * Условие безопасности: загружаются только READY-плагины, прошедшие проверку ABI и одобренные пользователем
 * (pin [NativePluginTrustStore] совпадает); перед загрузкой подпись читается ещё раз — APK могли подменить после сканирования.
 */
class NativePluginLoader(private val context: Context) {

    /**
     * Загрузить READY-компаньон в процесс приложения.
     *
     * Находит класс входного Service, загружает и создаёт его [DshUiPlugin] через ClassLoader компаньона —
     * блок `init` регистрирует вклады в места вставки, отрисовщики инструментов и страницы в процессе приложения.
     *
     * @return загруженный плагин; null при ошибке (плагин работает через веб-интерфейс).
     */
    /**
     * Плагин загружается в процесс один раз.
     *
     * Каждый возврат на передний план и пересканирование снова вызывают `loader.load()`; DexClassLoader создаёт
     * новый загрузчик классов, а вклады старого экземпляра в SlotRegistry остаются. У двух загрузчиков свои
     * одноимённые классы вроде `DockData`, и `as DockData` в Render плагина выбрасывает
     * ClassCastException (так падал док плагина подписок). С кэшем экземпляра пересканирование его переиспользует
     * и не регистрирует вклады повторно; обновлённый плагин заработает после перезапуска процесса.
     */
    private companion object {
        /** Кэш на уровне процесса: MainAppHost создаёт NativePluginLoader на каждое сканирование, в экземпляре кэш не удержать. */
        val loadedPlugins = java.util.concurrent.ConcurrentHashMap<String, DshUiPlugin>()
    }

    fun load(plugin: InstalledNativePlugin): DshUiPlugin? {
        if (plugin.status != InstalledNativePlugin.Status.READY) return null
        val cacheKey = plugin.packageName + "/" + plugin.signatureFingerprint
        loadedPlugins[cacheKey]?.let { return it }
        // TOCTOU: между сканированием и загрузкой APK мог обновиться с другим подписантом — верим текущему состоянию системы.
        val currentFingerprint = NativePluginDiscovery.signingFingerprint(
            context.packageManager,
            plugin.packageName,
        )
        if (currentFingerprint == null ||
            !java.security.MessageDigest.isEqual(
                currentFingerprint.toByteArray(),
                plugin.signatureFingerprint.lowercase().toByteArray(),
            )
        ) {
            android.util.Log.w("NativePluginLoader", "signature changed before load: ${plugin.packageName}")
            return null
        }
        return try {
            // The companion packages the SDK for compilation, but at runtime the host
            // must own the single copy of DshUiPlugin, PageRegistry and Compose. A
            // package context has a separate SDK class identity, so `instanceof` would
            // reject an otherwise valid companion. Parent-first loading shares the
            // gated host ABI while the child loader supplies only the companion's code.
            val pluginContext = context.createPackageContext(
                plugin.packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            // `createPackageContext` already builds a ClassLoader over the installed
            // APK. Prefer it: a separate DexClassLoader over sourceDir can fail with
            // "Failed to find entry 'classes.dex'" for multi-dex / v4-signed APKs,
            // which silently drops every slot contribution of the companion.
            val viaPackage = runCatching {
                loadViaService(pluginContext.classLoader, plugin)
            }.getOrNull()
            if (viaPackage != null) {
                loadedPlugins[cacheKey] = viaPackage
                return viaPackage
            }
            val appInfo = pluginContext.applicationInfo
            val dexPath = (listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty())
                .joinToString(java.io.File.pathSeparator)
            // PathClassLoader, а не DexClassLoader(…, optimizedDirectory = null, …): на Android 8.0
            // DexClassLoader делает `new File(optimizedDirectory)` и падает NPE — companion-плагины
            // на Android 8 не загружались совсем (найдено на эмуляторе API 26).
            val classLoader = dalvik.system.PathClassLoader(
                dexPath,
                appInfo.nativeLibraryDir,
                context.classLoader,
            )
            // Вход в плагин ищется по Service компаньона (см. pluginFromService).
            val viaDex = loadViaService(classLoader, plugin)
            if (viaDex != null) loadedPlugins[cacheKey] = viaDex
            viaDex
        } catch (error: Throwable) {
            android.util.Log.w("NativePluginLoader", "failed to load ${plugin.packageName}", error)
            null
        }
    }

    /**
     * Экземпляр плагина через класс Service компаньона.
     *
     * Договорённость (в документации SDK): у класса Service есть `@JvmStatic fun plugin(): DshUiPlugin`,
     * или имя Service вида `<X>PluginService` соответствует классу плагина `<X>Plugin`. Пробуются оба.
     */
    private fun loadViaService(classLoader: ClassLoader, plugin: InstalledNativePlugin): DshUiPlugin? {
        val serviceClassName = plugin.serviceComponent.className
        // 1. Сначала статическая фабрика plugin() у класса Service.
        runCatching {
            val serviceClass = classLoader.loadClass(serviceClassName)
            val factory = serviceClass.getMethod("plugin").apply { isAccessible = true }
            val instance = factory.invoke(null)
            if (instance is DshUiPlugin) return instance
        }
        // 2. Иначе по имени класса: …PluginService → …Plugin.
        val pluginClassName = serviceClassName.removeSuffix("Service")
        if (pluginClassName != serviceClassName) {
            runCatching {
                val pluginClass = classLoader.loadClass(pluginClassName)
                val ctor = pluginClass.getDeclaredConstructor().apply { isAccessible = true }
                val instance = ctor.newInstance()
                if (instance is DshUiPlugin) return instance
            }
        }
        return null
    }
}
