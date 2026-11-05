package com.dsh

import com.dsh.core.model.ModelCatalogInfo
import com.dsh.feature.settings.logic.Automation

/** A label is migratable only when it identifies one provider/model, never a catalog-order fallback. */
internal fun resolveAutomationModel(a: Automation, catalog: ModelCatalogInfo, default: Pair<String, Int>): Automation? {
    val inherited = a.model.isBlank() && a.modelId == null
    val name = (if (inherited) default.first else a.model).substringBefore(" · ").trim()
    if (a.modelId == null && name.isBlank()) return null
    val candidates = catalog.groups.flatMap { group -> group.models.filter { model ->
        (a.providerId == null || a.providerId == group.id) &&
            (if (a.modelId != null) model.id == a.modelId else model.id == name || model.name == name)
    }.map { group to it } }
    val (group, model) = candidates.singleOrNull() ?: return null
    val index = if (inherited) default.second else a.reasoning
    val effort = a.reasoningEffort?.takeIf { wanted -> model.reasoningEfforts.any { it.id == wanted } }
        ?: model.reasoningEfforts.getOrNull(index)?.id ?: model.defaultReasoningEffort
    return a.copy(model = model.name, modelId = model.id, providerId = group.id, reasoning = index, reasoningEffort = effort)
}
