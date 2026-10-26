package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.bridge.BridgePluginCatalog
import com.deepseek.dsh.core.bridge.BridgePluginCatalogData
import com.deepseek.dsh.core.bridge.BridgePluginCatalogSource
import com.deepseek.dsh.core.bridge.BridgeRegistry
import com.deepseek.dsh.core.bridge.BridgeResult
import com.deepseek.dsh.core.bridge.BridgeSlotSurface
import com.deepseek.dsh.core.bridge.BridgeTransientEvent
import com.deepseek.dsh.core.model.BuiltInPluginInfo
import com.deepseek.dsh.core.model.PluginInventorySnapshotInfo
import com.deepseek.dsh.core.model.PluginManagerSnapshot
import com.deepseek.dsh.core.model.PluginRowInfo
import com.deepseek.dsh.core.model.PluginSecretSlot
import com.deepseek.dsh.core.model.PluginSettingsDocument
import com.deepseek.dsh.core.model.PluginSettingsPage
import com.deepseek.dsh.core.model.PluginSlotEntry
import com.deepseek.dsh.core.model.Tr
import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.ForwardedEvent
import com.deepseek.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

/**
 * Плагины Host: инвентарь и менеджер (Remote API Host), каталог и слоты интерфейса
 * (`bridge/v1`), настройки и учётные данные плагинов, магазин, установка с прогрессом.
 */
