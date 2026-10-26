package com.deepseek.dsh.core.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Модель протокола `bridge/v1`.
 *
 * Это клиентское отражение контракта плагина `dsh-mobile-bridge` в Host: имена полей
 * совпадают с `docs/protocol.md`. Незнакомые поля [BridgeJson] пропускает, поэтому новые
 * поля Host не ломают выпущенный клиент; незнакомые id provider тоже пропускаются.
 */
object BridgeProtocol {
    /** Версия протокола; незнакомую основную версию клиент отклоняет. */
    const val BRIDGE_VERSION: String = "1.0.0"

    /** Общий префикс маршрутов. */
    const val BASE_PATH: String = "/dsh-mobile/bridge/v1"

    /** Маршрут каталога provider. */
    const val MANIFEST_PATH: String = "$BASE_PATH/manifest"

    /** Маршрут запросов. */
    const val QUERY_PATH: String = "$BASE_PATH/query"

    /** id provider `client-assets`. */
    const val PROVIDER_CLIENT_ASSETS: String = "client-assets"

    /** id provider `client-slots`. */
    const val PROVIDER_CLIENT_SLOTS: String = "client-slots"

    /** id provider `package-inventory`. */
    const val PROVIDER_PACKAGE_INVENTORY: String = "package-inventory"

    /** id provider `native-plugins` (список нативных компаньонов). */
    const val PROVIDER_NATIVE_PLUGINS: String = "native-plugins"

    /** id provider `rpc`. */
    const val PROVIDER_RPC: String = "rpc"
}

/** Ответ `GET /manifest` Host. */
@Serializable
data class BridgeManifest(
    val bridgeVersion: String = "",
    /** Версия самого плагина; старый плагин её не сообщает — null. */
    val pluginVersion: String? = null,
    val dshVersion: String? = null,
    val providers: List<BridgeProviderEntry> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /** Найти provider по id. */
    fun provider(id: String): BridgeProviderEntry? = providers.firstOrNull { it.id == id }

    /** Обслуживает ли provider заданную версию. */
    fun serves(id: String, version: Int): Boolean = provider(id)?.version == version

    /** Понимает ли клиент основную версию протокола Host. */
    fun isCompatible(): Boolean =
        bridgeVersion.substringBefore('.').toIntOrNull() ==
            BridgeProtocol.BRIDGE_VERSION.substringBefore('.').toIntOrNull()
}

/** Provider в manifest. */
@Serializable
data class BridgeProviderEntry(
    val id: String = "",
    val version: Int = 0,
    val capabilities: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val authorization: String? = null,
)

/** Тело запроса `POST /query`. */
@Serializable
data class BridgeQuery(
    val provider: String,
    val op: String,
    val args: Map<String, JsonElement> = emptyMap(),
)

/** Тело ответа `POST /query`: либо успех, либо ошибка, частичного успеха нет. */
@Serializable
data class BridgeQueryResponse(
    val ok: Boolean = false,
    val provider: String? = null,
    val version: Int = 0,
    val data: JsonElement? = null,
    val warnings: List<String> = emptyList(),
    val error: BridgeQueryError? = null,
)

/** Стабильный код ошибки и текст диагностики. */
@Serializable
data class BridgeQueryError(
    val code: String = "",
    val message: String = "",
    val details: JsonElement? = null,
)

/** Стабильные коды ошибок. Клиент сопоставляет код со своим текстом и message не разбирает. */
object BridgeErrorCode {
    const val REQUEST_MALFORMED: String = "request/malformed"
    const val REQUEST_UNSUPPORTED_METHOD: String = "request/unsupported-method"
    const val PROVIDER_UNKNOWN: String = "provider/unknown"
    const val PROVIDER_UNAVAILABLE: String = "provider/unavailable"
    const val PROVIDER_VERSION_MISMATCH: String = "provider/version-mismatch"
    const val OP_UNKNOWN: String = "op/unknown"
    const val ARGS_INVALID: String = "args/invalid"
    const val BUDGET_EXCEEDED: String = "budget/exceeded"
    const val AUTH_REQUIRED: String = "auth/required"
    const val INTERNAL_ERROR: String = "internal/error"

