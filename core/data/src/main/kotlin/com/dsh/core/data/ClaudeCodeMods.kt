package com.dsh.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Модель полосы модов Claude Code.
 *
 * `client-ui-claude-code-mods` рисует присланное Host дерево узлов (только
 * `Box`/`Text`/`Button`) полосой: у `Text` — цвет, жирный, приглушённый, курсив, подчёркивание;
 * у `Box` — направление, рамка, отступы, промежутки; у `Button` — подпись, клавиша, выключенность и actionId.
 */

/** Узел полосы. */
sealed interface BandNode {
    /** Текст. */
    data class Text(
        val text: String,
        val color: String? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    ) : BandNode

    /** Контейнер раскладки. */
    data class Box(
        val row: Boolean = false,
        val border: Boolean = false,
        val padding: Int = 0,
        val gap: Int = 0,
        val children: List<BandNode> = emptyList(),
    ) : BandNode

    /** Кнопка. */
    data class Button(
        val label: String,
        val hotkey: String? = null,
        val disabled: Boolean = false,
        val actionId: String? = null,
    ) : BandNode
}

/** Снимок одной перерисовки (по generation Host отбрасывает нажатия устаревших кнопок). */
data class ClaudeCodeBandSnapshot(
    val generation: Long,
    val root: BandNode?,
)

/** Разбор `SurfaceSnapshot`: `{ generation, tree }`. */
internal fun parseBandSnapshot(value: JsonElement?): ClaudeCodeBandSnapshot? {
    val obj = value as? JsonObject ?: return null
    val generation = (obj["generation"] as? JsonPrimitive)?.longOrNull ?: 0L
    val tree = obj["tree"]
    return ClaudeCodeBandSnapshot(
        generation = generation,
        root = if (tree == null || tree is kotlinx.serialization.json.JsonNull) null else parseBandNode(tree),
    )
}

/** Глубже полоса не разбирается: дерево приходит от плагина Host, и вложенность в тысячи уровней
 *  переполняла стек при разборе и отрисовке (падение приложения). Настоящая полоса — единицы уровней. */
internal const val MAX_BAND_DEPTH = 32

/** Разбор узла; незнакомый узел показывается как текст, чтобы содержимое не пропало. */
internal fun parseBandNode(element: JsonElement?, depth: Int = 0): BandNode? {
    if (depth > MAX_BAND_DEPTH) return null
    when (element) {
        null -> return null
        is JsonPrimitive -> return element.contentOrNull?.let { BandNode.Text(text = it) }
        is JsonArray -> return BandNode.Box(children = element.mapNotNull { parseBandNode(it, depth + 1) })
        is JsonObject -> {
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
            val props = element["props"] as? JsonObject
            fun text(key: String): String? =
                (props?.get(key) as? JsonPrimitive)?.contentOrNull
            fun bool(key: String): Boolean =
                (props?.get(key) as? JsonPrimitive)?.booleanOrNull == true
            fun number(key: String): Int =
                (props?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            return when (type) {
                "Text" -> BandNode.Text(
                    text = text("text") ?: text("children") ?: "",
                    color = text("color"),
                    bold = bool("bold"),
                    dim = bool("dimColor"),
                    italic = bool("italic"),
                    underline = bool("underline"),
                )
                "Button" -> BandNode.Button(
                    label = text("label") ?: "",
                    hotkey = text("hotkey"),
                    disabled = bool("disabled") || (element["actionId"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank(),
                    actionId = (element["actionId"] as? JsonPrimitive)?.contentOrNull,
                )
                else -> BandNode.Box(
                    row = text("flexDirection") == "row",
                    border = bool("borderStyle") || bool("border"),
                    padding = maxOf(number("padding"), number("paddingX"), number("paddingY")),
                    gap = number("gap"),
                    children = (element["children"] as? JsonArray).orEmpty().mapNotNull { parseBandNode(it, depth + 1) },
                )
            }
        }
        else -> return null
    }
}
