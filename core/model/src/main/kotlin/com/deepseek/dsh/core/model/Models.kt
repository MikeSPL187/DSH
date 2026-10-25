package com.deepseek.dsh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class HostKind {
    LOCAL,
    REMOTE
}

/**
 * Описание Host.
 */
@Serializable
sealed interface HostDescriptor {
    val id: String
    val name: String
    val kind: HostKind

    @Serializable
    data class Local(
        override val id: String = "local-host",
        override val name: String = "Local Linux runtime",
        val port: Int? = null,
        val token: String? = null
    ) : HostDescriptor {
        override val kind: HostKind = HostKind.LOCAL
    }

    @Serializable
    data class Remote(
        override val id: String,
        override val name: String,
        val baseUrl: String,
        val deviceToken: String,
        val tlsFingerprint: String? = null
    ) : HostDescriptor {
        override val kind: HostKind = HostKind.REMOTE
    }
}

/**
 * Данные QR-кода сопряжения.
 * Формат: {v, name, url, fp, pair, exp}
 */
@Serializable
data class PairPayload(
    val v: Int = 1,
    val name: String,
    val url: String,
    val fp: String,
    val pair: String,
    val exp: Long = 0L
)

@Serializable
data class SessionInfo(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val workspacePath: String? = null,
    val status: String = "idle",
    val updatedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val model: String? = null,
    val permissionPreset: String? = null
)

@Serializable
data class ModelReasoningEffort(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class ModelItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val reasoningEfforts: List<ModelReasoningEffort> = emptyList(),
    val defaultReasoningEffort: String? = null,
)

@Serializable
data class ModelProviderGroup(
    val id: String,
    val name: String,
    val models: List<ModelItem> = emptyList()
)

@Serializable
data class ModelCatalogInfo(
    val routableProviders: List<String> = emptyList(),
    val groups: List<ModelProviderGroup> = emptyList(),
    val defaultModel: String = ""
)

/**
 * Настройки пользователя на телефоне.
 */
@Serializable
data class AppSettings(
    val defaultModel: String = "",
    val defaultReasoningLevel: String = "",
    val idleStopMinutes: Int = 5,           // остановить контейнер через 5 минут простоя (0 — не останавливать)
    val keepAliveOnTask: Boolean = true,    // работать в фоне, пока есть задачи
    val autoBootRecovery: Boolean = false,  // восстановление после перезагрузки
    val highRiskConfirm: Boolean = true,    // строгое подтверждение опасных действий
    val showCodingView: Boolean = true,     // вид работы с кодом (траектория, diff, полный выбор пресетов)
    val transcriptView: String = "standard", // пресет агента для новых сессий (пишется при первом запуске)
    val enableSoundNotice: Boolean = true,  // звук по завершении задачи
    val notificationsEnabled: Boolean = true,
    val notifyOnTurnComplete: Boolean = true,
    val notifyOnError: Boolean = true,
    val remoteHostUrl: String = "",
    val remoteHostToken: String = "",
    val workspacePath: String = "/storage/emulated/0/Documents/DSH-Workspace"
)

interface SettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
}

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

@Serializable
data class ChatMessage(
    val hostId: String,
    val sessionId: String,
    val messageId: String,
    val role: MessageRole,
    val content: String,
    val seq: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class SurfaceKind {
    PAGE,
    SIDEBAR,
    DOCK,
    OVERLAY,
    MESSAGE_ACTION,
    THEME,
    UNKNOWN
}

@Serializable
data class ComposerStats(
    val outputSpeedTokensPerSec: Double = 0.0,
    val contextPercent: Int = 0,
    val cacheHitPercent: Int = 0
)

@Serializable
data class BuiltInPluginInfo(
    val title: String,
    val packageName: String,
    val description: String,
    val enabled: Boolean = true,
    val phase: String = "active",
    val entryId: String? = null,
    val presetId: String? = null,
    val condition: String? = null
)

@Serializable
data class AgentPresetGroupInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
    val broken: String? = null,
    val rows: List<BuiltInPluginInfo> = emptyList()
)

@Serializable
data class PluginInventorySnapshotInfo(
    val managementAvailable: Boolean = false,
    val entries: List<BuiltInPluginInfo> = emptyList(),
    val agentPresets: List<AgentPresetGroupInfo> = emptyList()
)

@Serializable
data class AutomationTaskItem(
    val id: String,
    val title: String,
    val summary: String,
    val inactive: Boolean = false,
    val prompt: String = ""
)