    /** Код клиента: Host недоступен. */
    const val TRANSPORT_UNREACHABLE: String = "transport/unreachable"

    /** Код клиента: ответ не разобрать. */
    const val TRANSPORT_DECODE_FAILED: String = "transport/decode-failed"
}

/** Данные `client-assets` / `boot-graph`. */
@Serializable
data class BridgeBootGraph(
    val rev: String = "",
    val release: BridgeBootRelease? = null,
    val entries: List<BridgeBootEntry> = emptyList(),
    val batches: List<BridgeBootBatch> = emptyList(),
)

/** Выпуск Host. */
@Serializable
data class BridgeBootRelease(
    val version: String = "",
    val commit: String? = null,
)

/** Строка boot manifest. */
@Serializable
data class BridgeBootEntry(
    val id: String = "",
    val url: String = "",
    val rev: String = "",
    val inject: List<String> = emptyList(),
    val immediately: Boolean = false,
    val external: List<String> = emptyList(),
)

/** Описание начального combo-скрипта. */
@Serializable
data class BridgeBootBatch(
    val phase: String = "",
    val url: String = "",
    val rev: String = "",
    val entries: List<String> = emptyList(),
)

/** Данные `client-assets` / `module-source`. */
@Serializable
data class BridgeModuleSource(
    val id: String = "",
    val path: String = "",
    val bytes: Long = 0,
    val complete: Boolean = false,
    val sha256: String = "",
    val source: String = "",
)

/** Данные `client-assets` / `locale`. */
@Serializable
data class BridgeLocale(
    val namespace: String = "",
    val locale: String = "",
    val available: List<String> = emptyList(),
    val dictionary: Map<String, String> = emptyMap(),
)

/** Регистрация слота (в виде данных). */
@Serializable
data class BridgeSlotRegistration(
    val slot: String = "",
    val id: String? = null,
    val key: String? = null,
    val order: Int? = null,
    val label: String? = null,
    val priority: Int? = null,
    /** Нативное место, определённое мостом, например `drawer` / `conversation` / `settings.page`. */
    val placement: String? = null,
    @SerialName("package") val packageName: String? = null,
    val locale: String? = null,
    /** Откуда регистрация: `static` (статический разбор) или `evaluated` (выполнение в VM); старые плагины не сообщают. */
    val origin: String? = null,
    val render: BridgeSlotRender? = null,
) {
    /** Стабильный идентификатор для dispatch: сначала `id`, у слотов с ключом — `key`. */
    val identity: String get() = id ?: key ?: render?.componentId ?: slot

    /** Компонент — аргумент `render-tree` / `icon-svg`. */
    val componentId: String? get() = render?.componentId
}

/** Компонент регистрации. */
@Serializable
data class BridgeSlotRender(
    val kind: String = "",
    val componentId: String = "",
) {
    val isFactory: Boolean get() = kind == "factory"
}

/** Данные `client-slots` / `list`. */
@Serializable
data class BridgeSlotList(
    val slot: String? = null,
    val registrations: List<BridgeSlotRegistration> = emptyList(),
    val emptySlots: List<String> = emptyList(),
)

/** Узел сериализованного дерева: элемент, текст или неразборная заглушка. */
@Serializable
data class BridgeRenderNode(
    val kind: String = "",
    val type: String? = null,
    val key: String? = null,
    val props: Map<String, JsonElement> = emptyMap(),
    val children: List<BridgeRenderNode> = emptyList(),
    val value: String? = null,
    val reason: String? = null,
)

/** Данные `client-slots` / `render-tree`. */
@Serializable
data class BridgeRenderTree(
    val componentId: String = "",
    val tree: BridgeRenderNode? = null,
)

/** Данные `client-slots` / `icon-svg`. */
@Serializable
data class BridgeIconSvg(
    val componentId: String = "",
    val size: Int = 0,
    val svg: String? = null,
)

