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
 * Имя папки проекта по умолчанию на диске. Не переименовывать: по нему находятся уже
 * созданные папки пользователей; в интерфейсе показывается R.string.workspace_default.
 */
internal const val DEFAULT_WORKSPACE_DIR_NAME = "默认工作区"
