package com.dsh.runtime.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Манифест rootfs — только встроенный в APK (G-41).
 *
 * Раньше «последний выпуск» репозитория образа в сети был важнее встроенного, а SHA-256 приходил в том же
 * манифесте: владелец того репозитория (или взломщик его аккаунта) выпускал новый образ — и он ставился на все
 * телефоны и выполнялся с правами приложения. Теперь адреса и SHA-256 образа закреплены в APK: новый образ
 * приходит только вместе с новым APK, подписанным нашим ключом. Кэш прошлых сетевых манифестов удаляется.
 */
class RootfsManifestSource(
    private val context: Context,
    private val layout: RuntimeLayout,
) {
    /** Встроенный манифест; null — APK собран без него (ошибка сборки). */
    suspend fun resolve(): RootfsManifest? = withContext(Dispatchers.IO) {
        // Манифест из сети, сохранённый прошлыми версиями, больше не читается — удаляем, чтобы не вводил в заблуждение.
        runCatching { File(layout.baseDir, "runtime/${RootfsNaming.MANIFEST_CACHE_FILE}").delete() }
        bundledManifest()
    }

    /** Версия DSH встроенного манифеста; нет или неверный формат — null. */
    fun bundledDshVersion(): String? =
        bundledManifest()?.runtimeVersion?.let(RootfsNaming::dshVersion)

    private fun bundledManifest(): RootfsManifest? = runCatching {
        val raw = context.assets.open(RootfsNaming.MANIFEST_ASSET).bufferedReader().use { it.readText() }
        parseBundledManifest(raw)
    }.getOrNull()

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Разобрать встроенный манифест. Каждый архив для загрузки — только по https и с SHA-256 в самом APK:
         * без закреплённого хэша образ не ставится (fail closed).
         */
        fun parseBundledManifest(raw: String): RootfsManifest? {
            val manifest = json.decodeFromString(RootfsManifest.serializer(), raw)
            if (manifest.runtimeVersion.isBlank()) return null
            val pinned = manifest.downloadUrls.all { (abi, url) ->
                url.startsWith("https://") && manifest.downloadSha256[abi].orEmpty().matches(SHA256)
            } && manifest.assetArchives.keys.all { abi -> manifest.assetSha256[abi].orEmpty().matches(SHA256) }
            return manifest.takeIf { pinned }
        }

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
