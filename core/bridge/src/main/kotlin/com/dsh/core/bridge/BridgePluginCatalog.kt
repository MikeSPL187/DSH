package com.dsh.core.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Источник страницы плагинов: два provider моста сводятся в один каталог.
 *
 * Порядок строго такой:
 * 1. регистрации `plugins.item` из `client-slots` — категории, тексты и значки из ресурсов клиента;
 * 2. список bundle / row из `package-inventory` — собственный список Host;
 * 3. нет ни того ни другого — [BridgePluginCatalogSource.NONE], интерфейс показывает пустое состояние, а не выдумку.
 *
 * Значок — сначала `client-slots/icon-svg` (официальная графика), иначе собственный
 * `meta.icon` пакета (data URL). Нет обоих — пусто, локальные заглушки не подставляются.
 */
object BridgePluginCatalog {

    /** Слот `plugins.item`: в нём группа «Настройки встроенных плагинов» страницы плагинов. */
    const val PLUGINS_ITEM_SLOT: String = "plugins.item"

    /** Сторона значка по умолчанию. */
    const val DEFAULT_ICON_SIZE: Int = 40

    /**
     * Прочитать каталог плагинов.
     *
     * @param registry мост с согласованными provider.
     * @param iconSize сторона значка.
     * @return каталог; если оба provider недоступны — пустой с `source = NONE`.
     */
    suspend fun load(
        registry: BridgeRegistry,
        iconSize: Int = DEFAULT_ICON_SIZE,
        locale: String? = null,
    ): BridgePluginCatalogResult {
        val warnings = mutableListOf<String>()

        // Сначала согласование: есть ли provider — решает manifest, до него все has* ложны.
        when (val negotiated = registry.negotiate()) {
            is BridgeResult.Success -> warnings += negotiated.warnings
            is BridgeResult.Failure -> return BridgePluginCatalogResult(
                catalog = BridgePluginCatalogData(source = BridgePluginCatalogSource.NONE),
                warnings = listOf(hintFor(negotiated)),
            )
        }

        val slotList = if (registry.hasClientSlots()) {
            when (val result = registry.slotRegistrations(PLUGINS_ITEM_SLOT, locale)) {
                is BridgeResult.Success -> {
                    warnings += result.warnings
                    result.value
                }
                is BridgeResult.Failure -> {
                    warnings += "client-slots unavailable: ${result.message}"
                    null
                }
            }
        } else null

        val inventory = if (registry.hasPackageInventory()) {
            when (val result = registry.bundles(locale = locale)) {
                is BridgeResult.Success -> {
                    warnings += result.warnings
                    result.value
                }
                is BridgeResult.Failure -> {
                    warnings += "package-inventory unavailable: ${result.message}"
                    null
                }
            }
        } else null

        val categories = slotList?.registrations
            ?.filter { it.slot == PLUGINS_ITEM_SLOT }
            ?.sortedWith(compareBy({ it.order ?: 0 }, { it.identity }))
            ?.map { registration ->
                BridgePluginCategory(
                    id = registration.identity,
                    label = registration.label ?: registration.identity,
                    packageName = registration.packageName,
                    locale = registration.locale,
                    order = registration.order ?: 0,
                    componentId = registration.componentId,
                    // У официальных элементов нет оцениваемого компонента: мост отдаёт готовую
                    // графику по id регистрации, поэтому ключ значка работает в обоих случаях.
                    iconSvg = iconOf(
                        registry,
                        registration.componentId ?: registration.identity,
                        iconSize,
                        warnings,
                    ),
                )
            }
            .orEmpty()

        val bundles = inventory?.bundles.orEmpty().map { bundle ->
            BridgePluginBundleSummary(
                name = bundle.name,
                title = bundle.title ?: bundle.name,
                description = bundle.description,
                version = bundle.version,
                official = bundle.official,
                optional = bundle.optional,
                removable = bundle.removable,
                enabled = bundle.enabled,
                installed = bundle.installed,
                availability = bundle.availability,
                errorCode = bundle.errorCode,
                iconDataUrl = bundle.icon,
                profileRoot = bundle.profileRoot,
                pinned = bundle.pinned,
                order = bundle.order,
                hidden = bundle.hidden,
                market = bundle.market,
                locked = bundle.locked,
                rows = bundle.rows.map { row ->
                    BridgePluginRowSummary(
                        rowId = row.rowId,
                        moduleName = row.moduleName,
                        title = row.title ?: row.moduleName,
                        description = row.description,
                        iconDataUrl = row.icon,
                        enabled = row.enabled,
                        entryId = row.entryId,
                    )
                },
            )
        }

        val source = when {
            categories.isNotEmpty() -> BridgePluginCatalogSource.CLIENT_SLOTS
            bundles.isNotEmpty() -> BridgePluginCatalogSource.PACKAGE_INVENTORY
            else -> BridgePluginCatalogSource.NONE
        }
        return BridgePluginCatalogResult(
            catalog = BridgePluginCatalogData(
                source = source,
                categories = categories,
                bundles = bundles,
                managementAvailable = inventory?.managementAvailable == true,
            ),
            warnings = warnings,
        )
    }

