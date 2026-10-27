package com.dsh.core.data

/**
 * То, что интерфейс показывает из итога включения или выключения bundle.
 *
 * Это `ChangeResult` из `pluginManager` Host; `application` бывает
 * `applied / restart-required / overridden / failed / cancelled`;
 * при `restart-required` интерфейс просит перезапустить DSH, `warnings` —
 * замечания этой операции (веб-версия тоже их показывает).
 */
data class BundleChangeOutcome(
    /** Итог: applied / restart-required / overridden / failed / cancelled. */
    val application: String?,
    /** Почему нужен перезапуск: no-hmr / package-moved / host-module / unmanaged-plugin / module-comparison. */
    val restartReason: String?,
    /** Замечания Host. */
    val warnings: List<String>,
) {
    /** Нужен ли перезапуск DSH. Некоторые версии Host присылают только restartReason. */
    val restartRequired: Boolean
        get() = application == "restart-required" || !restartReason.isNullOrBlank()
}