/** Bundle из `package-inventory`. */
@Serializable
data class BridgeBundle(
    val name: String = "",
    val official: Boolean = false,
    val optional: Boolean = false,
    val removable: Boolean = false,
    val enabled: Boolean = false,
    val installed: Boolean = false,
    val availability: String = "",
    val version: String? = null,
    val source: String? = null,
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val rows: List<BridgeBundleRow> = emptyList(),
    val errorCode: String? = null,
    /** Правила официальной страницы плагинов от моста (старый мост не присылает — решает приложение). */
    val profileRoot: Boolean = false,
    val pinned: Boolean = false,
    val order: Int = 0,
    val hidden: Boolean = false,
    val market: Boolean = false,
    val locked: Boolean = false,
)

/** Строка bundle. */
@Serializable
data class BridgeBundleRow(
    val rowId: String = "",
    val moduleName: String = "",
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val enabled: Boolean? = null,
    /** id входа Loader; пусто, если Host не превратил строку во вход — тогда её не переключить. */
    val entryId: String? = null,
)

/** Строка Loader из `package-inventory`. */
@Serializable
data class BridgeInventoryRow(
    val entryId: String = "",
    val moduleName: String = "",
    val enabled: Boolean = false,
    val fiberPhase: String? = null,
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
)

/** Данные `package-inventory` / `bundles` и `/rows`. */
@Serializable
data class BridgeInventory(
    val bundles: List<BridgeBundle> = emptyList(),
    val rows: List<BridgeInventoryRow> = emptyList(),
    val managementAvailable: Boolean = false,
)

/** Данные `package-inventory` / `meta-icon`. */
@Serializable
data class BridgeMetaIcon(
    val name: String = "",
    val icon: String? = null,
)

/** `native-plugins`: объявление нативного компаньона (`dsh.native` в package.json JS-плагина). */
@Serializable
data class BridgeNativeCompanion(
    val pluginId: String = "",
    val name: String? = null,
    val apiVersion: Int? = null,
    val minAppVersion: Int? = null,
    val capabilities: List<String> = emptyList(),
    val storeUrl: String? = null,
)

/** Данные `native-plugins` / `for`. */
@Serializable
data class BridgeNativeCompanionResult(
    val companion: BridgeNativeCompanion? = null,
)

/** Данные `native-plugins` / `list`. */
@Serializable
data class BridgeNativeCompanionList(
    val companions: List<BridgeNativeCompanion> = emptyList(),
)

/**
 * Элемент `host-slots/list`.
 *
 * `slot` — **нативное** место (например, `settings.page`), а не имя слота браузера: мост уже
 * разложил вклады, которые может перечислить сервер, и клиент просто рисует их на месте.
 */
@Serializable
data class BridgeSlotSurface(
    val slot: String,
    val id: String,
    val title: String,
    val description: String? = null,
    val module: String? = null,
)

/** Ответ `host-slots/list`. */
@Serializable
data class BridgeSlotSurfaceList(
    val surfaces: List<BridgeSlotSurface> = emptyList(),
)

/** Настроенное место секрета по данным Host (значение не передаётся никогда). */
@Serializable
data class BridgeSecretSlot(
    val path: List<String> = emptyList(),
    val set: Boolean = false,
)

/** Ответ `settings/describe`. */
@Serializable
data class BridgeSettingsDocument(
    val ns: String,
    val revision: Int = 0,
    val applies: String? = null,
    val schema: JsonObject = JsonObject(emptyMap()),
    val value: JsonObject = JsonObject(emptyMap()),
    /** Места полей-секретов; по ним клиент пишет «Задано», не показывая секрет. */
    val secrets: List<BridgeSecretSlot> = emptyList(),
)

/** Ответ `settings/update`. */
@Serializable
data class BridgeSettingsWriteResult(
    val ns: String,
    val updated: Boolean = false,
)

/** Короткое сообщение из `events/pending`. */
@Serializable
data class BridgeTransientEvent(
    val kind: String,
    val text: String,
    val seq: Long = 0,
)

/** Ответ `events/pending`. */
@Serializable
data class BridgeTransientEventList(
    val events: List<BridgeTransientEvent> = emptyList(),
)