@Serializable
data class TeamTaskItem(
    val id: String,
    val subject: String,
    val description: String,
    val status: String = "pending", // pending, in_progress, completed
    val ownerName: String? = null
)

/**
 * Участник команды агентов.
 *
 * Из проекции сессии `agentTeam` (`TeamProjection.members`). `id` — id сессии участника,
 * по нему открывается его сессия.
 */
@Serializable
data class AgentTeamMember(
    val id: String,
    val name: String,
    val role: String = "teammate", // lead / teammate
    val phase: String = "active", // provisioning / active / failed
    val error: String? = null,
)

/** Пункт общей доски задач (`TeamTaskView`). */
@Serializable
data class AgentTeamTask(
    val id: String,
    val subject: String,
    val description: String = "",
    val status: String = "pending", // pending / in_progress / completed
    val ownerName: String? = null,
    val blockedBy: List<String> = emptyList(),
    val writeScopes: List<String> = emptyList(),
    val ready: Boolean = true,
    val writeScopeWarnings: List<String> = emptyList(),
)

/** Часть проекции `agentTeam`, нужная нативной панели команды. */
@Serializable
data class AgentTeamSummary(
    val members: List<AgentTeamMember> = emptyList(),
    val tasks: List<AgentTeamTask> = emptyList(),
    val failure: String? = null,
)

/** Пункт плана из проекции `todos`. */
@Serializable
data class TodoEntryInfo(
    val content: String,
    val status: String, // pending | in_progress | completed
)

/** Текущая цель из проекции `goal`. */
@Serializable
data class GoalInfo(
    val objective: String,
    val phase: String,
    val blockedReason: String? = null,
    val roundsStarted: Int = 0,
    val maxGoalRounds: Int = 0,
)

/** Слэш-команда из `commands/list`. */
@Serializable
data class HostCommandInfo(
    val name: String,
    val description: String,
    val inputHint: String? = null,
    val acceptsAttachments: Boolean = false,
)

/** Участник запуска workflow (дочерняя сессия). */
@Serializable
data class WorkflowMemberInfo(
    val seq: Int = 0,
    val label: String = "",
    val childId: String = "",
    val status: String = "running",
)

/** Фаза запуска workflow (phase может отсутствовать). */
@Serializable
data class WorkflowPhaseInfo(
    val phase: String? = null,
    val members: List<WorkflowMemberInfo> = emptyList(),
)

/** Запуск workflow, собранный из событий tool-workflow. */
@Serializable
data class WorkflowRunInfo(
    val runId: String,
    val name: String = "",
    val status: String = "running",
    val phases: List<WorkflowPhaseInfo> = emptyList(),
)

/** Расписание (напоминание) из `schedule/catalog`. */
@Serializable
data class ScheduleInfo(
    val id: String,
    val title: String = "",
    val prompt: String = "",
    val scheduledAt: String = "",
    val sessionId: String = "",
    val status: String = "",
    val kind: String = "",
    /** Разовая задержка `after`, секунды. */
    val afterSeconds: Long = 0,
    /** Интервал `every`, секунды. */
    val everySeconds: Long = 0,
    /** Местное время `daily` / `weekly`, HH:mm:ss.SSS. */
    val time: String = "",
    /** Дни недели `weekly` (ISO: 1 — понедельник). */
    val weekdays: List<Int> = emptyList(),
    /** Выражение `cron` из пяти полей. */
    val expression: String = "",
)

/** Уведомление о бонусе из `account/getUnnotifiedBonuses`. */
@Serializable
data class AccountBonusInfo(
    val orderId: String,
    val title: String? = null,
    val message: String = "",
    val expiresAt: String = "",
)

/** Оценка сообщения из `messageFeedback`. */
@Serializable
data class MessageFeedbackInfo(
    val messageId: String,
    val rating: String,
    val note: String? = null,
    val version: String = "",
)

/** Веб-терминал из `terminal/list`. */
@Serializable
data class TerminalInfo(
    val id: String,
    val title: String,
    val state: String = "",
    val shellName: String = "",
)

/** Навык из `skills/list`, который может вызвать человек. */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val modelInvocable: Boolean = false,
)

/** Результат `commands/execute`. */
@Serializable
data class HostCommandResult(
    val kind: String,
    val text: String? = null,
)

