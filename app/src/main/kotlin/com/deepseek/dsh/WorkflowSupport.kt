package com.deepseek.dsh

import com.deepseek.dsh.core.data.DshRepository

/**
 * Включить инструменты workflow в профиле телефона.
 *
 * В образе телефона по умолчанию только `ptc-runtime`; `workflow-ptc` (движок) и `tool-workflow`
 * (инструмент для модели) — необязательные строки, выключенные, поэтому в DSH инструментов workflow нет.
 * Здесь они включаются через pluginManager (как переключатель на странице плагинов):
 * два повторяемых запроса, если уже включено — для Host ничего не меняется.
 */
suspend fun ensureOfficialWorkflowEnabled(repository: DshRepository, hostId: String) {
    for (entryId in listOf("include:workflow-ptc", "include:tool-workflow")) {
        repository.plugins.setPluginEnabled(hostId, entryId, true)
    }
}
