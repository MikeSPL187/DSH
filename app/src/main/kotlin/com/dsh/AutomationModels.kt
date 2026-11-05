package com.dsh

import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.model.AppSettings
import com.dsh.core.model.ModelProviderGroup
import com.dsh.core.model.ModelItem
import com.dsh.feature.settings.logic.Automation

internal fun automationDefault(configured: Pair<String, Int>, settings: AppSettings, catalog: ModelCatalogInfo): Pair<String, Int> =
    if (configured.first.isNotBlank()) configured else
        settings.defaultModel.ifBlank { catalog.defaultModel } to
            com.dsh.feature.chat.ReasoningLevel.entries.indexOfFirst { it.name == settings.defaultReasoningLevel }.coerceAtLeast(0)

internal fun automationModelLabel(catalog: ModelCatalogInfo, group: ModelProviderGroup, model: ModelItem): String =
    if (catalog.groups.sumOf { it.models.count { m -> m.name == model.name } } > 1) "${model.name} · ${group.name} (${group.id})" else model.name

/** A label is migratable only when it identifies one provider/model, never a catalog-order fallback. */
internal fun resolveAutomationModel(a: Automation, catalog: ModelCatalogInfo, default: Pair<String, Int>): Automation? {
    val inherited = a.model.isBlank() && a.modelId == null
    val selected = (if (inherited) default.first else a.model).trim()
    val name = selected.substringBefore(" · ").trim()
    if (a.modelId == null && name.isBlank()) return null
    val qualified = '·' in selected && catalog.groups.any { group -> group.models.any { automationModelLabel(catalog, group, it) == selected } }
    val candidates = catalog.groups.flatMap { group -> group.models.filter { model ->
        (a.providerId == null || a.providerId == group.id) &&
            (if (a.modelId != null) model.id == a.modelId else model.id == selected ||
                automationModelLabel(catalog, group, model) == selected ||
                (!qualified && model.name == name))
    }.map { group to it } }
    val (group, model) = candidates.singleOrNull() ?: return null
    val index = if (inherited) default.second else a.reasoning
    val effort = a.reasoningEffort?.takeIf { wanted -> model.reasoningEfforts.any { it.id == wanted } }
        ?: model.reasoningEfforts.getOrNull(index)?.id ?: model.defaultReasoningEffort
    return a.copy(model = model.name, modelId = model.id, providerId = group.id, reasoning = index, reasoningEffort = effort)
}