@Serializable
data class PluginConfigItem(
    val id: String,
    val title: String,
    val description: String,
    val iconKey: String = "default"
)

@Serializable
data class PluginBundleItem(
    val packageName: String,
    val title: String,
    val description: String,
    val isExperimental: Boolean = false,
    val enabled: Boolean = false,
    val iconKey: String = "default",
    val version: String? = null,
    val official: Boolean = false,
    /** Лежит ли пакет на устройстве (в отличие от «из установки»). */
    val installed: Boolean = false,
    /** Доступность по Host: `installation` / `installed` / `missing`. */
    val availability: String = "",
    val removable: Boolean = true,
    val error: String? = null,
    val rows: List<PluginComponentInfo> = emptyList(),
    val iconData: String? = null,
    // Правила страницы плагинов — от моста (старый мост не присылает: false/0).
    val profileRoot: Boolean = false,
    val pinned: Boolean = false,
    val order: Int = 0,
    val hidden: Boolean = false,
    val market: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class PluginComponentInfo(
    val rowId: String,
    val moduleName: String,
    val title: String,
    val description: String,
    val enabled: Boolean = true,
    val phase: String = "active",
    val entryId: String? = null,
)

@Serializable
data class PluginRowInfo(
    val entryId: String? = null,
    val moduleName: String,
    val title: String,
    val description: String,
    val enabled: Boolean = true,
    val phase: String = "active",
    val iconData: String? = null,
)

@Serializable
data class PluginManagerSnapshot(
    val basicConfigs: List<PluginConfigItem> = emptyList(),
    val experimentalBundles: List<PluginBundleItem> = emptyList(),
    val installedBundles: List<PluginBundleItem> = emptyList()
)

@Serializable
data class SurfaceItem(
    val pluginId: String,
    val slot: String,
    val order: Int = 0,
    val title: String? = null,
    val kind: SurfaceKind = SurfaceKind.PAGE,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class SurfaceCatalog(
    val clientId: String,
    val dshVersion: String,
    val surfaces: List<SurfaceItem> = emptyList()
)






/**
 * Точка входа плагина, соответствующая нативному интерфейсу.
 *
 * Пока только страница настроек: если плагин объявил схему `Config`, Host создаёт пространство
 * настроек, и в нативных настройках должен быть пункт. Страницы содержимого ещё нет — пункт только ведёт туда.
 */
@Serializable
data class PluginSettingsPage(
    val id: String,
    val title: String,
    val description: String? = null,
    val module: String? = null,
)

/**
 * Документ настроек плагина: форма из Host, текущие значения и версия.
 *
 * Форма — из схемы `Config` самого плагина; клиент рисует поля и пишет обратно, но поля не переопределяет.
 */
@Serializable
data class PluginSettingsDocument(
    val ns: String,
    val revision: Int = 0,
    val schema: JsonObject = JsonObject(emptyMap()),
    val value: JsonObject = JsonObject(emptyMap()),
    /** Заданные места секретов (Host присылает только path и set, значения вырезаны). */
    val secretSlots: List<PluginSecretSlot> = emptyList(),
)

/** Место поля-секрета (путь и задан ли). */
@Serializable
data class PluginSecretSlot(
    val path: List<String> = emptyList(),
    val set: Boolean = false,
)

/**
 * Точка входа плагина с уже определённым нативным местом.
 *
 * Место переводит мост из имени слота, клиент рисует каждое место на своём экране;
 * ещё не реализованные места тоже остаются в списке, а не теряются.
 */
@Serializable
data class PluginSlotEntry(
    val placement: String,
    val order: Int = 0,
    val slot: String,
    val id: String? = null,
    /** Идентификатор слота с ключом (например, `render_ui` в `tool.call.toolview` по имени инструмента). */
    val key: String? = null,
    val title: String? = null,
    val module: String? = null,
    /** Компонент `<package>#<id|key|slot>` от моста — важнее собранного клиентом. */
    val componentId: String? = null,
) {
    /** Стабильный идентификатор для сопоставления по инструменту или месту: сначала id, у слотов с ключом — key. */
    val identity: String? get() = id ?: key

    /** Компонент: от моста, иначе `<module>#<identity|slot>`. */
    fun resolvedComponentId(): String? =
        componentId ?: module?.let { "$it#${identity ?: slot}" }
}

/** Сообщение в очереди на следующий ход (проекция inbox). */
data class QueuedMessageInfo(
    val id: String,
    val text: String,
)
