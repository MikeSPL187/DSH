package com.dsh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Узел формы настроек плагина.
 *
 * Host отдаёт `Config`-схему плагина как таблицу узлов со ссылками по uid:
 * корень — `refs[root.uid]`, дети ссылаются на ту же таблицу. Здесь ссылки раскрываются в дерево.
 */
internal data class FormNode(
    val type: String,
    val meta: JsonObject,
    val dict: List<Pair<String, FormNode>>,
    val list: List<FormNode>,
    val inner: FormNode?,
    /** Значение узла `const`. */
    val constValue: JsonElement?,
)

/** Разбор схемы формы из проекции Host; null — не разобрать. */
internal fun parseSettingsForm(schema: JsonObject): FormNode? {
    val refs = schema["refs"] as? JsonObject
    val rootUid = (schema["uid"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    if (refs != null && rootUid != null) {
        return resolveFormNode(rootUid, refs, 0)
    }
    // Документ без uid/refs сам является узлом (поля встроены).
    return inlineFormNode(schema, 0)
}

private fun resolveFormNode(uid: Int, refs: JsonObject, depth: Int): FormNode? {
    if (depth > 16) return null
    val raw = refs[uid.toString()] as? JsonObject ?: return null
    return inlineFormNode(raw, depth, refs)
}

private fun inlineFormNode(raw: JsonObject, depth: Int, refs: JsonObject? = null): FormNode? {
    if (depth > 16) return null
    fun child(element: JsonElement?): FormNode? {
        val obj = element as? JsonObject ?: return null
        return inlineFormNode(obj, depth + 1, refs)
    }
    fun referenced(element: JsonElement?): FormNode? {
        val uid = (element as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val table = refs ?: return null
        return resolveFormNode(uid, table, depth + 1)
    }
    val type = (raw["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    val meta = raw["meta"] as? JsonObject ?: JsonObject(emptyMap())
    val dict = (raw["dict"] as? JsonObject)?.mapNotNull { (name, element) ->
        (referenced(element) ?: child(element))?.let { name to it }
    }.orEmpty()
    val list = (raw["list"] as? JsonArray)?.mapNotNull { referenced(it) ?: child(it) }.orEmpty()
    val inner = referenced(raw["inner"]) ?: child(raw["inner"])
    return FormNode(
        type = type,
        meta = meta,
        dict = dict,
        list = list,
        inner = inner,
        constValue = raw["value"],
    )
}