class PluginsRepository internal constructor(
    private val client: DshClientProtocol,
    private val calls: HostCalls,
) {
    private val _builtInPluginsFlow = MutableStateFlow<List<BuiltInPluginInfo>>(emptyList())
    val builtInPluginsFlow: StateFlow<List<BuiltInPluginInfo>> = _builtInPluginsFlow.asStateFlow()

    private val _pluginSnapshotFlow = MutableStateFlow<PluginInventorySnapshotInfo?>(null)
    val pluginSnapshotFlow: StateFlow<PluginInventorySnapshotInfo?> = _pluginSnapshotFlow.asStateFlow()

    private val _pluginManagerSnapshotFlow = MutableStateFlow<PluginManagerSnapshot?>(null)
    val pluginManagerSnapshotFlow: StateFlow<PluginManagerSnapshot?> = _pluginManagerSnapshotFlow.asStateFlow()

    private val _pluginRowsFlow = MutableStateFlow<List<PluginRowInfo>>(emptyList())
    val pluginRowsFlow: StateFlow<List<PluginRowInfo>> = _pluginRowsFlow.asStateFlow()

    /**
     * Каталог страницы плагинов из `bridge/v1` (`client-slots`, `package-inventory`).
     * В отличие от [pluginManagerSnapshotFlow] в нём есть регистрации слотов и значки.
     * Без моста UI показывает снимок менеджера — придуманных категорий нет.
     */
    private val _bridgePluginCatalogFlow =
        MutableStateFlow(BridgePluginCatalogData(source = BridgePluginCatalogSource.NONE))
    val bridgePluginCatalogFlow: StateFlow<BridgePluginCatalogData> = _bridgePluginCatalogFlow.asStateFlow()

    /** Нефатальные замечания моста (для диагностики). */
    private val _bridgeWarningsFlow = MutableStateFlow<List<String>>(emptyList())
    val bridgeWarningsFlow: StateFlow<List<String>> = _bridgeWarningsFlow.asStateFlow()

    private val _pluginSettingsPagesFlow = MutableStateFlow<List<PluginSettingsPage>>(emptyList())

    /** Страницы настроек, которые объявили плагины. */
    val pluginSettingsPagesFlow: StateFlow<List<PluginSettingsPage>> = _pluginSettingsPagesFlow.asStateFlow()

    private val _pluginSlotSurfacesFlow = MutableStateFlow<List<BridgeSlotSurface>>(emptyList())

    /** Все нативные места интерфейса, объявленные плагинами; UI берёт только свои слоты. */
    val pluginSlotSurfacesFlow: StateFlow<List<BridgeSlotSurface>> = _pluginSlotSurfacesFlow.asStateFlow()

    private val _pluginSlotEntriesFlow = MutableStateFlow<List<PluginSlotEntry>>(emptyList())

    /**
     * Все точки входа плагинов с нативным местом. Источник — регистрации клиентских
     * пакетов (`client-slots`), место переводит мост. Экраны со своим местом (настройки,
     * список плагинов) берут своё сами; здесь — полный список для сводных видов.
     */
    val pluginSlotEntriesFlow: StateFlow<List<PluginSlotEntry>> = _pluginSlotEntriesFlow.asStateFlow()

    private val _componentRefreshFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * id компонентов, которые плагин просит перерисовать (событие `client/refresh`).
     * Экран, показывающий дерево компонента, перечитывает `component-tree` — без опроса.
     */
    val pluginComponentRefreshFlow: SharedFlow<String> = _componentRefreshFlow.asSharedFlow()

    /** Прогресс установки плагина (`plugin-manager/install-state` + хвост журнала). */
    private val _installProgressFlow = MutableStateFlow<PluginInstallProgressInfo?>(null)
    val installProgressFlow: StateFlow<PluginInstallProgressInfo?> = _installProgressFlow.asStateFlow()

    @Volatile
    private var activeInstallRequestId: String? = null
    private var installLogTail: String = ""

    /** Мост к Host; пересоздаётся при каждой привязке. */
    @Volatile
    private var bridgeRegistry: BridgeRegistry? = null

    /**
     * Привязать мост к текущему Host. Мост создаёт вызывающий: ему нужен CookieJar сессии
     * Host из сетевого слоя. Новый мост ставится даже на тот же адрес: Host мог перезапуститься
     * или обновить плагины, и старый кэш manifest устарел — повторное согласование дёшево.
     */
    fun bindBridge(registry: BridgeRegistry) {
        bridgeRegistry = registry
    }

    /** Текущий мост; null, пока Host не привязан. */
    fun bridge(): BridgeRegistry? = bridgeRegistry

    /**
     * Версия bridge-плагина, которая **сейчас работает** в Host — точнее файла на диске:
     * файл мог уже обновиться, а Host — ещё нет. null, если мост недоступен.
     * @param forceRefresh заново согласовать manifest (после перезапуска Host).
     */
    suspend fun bridgePluginVersion(forceRefresh: Boolean = false): String? =
        bridgeRegistry?.pluginVersion(forceRefresh)

    /**
     * Пространства настроек, принадлежащие официальным строкам Loader. По ним страница
     * моделей отличает официальных провайдеров от провайдеров плагинов.
     */
    fun officialSettingsNamespaces(): Set<String> =
        _pluginSlotSurfacesFlow.value
            .filter { surface -> surface.module?.startsWith("@deepseek-ai/") == true }
            .mapNotNull { surface -> surface.id.substringAfterLast('#').takeIf { it.isNotBlank() } }
            .toSet()

    /**
     * Перечитать точки входа и страницы настроек плагинов. Два независимых источника:
     * `client-slots/list` (регистрации и подписи) и `host-slots/list` (пространства настроек).
     * Сразу после запуска Host мост может быть не готов — ошибка одного не очищает другой,
     * а вызывающий периодически повторяет.
     */
    suspend fun refreshPluginSettingsPages() {
        val registry = bridgeRegistry ?: run {
            _pluginSlotSurfacesFlow.value = emptyList()
            _pluginSettingsPagesFlow.value = emptyList()
            _pluginSlotEntriesFlow.value = emptyList()
            return
        }
        // Подписи регистраций — thunk `t('key')`: мост разрешает их только с языком интерфейса.
        val locale = Locale.getDefault().toLanguageTag()
        when (val all = registry.slotRegistrations(locale = locale)) {
            is BridgeResult.Success -> {
                _pluginSlotEntriesFlow.value = all.value.registrations
                    // Пакет может найтись в нескольких node_modules (установка, ссылки профиля).
                    .distinctBy { "${it.slot}|${it.identity}|${it.packageName}" }
                    .map { registration ->
                        PluginSlotEntry(
                            placement = registration.placement ?: "other",
                            order = registration.order ?: 0,
                            slot = registration.slot,
                            id = registration.id,
                            key = registration.key,
                            title = registration.label,
                            module = registration.packageName,
                            componentId = registration.componentId,
                        )
                    }
            }
            // Разовый сбой не убирает точки входа, которыми пользуются сейчас.
            is BridgeResult.Failure -> Unit
        }
        val surfaces = when (val result = registry.hostSlotSurfaces(locale)) {
            is BridgeResult.Success -> result.value.surfaces
            is BridgeResult.Failure -> emptyList()
        }
        _pluginSlotSurfacesFlow.value = surfaces
        _pluginSettingsPagesFlow.value = surfaces
            .filter { it.slot == SETTINGS_PAGE_SLOT }
            .map { PluginSettingsPage(id = it.id, title = it.title, description = it.description, module = it.module) }
    }

    /** Забрать накопленные в Host короткие события плагинов; без моста — пусто. */
    suspend fun pendingPluginEvents(): List<BridgeTransientEvent> {
        val registry = bridgeRegistry ?: return emptyList()
        return when (val result = registry.pendingEvents()) {
            is BridgeResult.Success -> result.value.events
            is BridgeResult.Failure -> emptyList()
        }
    }

    /** События `client/refresh` — в [pluginComponentRefreshFlow]; тосты показывает вызывающий. */
    suspend fun dispatchPluginEvents(events: List<BridgeTransientEvent>) {
        events
            .filter { it.kind == "client/refresh" && it.text.isNotBlank() }
            .forEach { _componentRefreshFlow.emit(it.text) }
    }

    /**
     * Документ настроек плагина.
     * @param ns пространство настроек (id строки в Host).
     * @return форма и значения; null — страница покажет «Не удалось прочитать».
     */
    suspend fun pluginSettingsDocument(ns: String): PluginSettingsDocument? {
        val registry = bridgeRegistry ?: return null
        return when (val result = registry.settingsDescribe(ns)) {
            is BridgeResult.Success -> PluginSettingsDocument(
                ns = result.value.ns,
                revision = result.value.revision,
                schema = result.value.schema,
                value = result.value.value,
                secretSlots = result.value.secrets.map { PluginSecretSlot(path = it.path, set = it.set) },
            )
            is BridgeResult.Failure -> null
        }
    }

    /**
     * Записать настройки плагина.
     * @param expectedRevision ревизия при открытии формы: Host не даст затереть чужие правки.
     */
    suspend fun updatePluginSettings(ns: String, patch: JsonObject, expectedRevision: Int): Boolean {
        val registry = bridgeRegistry ?: return false
        return when (val result = registry.settingsUpdate(ns, patch, expectedRevision)) {
            is BridgeResult.Success -> result.value.updated
            is BridgeResult.Failure -> false
        }
    }

    /** Записать секрет; записанное значение больше не возвращает ни один вызов чтения. */
    suspend fun setCredential(hostId: String, ref: String, value: String): Boolean =
        credentialCallOk(hostId, "credentials/set", buildJsonObject { put("ref", ref); put("value", value) })

    /** Удалить секрет по ссылке. */
    suspend fun unsetCredential(hostId: String, ref: String): Boolean =
        credentialCallOk(hostId, "credentials/unset", buildJsonObject { put("ref", ref) })

    private suspend fun credentialCallOk(hostId: String, method: String, args: JsonObject): Boolean {
        val raw = calls.rpc(hostId, method, args).getOrNull() ?: return false
        val root = runCatching { hostJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return false
        return ((root["result"] as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * Перечитать каталог плагинов из моста. Ошибка — не исключение: каталог становится
     * `source = NONE`, причина — в [bridgeWarningsFlow]. Пока мост не привязан (Host
     * запускается), замечаний нет: «ещё не готово» — не проблема для пользователя.
     */
    suspend fun refreshBridgePluginCatalog(iconSize: Int = BridgePluginCatalog.DEFAULT_ICON_SIZE) {
        val registry = bridgeRegistry
        if (registry == null) {
            _bridgePluginCatalogFlow.value = BridgePluginCatalogData(source = BridgePluginCatalogSource.NONE)
            _bridgeWarningsFlow.value = emptyList()
            return
        }
        val result = BridgePluginCatalog.load(registry, iconSize, Locale.getDefault().toLanguageTag())
        _bridgePluginCatalogFlow.value = result.catalog
        _bridgeWarningsFlow.value = result.warnings
    }

    /** Перечитать инвентарь, снимок менеджера и строки Loader. */
    suspend fun refreshPluginInventory(hostId: String) {
        (client.getPluginInventorySnapshot(hostId) as? RemoteResult.Success)?.data?.let { snapshot ->
            _pluginSnapshotFlow.value = snapshot
            _builtInPluginsFlow.value =
                (snapshot.agentPresets.flatMap { it.rows } + snapshot.entries).distinctBy { it.packageName }
        }
        (client.getPluginManagerSnapshot(hostId) as? RemoteResult.Success)?.data?.let {
            _pluginManagerSnapshotFlow.value = it
        }
        (client.getPluginRows(hostId) as? RemoteResult.Success)?.data?.let { _pluginRowsFlow.value = it }
    }

    /**
     * Включить или выключить bundle. Возвращается полный итог: о `restart-required`
     * и `warnings` интерфейс обязан сказать пользователю.
     */
    suspend fun setBundleEnabled(hostId: String, packageName: String, enabled: Boolean): Result<BundleChangeOutcome> {
        _pluginManagerSnapshotFlow.value?.let { current ->
            _pluginManagerSnapshotFlow.value = current.copy(
                experimentalBundles = current.experimentalBundles.map {
                    if (it.packageName == packageName) it.copy(enabled = enabled) else it
                },
                installedBundles = current.installedBundles.map {
                    if (it.packageName == packageName) it.copy(enabled = enabled) else it
                },
            )
        }
        val args = buildJsonObject { put("name", packageName); put("enabled", enabled) }
        return calls.valueOrThrow(hostId, "pluginManager/setBundleEnabled", args).mapCatching { value ->
            val result = value as? JsonObject
                ?: error(Tr.t("Host returned no change result", "Host не вернул результат изменения"))
            BundleChangeOutcome(
                application = result.str("application"),
                restartReason = result.str("restartReason"),
                warnings = (result["warnings"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
    }

    /**
     * Включить или выключить строку Loader (сторонний плагин не из bundle). Переключатель
     * сразу показывает новое значение, затем список перечитывается — при ошибке вернётся старое.
     */
    suspend fun setPluginEnabled(hostId: String, entryId: String, enabled: Boolean): Result<Boolean> {
        _pluginRowsFlow.value = _pluginRowsFlow.value.map { row ->
            if (row.entryId == entryId) row.copy(enabled = enabled) else row
        }
        val result = client.setPluginEnabled(hostId, entryId, enabled).toResult()
        refreshPluginInventory(hostId)
        return result
    }

    /** Вызов маршрута плагина в Host; payload передаётся как есть. */
    suspend fun pluginRpc(hostId: String, method: String, payloadJson: String): Result<String> =
        when (val res = client.pluginRpc(hostId, method, payloadJson)) {
            is RemoteResult.Success -> Result.success(res.data)
            is RemoteResult.Failure -> Result.failure(IllegalStateException("${res.code}: ${res.message}"))
        }

    // Магазин плагинов (`pluginMarket`) — тот же индекс, что в веб-версии. Если сервис
    // в Host выключен, вызовы завершаются ошибкой и страница говорит, что индекс недоступен.

    /** Настроенные источники индекса. */
    suspend fun marketIndexes(hostId: String): Result<List<MarketIndexSummary>> =
        calls.valueOrThrow(hostId, "pluginMarket/listIndexes").mapCatching(::parseMarketIndexes)

    /**
     * Поиск в индексе.
     * @param queryJson `MarketQuery` в JSON (text/category/labels/sort/offset/limit…).
     * @return результат; null, пока индекс ещё не прочитан.
     */
    suspend fun marketSearch(hostId: String, url: String, queryJson: String): Result<MarketListing?> {
        val args = buildJsonObject {
            put("url", url)
            put("query", runCatching { hostJson.parseToJsonElement(queryJson) }.getOrNull() ?: JsonObject(emptyMap()))
        }
        return calls.valueOrThrow(hostId, "pluginMarket/search", args).mapCatching(::parseMarketSearch)
    }

    suspend fun installPlugin(hostId: String, packageName: String): Result<Boolean> {
        val requestId = UUID.randomUUID().toString()
        activeInstallRequestId = requestId
        installLogTail = ""
        _installProgressFlow.value =
            PluginInstallProgressInfo(phase = "installing", registry = null, index = 1, total = 1, logTail = "")
        return try {
            client.installPlugin(hostId, packageName, requestId).toResult()
                .onSuccess { refreshPluginInventory(hostId) }
        } finally {
            activeInstallRequestId = null
        }
    }

    suspend fun uninstallPlugin(hostId: String, packageName: String): Result<Boolean> =
        client.uninstallPlugin(hostId, packageName).toResult()
            .onSuccess { refreshPluginInventory(hostId) }

    /** Перечитать всё о плагинах (после установки, включения или удаления). */
    suspend fun refreshAll(hostId: String) {
        refreshPluginInventory(hostId)
        refreshBridgePluginCatalog()
        refreshPluginSettingsPages()
    }

    /** Событие с параметрами из Host: фаза установки и журнал текущей установки. */
    internal fun onEventPayload(forwarded: ForwardedEvent) {
        val requestId = activeInstallRequestId ?: return
        val payload = forwarded.args.firstOrNull() as? JsonObject ?: return
        val payloadRequestId = payload.str("requestId")
        if (payloadRequestId != null && payloadRequestId != requestId) return
        when (forwarded.event) {
            "plugin-manager/install-state" -> {
                val attempt = payload["attempt"] as? JsonObject
                _installProgressFlow.value = PluginInstallProgressInfo(
                    phase = payload.str("phase") ?: "installing",
                    registry = attempt?.str("registry"),
                    index = (attempt?.get("index") as? JsonPrimitive)?.intOrNull ?: 1,
                    total = (attempt?.get("total") as? JsonPrimitive)?.intOrNull ?: 1,
                    logTail = installLogTail,
                )
            }
            "plugin-manager/install-log" -> {
                val text = payload.str("text").orEmpty()
                if (text.isNotBlank()) {
                    installLogTail = (installLogTail + text).takeLast(400)
                    _installProgressFlow.value = _installProgressFlow.value?.copy(logTail = installLogTail)
                }
            }
        }
    }
}

/** Прогресс установки плагина (`plugin-manager/install-state` + хвост install-log). */
data class PluginInstallProgressInfo(
    val phase: String,
    val registry: String?,
    val index: Int,
    val total: Int,
    val logTail: String,
)

/** Слот моста для нативной страницы настроек. */
private const val SETTINGS_PAGE_SLOT = "settings.page"

internal fun <T> RemoteResult<T>.toResult(): Result<T> = when (this) {
    is RemoteResult.Success -> Result.success(data)
    is RemoteResult.Failure -> Result.failure(IllegalStateException(message))
}
