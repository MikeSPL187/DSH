package com.dsh.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Иконки DSH — те же контурные символы, что в прототипе (`docs/design/dsh-prototype.html`):
 * сетка 24, линия 1,75 (у мелких 15 dp — 2), скруглённые концы и углы.
 * Сгенерировано из `<symbol>` прототипа скриптом `docs/design/tools/glyphs.py`, руками не править.
 */
object DshGlyphs {
    private class P(val d: String, val fill: Boolean, val stroke: Boolean, val width: Float?)

    private val cache = HashMap<String, ImageVector>()

    /** Символ [name] (как `i-name` в прототипе) с толщиной линии [stroke]. */
    fun get(name: String, stroke: Float = 1.75f): ImageVector = synchronized(cache) {
        cache.getOrPut("$name@$stroke") {
            val b = ImageVector.Builder(name = "dsh-$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            for (p in parts.getValue(name)) {
                b.addPath(
                    pathData = addPathNodes(p.d),
                    fill = if (p.fill) SolidColor(Color.Black) else null,
                    stroke = if (p.stroke) SolidColor(Color.Black) else null,
                    strokeLineWidth = p.width ?: stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            b.build()
        }
    }

    /** Мелкая иконка (15 dp в прототипе, класс `.xs`): линия толще. */
    fun xs(name: String): ImageVector = get(name, 2f)

    val Menu: ImageVector get() = get("menu")
    val New: ImageVector get() = get("new")
    val Plus: ImageVector get() = get("plus")
    val Mic: ImageVector get() = get("mic")
    val Up: ImageVector get() = get("up")
    val Stop: ImageVector get() = get("stop")
    val Folder: ImageVector get() = get("folder")
    val Term: ImageVector get() = get("term")
    val Tasks: ImageVector get() = get("tasks")
    val Chev: ImageVector get() = get("chev")
    val Right: ImageVector get() = get("right")
    val Search: ImageVector get() = get("search")
    val File: ImageVector get() = get("file")
    val Check: ImageVector get() = get("check")
    val Pause: ImageVector get() = get("pause")
    val X: ImageVector get() = get("x")
    val Alert: ImageVector get() = get("alert")
    val Copy: ImageVector get() = get("copy")
    val Redo: ImageVector get() = get("redo")
    val Share: ImageVector get() = get("share")
    val Bug: ImageVector get() = get("bug")
    val Code: ImageVector get() = get("code")
    val Book: ImageVector get() = get("book")
    val Flow: ImageVector get() = get("flow")
    val Camera: ImageVector get() = get("camera")
    val Image: ImageVector get() = get("image")
    val Slash: ImageVector get() = get("slash")
    val At: ImageVector get() = get("at")
    val Spark: ImageVector get() = get("spark")
    val Shield: ImageVector get() = get("shield")
    val Trash: ImageVector get() = get("trash")
    val Puzzle: ImageVector get() = get("puzzle")
    val Clock: ImageVector get() = get("clock")
    val Gear: ImageVector get() = get("gear")
    val Play: ImageVector get() = get("play")
    val Sliders: ImageVector get() = get("sliders")
    val Cpu: ImageVector get() = get("cpu")
    val Updown: ImageVector get() = get("updown")
    val Left: ImageVector get() = get("left")
    val More: ImageVector get() = get("more")
    val Arrow: ImageVector get() = get("arrow")
    val Text: ImageVector get() = get("text")
    val Mem: ImageVector get() = get("mem")
    val Pin: ImageVector get() = get("pin")
    val Link: ImageVector get() = get("link")
    val Globe: ImageVector get() = get("globe")
    val Lock: ImageVector get() = get("lock")
    val Key: ImageVector get() = get("key")
    val Bell: ImageVector get() = get("bell")
    val Speaker: ImageVector get() = get("speaker")
    val Down: ImageVector get() = get("down")
    val Box: ImageVector get() = get("box")
    val Git: ImageVector get() = get("git")
    val Msg: ImageVector get() = get("msg")
    val Undo: ImageVector get() = get("undo")
    val Pencil: ImageVector get() = get("pencil")
    val Bolt: ImageVector get() = get("bolt")
    val Sun: ImageVector get() = get("sun")
    val Moon: ImageVector get() = get("moon")
    val Phone: ImageVector get() = get("phone")
    val Server: ImageVector get() = get("server")
    val Hdd2: ImageVector get() = get("hdd2")
    val Info: ImageVector get() = get("info")
    val Eye: ImageVector get() = get("eye")
    val Dsh: ImageVector get() = get("dsh")

    private val parts: Map<String, List<P>> = mapOf(
        "menu" to listOf(P("M4 7h16M4 12h11M4 17h16", false, true, null)),
        "new" to listOf(P("M12 20h8M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4Z", false, true, null)),
        "plus" to listOf(P("M12 5v14M5 12h14", false, true, null)),
        "mic" to listOf(P("M12,3h0a3,3 0 0 1 3,3v5a3,3 0 0 1 -3,3h-0a3,3 0 0 1 -3,-3v-5a3,3 0 0 1 3,-3Z", false, true, null), P("M5 11a7 7 0 0 0 14 0M12 18v3", false, true, null)),
        "up" to listOf(P("M12 19V5M6 11l6-6 6 6", false, true, null)),
        "stop" to listOf(P("M9.5,7h5a2.5,2.5 0 0 1 2.5,2.5v5a2.5,2.5 0 0 1 -2.5,2.5h-5a2.5,2.5 0 0 1 -2.5,-2.5v-5a2.5,2.5 0 0 1 2.5,-2.5Z", true, false, null)),
        "folder" to listOf(P("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z", false, true, null)),
        "term" to listOf(P("M6,4h12a3,3 0 0 1 3,3v10a3,3 0 0 1 -3,3h-12a3,3 0 0 1 -3,-3v-10a3,3 0 0 1 3,-3Z", false, true, null), P("m7 9 3 3-3 3M13 15h4", false, true, null)),
        "tasks" to listOf(P("M9 6h11M9 12h11M9 18h11", false, true, null), P("m3.5 6 1.2 1.2L6.5 5M3.5 12l1.2 1.2 1.8-2.2", false, true, null), P("M3.7,18a1.3,1.3 0 1,0 2.6,0a1.3,1.3 0 1,0 -2.6,0Z", false, true, null)),
        "chev" to listOf(P("m6 9 6 6 6-6", false, true, null)),
        "right" to listOf(P("m9 6 6 6-6 6", false, true, null)),
        "search" to listOf(P("M4,11a7,7 0 1,0 14,0a7,7 0 1,0 -14,0Z", false, true, null), P("m20 20-3.5-3.5", false, true, null)),
        "file" to listOf(P("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z", false, true, null), P("M14 3v5h5", false, true, null)),
        "check" to listOf(P("m5 12 4.5 4.5L19 7", false, true, null)),
        "pause" to listOf(P("M9 6v12M15 6v12", false, true, null)),
        "x" to listOf(P("M6 6l12 12M18 6 6 18", false, true, null)),
        "alert" to listOf(P("M12 9v4M12 17h.01", false, true, null), P("M10.3 3.9 2.4 17.6A2 2 0 0 0 4.1 20.6h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z", false, true, null)),
        "copy" to listOf(P("M11.5,9h6a2.5,2.5 0 0 1 2.5,2.5v6a2.5,2.5 0 0 1 -2.5,2.5h-6a2.5,2.5 0 0 1 -2.5,-2.5v-6a2.5,2.5 0 0 1 2.5,-2.5Z", false, true, null), P("M5 15a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2", false, true, null)),
        "redo" to listOf(P("M20 12a8 8 0 1 1-2.3-5.7L20 8", false, true, null), P("M20 3v5h-5", false, true, null)),
        "share" to listOf(P("M12 15V3M7 8l5-5 5 5", false, true, null), P("M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5", false, true, null)),
        "bug" to listOf(P("M8 8a4 4 0 0 1 8 0v1H8Z", false, true, null), P("M12,9h0a5,5 0 0 1 5,5v0a5,5 0 0 1 -5,5h-0a5,5 0 0 1 -5,-5v-0a5,5 0 0 1 5,-5Z", false, true, null), P("M3 13h4M17 13h4M4 7l3 2M20 7l-3 2M4 19l3-2M20 19l-3-2", false, true, null)),
        "code" to listOf(P("m8 7-5 5 5 5M16 7l5 5-5 5", false, true, null)),
        "book" to listOf(P("M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2Z", false, true, null), P("M4 20a2 2 0 0 0 2 1h13", false, true, null)),
        "flow" to listOf(P("M3.5,6a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0Z", false, true, null), P("M15.5,18a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0Z", false, true, null), P("M8.5 6H14a4 4 0 0 1 4 4v5.5", false, true, null)),
        "camera" to listOf(P("M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z", false, true, null), P("M8.5,13.5a3.5,3.5 0 1,0 7,0a3.5,3.5 0 1,0 -7,0Z", false, true, null)),
        "image" to listOf(P("M6,4h12a3,3 0 0 1 3,3v10a3,3 0 0 1 -3,3h-12a3,3 0 0 1 -3,-3v-10a3,3 0 0 1 3,-3Z", false, true, null), P("M7,10a2,2 0 1,0 4,0a2,2 0 1,0 -4,0Z", false, true, null), P("m21 16-5-5-9 9", false, true, null)),
        "slash" to listOf(P("M15 4 9 20", false, true, null)),
        "at" to listOf(P("M8,12a4,4 0 1,0 8,0a4,4 0 1,0 -8,0Z", false, true, null), P("M16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.5 7.1", false, true, null)),
        "spark" to listOf(P("M12 3c.5 4 2.5 6.5 7 7-4.5.5-6.5 3-7 7-.5-4-2.5-6.5-7-7 4.5-.5 6.5-3 7-7Z", false, true, null)),
        "shield" to listOf(P("M12 3 5 6v6c0 4.2 3 7.7 7 9 4-1.3 7-4.8 7-9V6Z", false, true, null)),
        "trash" to listOf(P("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3", false, true, null)),
        "puzzle" to listOf(P("M10 4a2 2 0 0 1 4 0v2h4v4h-2a2 2 0 0 0 0 4h2v4h-4v-2a2 2 0 0 0-4 0v2H6v-4h2a2 2 0 0 0 0-4H6V6h4Z", false, true, null)),
        "clock" to listOf(P("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0Z", false, true, null), P("M12 7v5l3 2", false, true, null)),
        "gear" to listOf(P("M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0Z", false, true, null), P("M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z", false, true, null)),
        "play" to listOf(P("M7 5v14l11-7Z", false, true, null)),
        "sliders" to listOf(P("M4 7h10M18 7h2M4 17h4M12 17h8", false, true, null), P("M14,7a2,2 0 1,0 4,0a2,2 0 1,0 -4,0Z", false, true, null), P("M8,17a2,2 0 1,0 4,0a2,2 0 1,0 -4,0Z", false, true, null)),
        "cpu" to listOf(P("M8.5,6h7a2.5,2.5 0 0 1 2.5,2.5v7a2.5,2.5 0 0 1 -2.5,2.5h-7a2.5,2.5 0 0 1 -2.5,-2.5v-7a2.5,2.5 0 0 1 2.5,-2.5Z", false, true, null), P("M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4", false, true, null)),
        "updown" to listOf(P("m8 9 4-4 4 4M8 15l4 4 4-4", false, true, null)),
        "left" to listOf(P("m15 5-7 7 7 7", false, true, null)),
        "more" to listOf(P("M5 12h.01M12 12h.01M19 12h.01", false, true, 3f)),
        "arrow" to listOf(P("M5 12h14M13 6l6 6-6 6", false, true, null)),
        "text" to listOf(P("M4 18 8.5 6h1L14 18M5.5 14h6M15 18l2.7-7h.6L21 18M15.8 16h4.4", false, true, null)),
        "mem" to listOf(P("M7 3h10a1 1 0 0 1 1 1v17l-6-4-6 4V4a1 1 0 0 1 1-1Z", false, true, null), P("m12 7 1 2 2 .3-1.5 1.4.4 2.1-1.9-1-1.9 1 .4-2.1L9 9.3l2-.3Z", false, true, null)),
        "pin" to listOf(P("M9 4h6l-1 6 3 3H7l3-3ZM12 13v7", false, true, null)),
        "link" to listOf(P("M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1", false, true, null), P("M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1", false, true, null)),
        "globe" to listOf(P("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0Z", false, true, null), P("M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3Z", false, true, null)),
        "lock" to listOf(P("M7.5,11h9a2.5,2.5 0 0 1 2.5,2.5v5a2.5,2.5 0 0 1 -2.5,2.5h-9a2.5,2.5 0 0 1 -2.5,-2.5v-5a2.5,2.5 0 0 1 2.5,-2.5Z", false, true, null), P("M8 11V8a4 4 0 0 1 8 0v3", false, true, null)),
        "key" to listOf(P("M4,15a4,4 0 1,0 8,0a4,4 0 1,0 -8,0Z", false, true, null), P("m11 12 9-9M16 7l3 3M14 9l2 2", false, true, null)),
        "bell" to listOf(P("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15Z", false, true, null), P("M10 20a2 2 0 0 0 4 0", false, true, null)),
        "speaker" to listOf(P("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", false, true, null), P("M15.5 9a4.2 4.2 0 0 1 0 6M18 6.5a7.8 7.8 0 0 1 0 11", false, true, null)),
        "down" to listOf(P("M12 4v12M6 11l6 6 6-6M5 20h14", false, true, null)),
        "box" to listOf(P("M3 7.5 12 3l9 4.5v9L12 21l-9-4.5Z", false, true, null), P("M3 7.5 12 12l9-4.5M12 12v9", false, true, null)),
        "git" to listOf(P("M3.5,6a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0Z", false, true, null), P("M3.5,18a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0Z", false, true, null), P("M15.5,9a2.5,2.5 0 1,0 5,0a2.5,2.5 0 1,0 -5,0Z", false, true, null), P("M6 8.5v7M18 11.5c0 3-3 3.5-6 4l-3.5 1", false, true, null)),
        "msg" to listOf(P("M4 5h16v11H9l-5 4Z", false, true, null)),
        "undo" to listOf(P("M9 14 4 9l5-5", false, true, null), P("M4 9h10a6 6 0 0 1 0 12h-3", false, true, null)),
        "pencil" to listOf(P("M4 20h4L19 9l-4-4L4 16Z", false, true, null)),
        "bolt" to listOf(P("M13 2 4 14h7l-1 8 9-12h-7Z", false, true, null)),
        "sun" to listOf(P("M8,12a4,4 0 1,0 8,0a4,4 0 1,0 -8,0Z", false, true, null), P("M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4", false, true, null)),
        "moon" to listOf(P("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z", false, true, null)),
        "phone" to listOf(P("M9,2.5h6a3,3 0 0 1 3,3v13a3,3 0 0 1 -3,3h-6a3,3 0 0 1 -3,-3v-13a3,3 0 0 1 3,-3Z", false, true, null), P("M11 18.5h2", false, true, null)),
        "server" to listOf(P("M5,4h14a2,2 0 0 1 2,2v3a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-3a2,2 0 0 1 2,-2Z", false, true, null), P("M5,13h14a2,2 0 0 1 2,2v3a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-3a2,2 0 0 1 2,-2Z", false, true, null), P("M7 7.5h.01M7 16.5h.01", false, true, null)),
        "hdd2" to listOf(P("M4,6a8,3 0 1,0 16,0a8,3 0 1,0 -16,0Z", false, true, null), P("M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3", false, true, null)),
        "info" to listOf(P("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0Z", false, true, null), P("M12 11v5M12 8h.01", false, true, null)),
        "eye" to listOf(P("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z", false, true, null), P("M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0Z", false, true, null)),
        "dsh" to listOf(P("m7 8 4 4-4 4", false, true, 2.2f), P("M13 16h4.5", false, true, 2.2f)),
    )
}
