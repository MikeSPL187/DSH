package com.deepseek.dsh

/** Страницы приложения; чат — главная, остальные открываются поверх и закрываются «Назад». */
enum class ActivePage {
    CHAT,
    FILES,
    PLUGINS,
    AUTOMATION,
    TERMINAL,
    SETTINGS,
}

internal const val PLUGIN_SELECTION_PREFS = "dsh_plugin_selection"
internal const val PLUGIN_SELECTION_KEY = "enabled_bundles"

/**
 * Имя папки проекта по умолчанию на диске; в интерфейсе показывается R.string.workspace_default.
 * Папку со старым именем [LEGACY_DEFAULT_WORKSPACE_DIR_NAME] переносит [migrateDefaultWorkspace].
 */
internal const val DEFAULT_WORKSPACE_DIR_NAME = "Default"
