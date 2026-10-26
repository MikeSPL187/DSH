package com.deepseek.dsh.core.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Клиентский фасад смысла provider.
 *
 * [BridgeClient] знает только протокол; здесь операции четырёх известных provider — именованные методы,
 * а без provider возвращается [BridgeResult.Failure] (`provider/unavailable` или
 * `provider/unknown`), и вызывающий переходит к списку Host — данные не выдумываются.
 */
class BridgeRegistry(
    private val client: BridgeClient,
    private val json: kotlinx.serialization.json.Json = BridgeJson.instance,
) {
    /** Фасад поверх клиента OkHttp с сессией. */
    constructor(
        baseUrl: String,
        httpClient: okhttp3.OkHttpClient,
    ) : this(BridgeClient(baseUrl, httpClient))

    /** Последний согласованный каталог provider — по нему интерфейс выбирает запасной путь. */
    @Volatile
    var manifest: BridgeManifest? = null
        private set

    /** Согласовать каталог provider и закэшировать. */
    suspend fun negotiate(forceRefresh: Boolean = false): BridgeResult<BridgeManifest> {
        val result = client.manifest(forceRefresh)
        if (result is BridgeResult.Success) manifest = result.value
        return result
    }

    /** Доступен ли `client-slots`. */
    fun hasClientSlots(): Boolean = manifest?.serves(BridgeProtocol.PROVIDER_CLIENT_SLOTS, 1) == true

    /** Доступен ли `package-inventory`. */
    fun hasPackageInventory(): Boolean =
        manifest?.serves(BridgeProtocol.PROVIDER_PACKAGE_INVENTORY, 1) == true

    /** Доступен ли `client-assets`. */
    fun hasClientAssets(): Boolean = manifest?.serves(BridgeProtocol.PROVIDER_CLIENT_ASSETS, 1) == true

    /**
     * Версия работающего плагина (`pluginVersion` в manifest).
     *
     * Старые плагины её не сообщают — тогда null; вызывающий берёт версию с диска и не путает
     * «не сообщил» с «не установлен».
     * @param forceRefresh заново согласовать manifest, отбросив кэш.
     * @return версия; null — согласование не удалось или Host не сообщил.
     */
    suspend fun pluginVersion(forceRefresh: Boolean = false): String? {
        if (forceRefresh || manifest == null) {
            val negotiated = negotiate(forceRefresh)
            if (negotiated is BridgeResult.Failure) return null
        }
        return manifest?.pluginVersion?.takeIf { it.isNotBlank() }
    }

    // ── client-assets ────────────────────────────────────────────────────────

    /** Boot manifest, который Host встраивает в официальную оболочку. */
    suspend fun bootGraph(): BridgeResult<BridgeBootGraph> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_ASSETS,
        op = "boot-graph",
    ) { json.decodeFromString(BridgeBootGraph.serializer(), it.toString()) }

    /** Исходник bundle клиентского entry (в пределах лимита байтов Host). */
    suspend fun moduleSource(id: String): BridgeResult<BridgeModuleSource> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_ASSETS,
        op = "module-source",
        args = BridgeArgs.of("id", id),
    ) { json.decodeFromString(BridgeModuleSource.serializer(), it.toString()) }

    /** Словарь локали пакета. */
    suspend fun locale(namespace: String, locale: String? = null): BridgeResult<BridgeLocale> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_ASSETS,
        op = "locale",
        args = if (locale == null) BridgeArgs.of("namespace", namespace)
        else BridgeArgs.of("namespace", namespace, "locale", locale),
    ) { json.decodeFromString(BridgeLocale.serializer(), it.toString()) }

    // ── client-slots ─────────────────────────────────────────────────────────

    /**
     * Регистрации слотов; при `slot` null — все.
     *
     * @param locale язык интерфейса клиента (BCP-47, например `ru-RU`). `label` регистрации
     *   плагина обычно thunk `t('key')`: только с языком он даёт тот же текст, что в веб-интерфейсе;
     *   без языка мост берёт язык по умолчанию из своих настроек.
     */
    suspend fun slotRegistrations(
        slot: String? = null,
        locale: String? = null,
    ): BridgeResult<BridgeSlotList> {
        val args = buildJsonObject {
            slot?.let { put("slot", JsonPrimitive(it)) }
            locale?.takeIf { it.isNotBlank() }?.let { put("locale", JsonPrimitive(it)) }
        }
        return query(
            provider = BridgeProtocol.PROVIDER_CLIENT_SLOTS,
            op = "list",
            args = args,
        ) { json.decodeFromString(BridgeSlotList.serializer(), it.toString()) }
    }

    /** Полные метаданные регистрации. */
    suspend fun describeSlot(slot: String, id: String): BridgeResult<BridgeSlotRegistration> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_SLOTS,
        op = "describe",
        args = BridgeArgs.of("slot", slot, "id", id),
    ) { json.decodeFromString(BridgeSlotRegistration.serializer(), it.toString()) }

    /** Дерево элементов компонента. */
    suspend fun renderTree(
        componentId: String,
        props: JsonObject = buildJsonObject { },
    ): BridgeResult<BridgeRenderTree> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_SLOTS,
        op = "render-tree",
        args = BridgeArgs.ofObject("props", props) + BridgeArgs.of("componentId", componentId),
    ) { json.decodeFromString(BridgeRenderTree.serializer(), it.toString()) }

    /** Графика компонента — отдельным SVG-документом. */
    suspend fun iconSvg(componentId: String, size: Int = 24): BridgeResult<BridgeIconSvg> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_SLOTS,
        op = "icon-svg",
        args = BridgeArgs.ofInt("size", size) + BridgeArgs.of("componentId", componentId),
    ) { json.decodeFromString(BridgeIconSvg.serializer(), it.toString()) }

    /**
     * Передать плагину нативное действие (нажатие, ввод, переключатель, отправку).
     *
     * Приложение JS плагина не выполняет: в Host событие становится сообщением шины
     * `ui/<componentId>/<event>`, а телефону ставится `client/refresh` — дерево компонента перечитывается.
     */
    suspend fun dispatchUiEvent(
        componentId: String,
        event: String,
        payload: JsonObject = buildJsonObject { },
    ): BridgeResult<Boolean> = query(
        provider = BridgeProtocol.PROVIDER_CLIENT_SLOTS,
        op = "dispatch",
        args = BridgeArgs.of("componentId", componentId, "event", event) +
            BridgeArgs.ofObject("payload", payload),
    ) { element ->
        // handled моста — выполнила ли событие половина плагина в Host; если нет, клиенту нужен запасной путь.
        ((element as? kotlinx.serialization.json.JsonObject)
            ?.get("handled") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: false
    }

    // ── package-inventory ────────────────────────────────────────────────────

    /** Список bundle Host, можно сузить по имени. */
    /**
     * Вклады плагинов, которые Host может перечислить и которые соответствуют нативному интерфейсу.
     *
     * В отличие от `slotRegistrations`, где оцениваются клиентские bundle, а у собранного веб-клиента
     * нечего оценивать, здесь читаются серверные данные самого Host — поэтому работает и на таких Host.
     */
    /** Документ настроек плагина (форма, значения, версия). */
    suspend fun settingsDescribe(ns: String): BridgeResult<BridgeSettingsDocument> = query(
        provider = BridgeSettingsProvider,
        op = "describe",
        args = BridgeArgs.of("ns", ns),
    ) { json.decodeFromString(BridgeSettingsDocument.serializer(), it.toString()) }

    // ── native-plugins ───────────────────────────────────────────────────────

    /** Есть ли у JS-плагина нативный компаньон APK; если да — его объявление. */
    suspend fun nativeCompanionFor(pluginId: String): BridgeResult<BridgeNativeCompanion?> = query(
        provider = BridgeProtocol.PROVIDER_NATIVE_PLUGINS,
        op = "for",
        args = BridgeArgs.of("pluginId", pluginId),
    ) { element ->
        json.decodeFromString(BridgeNativeCompanionResult.serializer(), element.toString()).companion
    }

    /** Все объявленные нативные компаньоны (рекомендации к установке). */
    suspend fun nativeCompanions(): BridgeResult<BridgeNativeCompanionList> = query(
        provider = BridgeProtocol.PROVIDER_NATIVE_PLUGINS,
        op = "list",
    ) { json.decodeFromString(BridgeNativeCompanionList.serializer(), it.toString()) }

    /** Записать изменённые поля; при несовпадении `expectedRevision` Host откажет. */
    suspend fun settingsUpdate(
        ns: String,
        patch: JsonObject,
        expectedRevision: Int,
    ): BridgeResult<BridgeSettingsWriteResult> = query(
        provider = BridgeSettingsProvider,
        op = "update",
        args = BridgeArgs.ofObject("patch", patch) +
            BridgeArgs.ofInt("expectedRevision", expectedRevision) +
            BridgeArgs.of("ns", ns),
    ) { json.decodeFromString(BridgeSettingsWriteResult.serializer(), it.toString()) }

    /** Забрать короткие сообщения Host; каждый вызов очищает очередь. */
    suspend fun pendingEvents(): BridgeResult<BridgeTransientEventList> = query(
        provider = BridgeEventsProvider,
        op = "pending",
    ) { json.decodeFromString(BridgeTransientEventList.serializer(), it.toString()) }

    suspend fun hostSlotSurfaces(locale: String? = null): BridgeResult<BridgeSlotSurfaceList> =
        query(
            provider = BridgeHostSlotsProvider,
            op = "list",
            args = locale?.takeIf { it.isNotBlank() }
                ?.let { BridgeArgs.of("locale", it) } ?: BridgeArgs.none(),
        ) { json.decodeFromString(BridgeSlotSurfaceList.serializer(), it.toString()) }

    suspend fun bundles(
        name: String? = null,
        locale: String? = null,
    ): BridgeResult<BridgeInventory> = query(
        provider = BridgeProtocol.PROVIDER_PACKAGE_INVENTORY,
        op = "bundles",
        args = (if (name == null) BridgeArgs.none() else BridgeArgs.of("name", name)) +
            (locale?.takeIf { it.isNotBlank() }?.let { BridgeArgs.of("locale", it) }
                ?: BridgeArgs.none()),
    ) { json.decodeFromString(BridgeInventory.serializer(), it.toString()) }

    /** Список строк Loader в Host. */
    suspend fun rows(): BridgeResult<BridgeInventory> = query(
        provider = BridgeProtocol.PROVIDER_PACKAGE_INVENTORY,
        op = "rows",
    ) { json.decodeFromString(BridgeInventory.serializer(), it.toString()) }

    /** `meta.icon` (data URL) bundle или строки. */
    suspend fun metaIcon(name: String): BridgeResult<BridgeMetaIcon> = query(
        provider = BridgeProtocol.PROVIDER_PACKAGE_INVENTORY,
        op = "meta-icon",
        args = BridgeArgs.of("name", name),
    ) { json.decodeFromString(BridgeMetaIcon.serializer(), it.toString()) }

    // ── rpc ──────────────────────────────────────────────────────────────────

    /** Вызвать удалённый метод из белого списка Host. */
    suspend fun invoke(
        namespace: String,
        method: String,
        args: JsonObject = buildJsonObject { },
    ): BridgeResult<JsonElement> = query(
        provider = BridgeProtocol.PROVIDER_RPC,
        op = "invoke",
        args = BridgeArgs.of("namespace", namespace, "method", method) + BridgeArgs.ofObject("args", args),
    ) { element ->
        val record = element.jsonObject
        record["result"] ?: JsonPrimitive("")
    }

    /** Общая обёртка query: без provider — сразу ошибка, без сети. */
    private suspend fun <T> query(
        provider: String,
        op: String,
        args: Map<String, JsonElement> = emptyMap(),
        decode: (JsonElement) -> T,
    ): BridgeResult<T> {
        if (manifest == null) {
            val negotiated = negotiate()
            if (negotiated is BridgeResult.Failure) return negotiated
        }
        val known = manifest?.provider(provider)
            ?: return BridgeResult.Failure(
                BridgeErrorCode.PROVIDER_UNKNOWN,
                "this Host does not offer the $provider provider",
            )
        if (op !in known.capabilities) {
            return BridgeResult.Failure(
                BridgeErrorCode.OP_UNKNOWN,
                "provider $provider does not offer $op",
            )
        }
        return client.query(provider, op, args, decode)
    }
}

