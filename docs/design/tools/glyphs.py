# Генерирует core/designsystem/.../DshGlyphs.kt из <symbol> прототипа.
# Запуск из корня репозитория: python3 docs/design/tools/glyphs.py
import re
src = open('docs/design/dsh-prototype.html').read()
syms = re.findall(r'<symbol id="i-([\w-]+)" viewBox="0 0 24 24"[^>]*>(.*?)</symbol>', src, re.S)

def attrs(tag): return dict(re.findall(r'([\w-]+)="([^"]*)"', tag))

def to_d(kind, a):
    if kind == 'path': return a['d']
    if kind in ('circle', 'ellipse'):
        cx, cy = float(a['cx']), float(a['cy'])
        rx = float(a.get('r', a.get('rx', 0))); ry = float(a.get('r', a.get('ry', rx)))
        return f"M{cx-rx:g},{cy:g}a{rx:g},{ry:g} 0 1,0 {2*rx:g},0a{rx:g},{ry:g} 0 1,0 {-2*rx:g},0Z"
    x, y, w, h = float(a.get('x', 0)), float(a.get('y', 0)), float(a['width']), float(a['height'])
    rx = float(a.get('rx', a.get('ry', 0))); ry = float(a.get('ry', rx))
    if rx == 0: return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}Z"
    return (f"M{x+rx:g},{y:g}h{w-2*rx:g}a{rx:g},{ry:g} 0 0 1 {rx:g},{ry:g}v{h-2*ry:g}"
            f"a{rx:g},{ry:g} 0 0 1 {-rx:g},{ry:g}h{-(w-2*rx):g}a{rx:g},{ry:g} 0 0 1 {-rx:g},{-ry:g}"
            f"v{-(h-2*ry):g}a{rx:g},{ry:g} 0 0 1 {rx:g},{-ry:g}Z")

entries, props = [], []
for name, body in syms:
    items = []
    for kind, tag in re.findall(r'<(\w+)([^>]*?)/?>', body):
        a = attrs(tag)
        filled = a.get('fill', 'none') != 'none'
        stroked = a.get('stroke', '') != 'none'
        sw = a.get('stroke-width')
        items.append(f'P("{to_d(kind, a)}", {str(filled).lower()}, {str(stroked).lower()}, {sw + "f" if sw else "null"})')
    entries.append(f'        "{name}" to listOf({", ".join(items)}),')
    kname = ''.join(w.capitalize() for w in name.split('-'))
    props.append(f'    val {kname}: ImageVector get() = get("{name}")')

kt = '''package com.deepseek.dsh.core.designsystem

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

''' + '\n'.join(props) + '''

    private val parts: Map<String, List<P>> = mapOf(
''' + '\n'.join(entries) + '''
    )
}
'''
open('core/designsystem/src/main/kotlin/com/deepseek/dsh/core/designsystem/DshGlyphs.kt', 'w').write(kt)
print(len(syms), 'glyphs')