    /**
     * Официальная графика компонента.
     *
     * Без исключений: значок — украшение, без него каталог работает. Причина ошибки — в
     * warnings, чтобы было видно, почему у плагина нет значка.
     */
    private suspend fun iconOf(
        registry: BridgeRegistry,
        componentId: String?,
        size: Int,
        warnings: MutableList<String>,
    ): String? {
        if (componentId == null) return null
        return when (val result = registry.iconSvg(componentId, size)) {
            is BridgeResult.Success -> result.value.svg
            is BridgeResult.Failure -> {
                warnings += "icon $componentId: ${result.message}"
                null
            }
        }
    }

    /**
     * Дерево элементов компонента `plugins.item`.
     * Как рисовать дерево нативно, решает клиент; мост интерфейса не понимает, он отдаёт данные.
     *
     * @param registry мост с согласованными provider.
     * @param componentId компонент.
     * @param props props компонента; по умолчанию `view = "summary"` — одна фраза для карточки.
     */
    suspend fun renderItem(
        registry: BridgeRegistry,
        componentId: String,
        props: JsonObject = buildJsonObject { put("view", "summary") },
    ): BridgeResult<BridgeRenderTree> = registry.renderTree(componentId, props)
}

/** Источник каталога — от него зависит вид страницы. */
/**
 * Ошибку — в подсказку, которой можно последовать.
 *
 * «bridge unavailable» мало: чаще всего в rootfs ещё нет плагина
 * (старый образ) — нужно нажать «Обновить плагины» в настройках, а не проверять сеть.
 * @param failure стабильный код и текст диагностики ошибки согласования.
 * @returns одна фраза для пользователя.
 */
private fun hintFor(failure: BridgeResult.Failure): String = when (failure.code) {
    BridgeErrorCode.TRANSPORT_UNREACHABLE ->
        com.dsh.core.model.Tr.t("Host is not ready: cannot reach the local runtime. Wait or restart the runtime.", "Host не готов: нет связи с локальной средой. Подождите или перезапустите среду.")
    BridgeErrorCode.PROVIDER_UNKNOWN ->
        com.dsh.core.model.Tr.t("The Host has no bridge route: the compatibility plugin is not installed. Open Settings → Update and tap Update plugin.", "У Host нет маршрута bridge: плагин совместимости не установлен. Откройте «Настройки → Обновление» и нажмите «Обновить плагин».")
    BridgeErrorCode.TRANSPORT_DECODE_FAILED ->
        com.dsh.core.model.Tr.t("Cannot parse the Host bridge response (${failure.message}). The plugin may need an update.", "Не удалось разобрать ответ bridge от Host (${failure.message}). Возможно, плагин нужно обновить.")
    BridgeErrorCode.AUTH_REQUIRED ->
        com.dsh.core.model.Tr.t("The Host rejected the request: no session. Sign in again or restart the runtime.", "Host отклонил запрос: сессия не установлена. Войдите снова или перезапустите среду.")
    BridgeErrorCode.PROVIDER_VERSION_MISMATCH ->
        com.dsh.core.model.Tr.t("Plugin protocol version does not match the client (${failure.message}). Update the plugin in Settings → Update.", "Версия протокола плагина не совпадает с клиентом (${failure.message}). Обновите плагин в «Настройки → Обновление».")
    else -> com.dsh.core.model.Tr.t("bridge unavailable: ${failure.message}", "bridge недоступен: ${failure.message}")
}

enum class BridgePluginCatalogSource {
    /** Из регистраций слотов клиента: категории и значки официальные. */
    CLIENT_SLOTS,

    /** Из списка Host: только bundle / row, официальных категорий нет. */
    PACKAGE_INVENTORY,

    /** Нет ни того ни другого: пустое состояние. */
    NONE,
}

/** Каталог плагинов. */
data class BridgePluginCatalogData(
    val source: BridgePluginCatalogSource,
    val categories: List<BridgePluginCategory> = emptyList(),
    val bundles: List<BridgePluginBundleSummary> = emptyList(),
    val managementAvailable: Boolean = false,
)

/** Каталог и нефатальные замечания. */
data class BridgePluginCatalogResult(
    val catalog: BridgePluginCatalogData,
    val warnings: List<String> = emptyList(),
)

/** Категория официальных плагинов (одна регистрация `plugins.item`). */
data class BridgePluginCategory(
    val id: String,
    val label: String,
    val packageName: String?,
    val locale: String?,
    val order: Int,
    val componentId: String?,
    val iconSvg: String?,
)

/** Bundle из списка Host. */
data class BridgePluginBundleSummary(
    val name: String,
    val title: String,
    val description: String?,
    val version: String?,
    val official: Boolean,
    val optional: Boolean,
    val removable: Boolean,
    val enabled: Boolean,
    val installed: Boolean,
    /** Доступность по Host: из установки, установлен или отсутствует. */
    val availability: String = "",
    val errorCode: String?,
    val iconDataUrl: String?,
    val profileRoot: Boolean = false,
    val pinned: Boolean = false,
    val order: Int = 0,
    val hidden: Boolean = false,
    val market: Boolean = false,
    val locked: Boolean = false,
    val rows: List<BridgePluginRowSummary> = emptyList(),
)

/** Строка bundle. */
data class BridgePluginRowSummary(
    val rowId: String,
    val moduleName: String,
    val title: String,
    val description: String?,
    val iconDataUrl: String?,
    /** Whether this row is enabled; null means the Host did not report it. */
    val enabled: Boolean? = null,
    /** The Loader entry this row resolves to, when the Host reports one. */
    val entryId: String? = null,
)
