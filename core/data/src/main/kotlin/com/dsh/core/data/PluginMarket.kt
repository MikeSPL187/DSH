package com.dsh.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Модель и разбор магазина плагинов.
 *
 * Страница «Обзор» читает `pluginMarket` Host (`listIndexes` / `search` /
 * `setIndexes`); формат индекса задаёт `@deepseek-ai/dsh-plugin-market-index`. Здесь только
 * поля, нужные списку и карточке плагина, остальные пропускаются.
 */

/** Источник индекса. */
data class MarketIndexSummary(
    val url: String,
    /** Название магазина из последнего удачного чтения; null — не читали или ошибка. */
    val title: String?,
    /** Причина последней ошибки чтения. */
    val error: String?,
)

/** Версия для установки. */
data class MarketVersion(
    val version: String,
    /** Spec для установщика (npm — `name@version`, git — `git+...`). */
    val spec: String,
    val registry: String?,
    /** Время публикации (Unix, секунды). */
    val publishTimestamp: Long,
    val compatible: Boolean,
    /** Нужный диапазон версий DSH, если текущая не подходит. */
    val requirement: String?,
)

/** Плагин в магазине. */
data class MarketEntry(
    val name: String,
    val title: String,
    val description: String?,
    val author: String?,
    val homepage: String?,
    val labels: List<String>,
    val versions: List<MarketVersion>,
    val defaultVersion: String?,
    /** `not-installed` / `installed` / `update-available` / `different-version` / `provided`. */
    val installationStatus: String,
) {
    /** Версия для списка: по умолчанию, иначе первая. */
    val presentedVersion: MarketVersion?
        get() = versions.firstOrNull { it.version == defaultVersion } ?: versions.firstOrNull()
}

/** Страница результатов поиска. */
data class MarketListing(
    val url: String,
    val title: String?,
    val entries: List<MarketEntry>,
    val total: Int,
    val nextOffset: Int?,
    /** Время документа, из которого эта страница: по нему листают тот же документ. */
    val fetchedAt: Long,
)

/** Разбор `value` из `plugin-market/listIndexes`. */
internal fun parseMarketIndexes(value: JsonElement?): List<MarketIndexSummary> {
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj.string("url") ?: return@mapNotNull null
        MarketIndexSummary(
            url = url,
            title = localized(obj["title"]),
            error = obj.string("error"),
        )
    }
}

/** Разбор `value` из `plugin-market/search` (пусто, пока индекс не прочитан). */
internal fun parseMarketSearch(value: JsonElement?): MarketListing? {
    val obj = value as? JsonObject ?: return null
    val url = obj.string("url") ?: return null
    val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull { parseEntry(it) }
    return MarketListing(
        url = url,
        title = localized(obj["title"]),
        entries = entries,
        total = obj.int("total") ?: entries.size,
        nextOffset = (obj["nextOffset"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
        fetchedAt = obj["fetchedAt"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L,
    )
}

private fun parseEntry(element: JsonElement): MarketEntry? {
    val obj = element as? JsonObject ?: return null
    val name = obj.string("name") ?: return null
    val versions = (obj["versions"] as? JsonArray).orEmpty().mapNotNull { parseVersion(it) }
    val installation = obj["installation"] as? JsonObject
    return MarketEntry(
        name = name,
        title = localized(obj["title"]) ?: name,
        description = localized(obj["description"]),
        author = obj.string("author"),
        homepage = obj.string("homepage"),
        labels = (obj["labels"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { label -> label },
        versions = versions,
        defaultVersion = obj.string("defaultVersion"),
        installationStatus = installation?.string("status") ?: "not-installed",
    )
}

private fun parseVersion(element: JsonElement): MarketVersion? {
    val obj = element as? JsonObject ?: return null
    val version = obj.string("version") ?: return null
    val install = obj["install"] as? JsonObject
    val spec = install?.string("spec") ?: return null
    val compatibility = obj["compatibility"] as? JsonObject
    val compatible = (compatibility?.get("compatible") as? JsonPrimitive)?.booleanOrNull ?: true
    val requirements = compatibility?.get("requirements") as? JsonObject
    return MarketVersion(
        version = version,
        spec = spec,
        registry = install.string("registry"),
        publishTimestamp = obj["publishTimestamp"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L,
        compatible = compatible,
        requirement = requirements?.string("dsh"),
    )
}

/** `LocalizedText` (строка или `{en, ru, …}`): язык приложения, иначе английский, иначе первое непустое. */
internal fun localized(element: JsonElement?): String? {
    when (element) {
        null -> return null
        is JsonPrimitive -> return element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> {
            for (key in listOf(java.util.Locale.getDefault().language, "en")) {
                val text = element.string(key)?.takeIf { it.isNotBlank() }
                if (text != null) return text
            }
            return element.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .firstOrNull { it.isNotBlank() }
        }
        else -> return null
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
