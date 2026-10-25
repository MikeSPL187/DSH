package com.deepseek.dsh.runtime.host

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimePhase {
    UNINSTALLED,
    INSTALLING,
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

@Serializable
data class RuntimeStatus(
    val phase: RuntimePhase,
    val port: Int? = null,
    val token: String? = null,
    val pid: Long? = null,
    val lastError: String? = null,
    /** Последний вывод (без секретов, около 120 последних строк); при ошибке виден в настройках. */
    val logTail: List<String> = emptyList(),
    /** Код выхода процесса; если сбой не из-за выхода процесса — null. */
    val exitCode: Int? = null,
    /** Время последнего обновления состояния (epoch millis). */
    val updatedAtMillis: Long = 0L,
)

interface HostRuntimeManager {
    suspend fun getStatus(): RuntimeStatus
    suspend fun isRootfsInstalled(): Boolean
    suspend fun installedRootfsVersion(): String?
    suspend fun availableRootfsVersion(): String?
    suspend fun isRootfsUpdateAvailable(): Boolean

    /**
     * Нужна ли этому обновлению полная переустановка.
     *
     * `MANDATORY` останавливает запуск; `OPTIONAL` — только подсказка в настройках: старый rootfs
     * полностью рабочий, плагин обновляется отдельно.
     */
    suspend fun rootfsUpdateKind(): RootfsUpdateKind

    /** Установить или обновить rootfs (загрузка целиком). */
    suspend fun installRootfs(onProgress: (Float, String) -> Unit): Result<Unit>

    /**
     * Отдельно поставить или обновить bridge-плагин в rootfs, без новой загрузки rootfs.
     *
     * Скачивается архив исходников нужного тега из репозитория плагина и после проверки раскладывается в `node_modules` профиля;
     * без git/pnpm Host (из proot GitHub не разрешается, а pnpm переписал бы список
     * профиля). Новый код загрузится после перезапуска Host.
     * @param version целевая версия — последний выпуск репозитория плагина.
     * @param onProgress ход и подписи шагов.
     * @return версия плагина, прочитанная после успеха.
     */
    suspend fun updateBridgePlugin(
        version: String,
        onProgress: (Float, String) -> Unit,
    ): Result<String>

    /** Действующая версия bridge-плагина (сначала работающая, потом с диска); не прочитать — null. */
    suspend fun installedBridgeVersion(): String?

    /**
     * Сводная оценка обновлений: последний выпуск репозитория плагина, версия плагина на месте, сравнение версий образа.
     *
     * Плагин отстал → [UpdateNeed.PLUGIN_REQUIRED] (обязательно, через «Обновить плагин»);
     * изменилась только часть образа → [UpdateNeed.ROOTFS_OPTIONAL] (необязательно, загрузка целиком).
     * @return вывод; ошибка любого запроса — «нельзя определить», без ложных сообщений.
     */
    suspend fun assessUpdates(): UpdateAssessment

    suspend fun start(workspacePath: String): Result<RuntimeStatus>

    /**
     * Автоматический запуск (прогрев при открытии приложения), а не по действию
     * пользователя. Реализация может не повторять недавнюю неудачную попытку:
     * повтор — это кнопка «Повторить», а не тихий перезапуск proot раз в минуту.
     */
    suspend fun warmUp(workspacePath: String): Result<RuntimeStatus> = start(workspacePath)

    suspend fun stop(): Result<Unit>
}