/**
 * Сгруппировать регистрации по имени слота, сохраняя порядок Host.
 *
 * Клиент берёт по слоту (`plugins.item`, `settings.section` и т. д.), поэтому группировка —
 * единственная нужная организация; второго уровня категорий нет.
 */
fun BridgeSlotList.bySlot(): Map<String, List<BridgeSlotRegistration>> =
    registrations.groupBy { it.slot }

/** Строковое значение prop дерева; нет — null. */
fun BridgeRenderNode.stringProp(name: String): String? {
    val value = props[name] ?: return null
    return runCatching { value.jsonPrimitive.content }.getOrNull()
}

/** Весь текст дочерних узлов по порядку. */
fun BridgeRenderNode.textContent(): String = when (kind) {
    "text" -> value.orEmpty()
    "element" -> children.joinToString(separator = "") { it.textContent() }
    else -> ""
}

/** Элемент — тег хоста (а не компонент или fragment). */
fun BridgeRenderNode.isHostTag(): Boolean =
    kind == "element" && type != null && !type.startsWith("component:") && type != "fragment"

/** Первый элемент дерева с данным типом. */
fun BridgeRenderNode.findByType(typeName: String): BridgeRenderNode? {
    if (this.type == typeName) return this
    for (child in children) {
        val found = child.findByType(typeName)
        if (found != null) return found
    }
    return null
}

/** `viewBox` из документа `svg`; нет — 24×24. */
fun BridgeIconSvg.viewBoxOrNull(): String? {
    val svg = svg ?: return null
    val match = VIEW_BOX.find(svg) ?: return null
    return match.groupValues.getOrNull(1)
}

private val VIEW_BOX = Regex("""viewBox="([^"]+)"""")

/** Массив JSON дерева — в список узлов (для своих разборщиков). */
internal fun JsonElement.asNodeList(): List<BridgeRenderNode> = jsonArray.map {
    BridgeJson.instance.decodeFromString(BridgeRenderNode.serializer(), it.toString())
}

internal const val BridgeHostSlotsProvider = "host-slots"
internal const val BridgeSettingsProvider = "settings"
internal const val BridgeEventsProvider = "events"
