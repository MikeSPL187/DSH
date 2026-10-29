package com.dsh.feature.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.dsh.core.designsystem.LocalConversationFontSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Компактный Markdown (мобильное подмножество).
 *
 * Заголовки (#~######), маркированные и нумерованные списки, блоки кода, таблицы, цитаты; внутри строки —
 * жирный, курсив, код и текст ссылок. Без сторонних библиотек, HTML и вложенных списков: этого
 * хватает для типичных ответов, и `##` или `|` не показываются как обычные символы.
 */
/** Текст ответа `.msg-ai`: Golos Text, межстрочный 1,6; размер — из темы Host (по умолчанию 16). */
@Composable
private fun bodyTextStyle(): TextStyle {
    val size = LocalConversationFontSize.current.toFloat()
    return ckBody(size.sp, (size * 1.6f).sp)
}

/** Ответ агента в Markdown: абзацы, заголовки, списки, задачи, цитаты, таблицы и блоки кода. */
@Composable
fun MarkdownMessageText(text: String, color: Color = com.dsh.core.designsystem.Dsh.colors.t1) {
    val c = com.dsh.core.designsystem.Dsh.colors
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, color),
                    style = ckDisplay(when (block.level) { 1 -> 21.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 16.sp }),
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text, color), style = bodyTextStyle(), color = color)
                is MarkdownBlock.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth().padding(start = (16 * block.depth).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val lh = with(androidx.compose.ui.platform.LocalDensity.current) { bodyTextStyle().lineHeight.toDp() }
                    Box(Modifier.width(18.dp).height(lh), contentAlignment = Alignment.Center) {
                        if (block.checked != null) ChecklistBox(block.checked)
                        else Text(block.marker, style = bodyTextStyle(), color = c.t3)
                    }
                    Text(inlineMarkdown(block.text, color), style = bodyTextStyle(), color = if (block.checked == true) c.t3 else color, modifier = Modifier.weight(1f))
                }
                is MarkdownBlock.Code -> CodeBlock(block.code, block.lang)
                is MarkdownBlock.Table -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(14.dp))
                        .horizontalScroll(rememberScrollState()),
                ) {
                    block.rows.forEachIndexed { index, cells ->
                        Row(
                            Modifier.then(if (index == 0) Modifier.background(c.s2) else Modifier)
                                .drawBehind { if (index > 0) drawRect(c.line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            cells.forEach { cell ->
                                Text(
                                    inlineMarkdown(cell, color), style = ckBody(13.5.sp, 18.sp, if (index == 0) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (index == 0) c.t2 else color, modifier = Modifier.widthIn(min = 64.dp, max = 220.dp),
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.s3))
                    Text(inlineMarkdown(block.text, color), style = bodyTextStyle(), color = c.t2, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/**
 * Блок кода `.code`: фон терминала, номера строк, моноширинный с межстрочным 1,7;
 * строки «+»/«−» диффа подсвечены; копирование — кнопкой в углу.
 */
@Composable
private fun CodeBlock(code: String, lang: String = "") {
    val c = com.dsh.core.designsystem.Dsh.colors
    val ctx = LocalContext.current
    val size = com.dsh.core.designsystem.LocalCodeFontSize.current - 0.5f
    // Пределы как в «Файлах» (G-16): строка до MAX_CODE_LINE символов, не больше MAX_CODE_LINES строк —
    // минифицированный JS в ответе не рисуется текстом шире предела Compose. «Копировать» берёт блок целиком.
    val allLines = remember(code) { code.lines() }
    val lines = remember(allLines) { allLines.take(MAX_CODE_LINES).map { if (it.length > MAX_CODE_LINE) it.take(MAX_CODE_LINE) + "…" else it } }
    val diff = remember(code) { lines.count { it.startsWith("+") || it.startsWith("-") } >= lines.size / 2 && lines.any { it.startsWith("@@") || it.startsWith("+") } }
    // Подсветка как в «Файлах» — у блоков с языком (не у диффа и не у огромных блоков).
    val painted = remember(lines, lang, diff, c) {
        if (lang.isEmpty() || diff || code.length > 50_000) null
        else lines.map { com.dsh.core.designsystem.highlightCode(listOf(it), com.dsh.core.designsystem.codeLangExt(lang), c) }
    }
    val shape = RoundedCornerShape(16.dp)
    val runInTerminal = LocalRunInTerminal.current
    val copied = stringResource(R.string.markdown_copied)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.termBg).border(1.dp, c.line, shape)) {
        // `.mc-head`: язык, «В терминал» (только команды оболочки — подставить, не запускать), «Копировать».
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifEmpty { stringResource(R.string.markdown_code) }, style = ckMono(12.sp, 16.sp), color = c.t3, maxLines = 1, modifier = Modifier.weight(1f))
            if (runInTerminal != null && lang in SHELL_LANGS) {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { runInTerminal(code) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.Icon(com.dsh.core.designsystem.DshGlyphs.Term, null, tint = c.t2, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.markdown_to_terminal), style = ckBody(13.sp, 16.sp, androidx.compose.ui.text.font.FontWeight.Medium), color = c.t2)
                }
            }
            ChatIconBtn(com.dsh.core.designsystem.DshGlyphs.Copy, stringResource(R.string.markdown_copy), tint = c.t2, iconSize = 18.dp) {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            lines.forEachIndexed { i, line ->
                val (bg, fg) = when {
                    diff && line.startsWith("+") -> c.okSoft to c.ok
                    diff && line.startsWith("-") -> c.dangerSoft to c.danger
                    diff && line.startsWith("@@") -> Color.Transparent to c.t3
                    else -> Color.Transparent to c.termText
                }
                Row(Modifier.background(bg).padding(end = 14.dp)) {
                    Text(
                        (i + 1).toString(), style = ckMono(size.sp, (size * 1.7f).sp), color = c.t3.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(38.dp).padding(end = 12.dp),
                    )
                    if (painted != null && fg == c.termText) Text(painted[i], style = ckMono(size.sp, (size * 1.7f).sp), color = fg, softWrap = false)
                    else Text(line, style = ckMono(size.sp, (size * 1.7f).sp), color = fg, softWrap = false)
                }
            }
            if (allLines.size > lines.size) Text(
                stringResource(R.string.markdown_code_more, lines.size, allLines.size),
                style = ckBody(12.5.sp, 16.sp), color = c.t3,
                modifier = Modifier.padding(start = 50.dp, top = 6.dp, end = 14.dp),
            )
        }
    }
}

/** Языки блоков кода, которые можно подставить в терминал. */
private val SHELL_LANGS = setOf("bash", "sh", "shell", "zsh", "console")

/** «В терминал» для блоков кода оболочки: открыть терминал с командой в поле ввода; null — терминала нет. */
val LocalRunInTerminal = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

private const val MAX_CODE_LINES = 400
private const val MAX_CODE_LINE = 2000

/** Поддерживаемые блоки Markdown. */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(
        val marker: String,
        val text: String,
        val depth: Int = 0,
        /** Отметка пункта задачи; null — обычный пункт списка. */
        val checked: Boolean? = null,
    ) : MarkdownBlock
    data class Code(val code: String, val lang: String = "") : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
}

/** Отметка задачи `- [x]`: квадрат 16 dp, отмечено — акцентом. */
@Composable
private fun ChecklistBox(checked: Boolean) {
    val c = com.dsh.core.designsystem.Dsh.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(16.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(1.5.dp, if (checked) c.accent else c.t3, shape),
        contentAlignment = Alignment.Center,
    ) { if (checked) androidx.compose.material3.Icon(com.dsh.core.designsystem.DshGlyphs.Check, null, tint = c.onAccent, modifier = Modifier.size(11.dp)) }
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val listRegex = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val taskRegex = Regex("^\\[([ xX])]\\s+(.*)$")
private val tableSeparatorRegex = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
private val fenceRegex = Regex("^\\s*```(.*)$")

/** Разбить Markdown на блоки; незнакомое — абзацем, чтобы текст не пропал. */
internal fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var index = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        val value = paragraph.toString().trim()
        if (value.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(value)
        }
        paragraph.setLength(0)
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        val fence = fenceRegex.find(line)
        when {
            fence != null -> {
                flushParagraph()
                val code = StringBuilder()
                index += 1
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index += 1
                }
                blocks += MarkdownBlock.Code(code.toString().trimEnd(), fence.groupValues[1].trim().substringBefore(' ').lowercase())
            }
            headingRegex.matches(trimmed) -> {
                flushParagraph()
                val match = headingRegex.find(trimmed)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.startsWith("|") && index + 1 < lines.size &&
                tableSeparatorRegex.matches(lines[index + 1].trim()) -> {
                flushParagraph()
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    val cells = lines[index].trim().trim('|').split("|").map { it.trim() }
                    if (!tableSeparatorRegex.matches(lines[index].trim())) {
                        rows += cells
                    }
                    index += 1
                }
                blocks += MarkdownBlock.Table(rows)
                continue
            }
            listRegex.matches(line) -> {
                flushParagraph()
                val match = listRegex.find(line)!!
                val indent = match.groupValues[1].length
                val depth = (indent / 2).coerceIn(0, 3)
                val rawMarker = match.groupValues[2]
                var itemText = match.groupValues[3].trim()
                // Список задач: - [ ] / - [x]
                val task = taskRegex.find(itemText)
                val checked = task?.let {
                    it.groupValues[1].equals("x", ignoreCase = true)
                }
                val marker = if (rawMarker.first().isDigit()) rawMarker else "•"
                if (task != null) itemText = task.groupValues[2].trim()
                blocks += MarkdownBlock.ListItem(
                    marker = marker,
                    text = itemText,
                    depth = depth,
                    checked = checked,
                )
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        index += 1
    }
    flushParagraph()
    return blocks
}

/**
 * Один проход по строке (G-16): раньше на каждый фрагмент — 4 поиска по остатку строки (O(n²)), без `remember`,
 * и при потоке ответа весь текст разбирался заново на каждый токен. При совпадении в одной позиции приоритет —
 * жирный, код, ссылка, курсив (как раньше).
 */
private val inlineRegex = Regex(
    "\\*\\*([^*]+)\\*\\*" +
        "|`([^`]+)`" +
        "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +
        "|(?<!\\*)\\*([^*]+)\\*(?!\\*)",
)

/** Строчные стили: жирный, код, ссылка, курсив. Разбор запоминается по тексту и цветам. */
@Composable
private fun inlineMarkdown(source: String, color: Color): AnnotatedString {
    val c = com.dsh.core.designsystem.Dsh.colors
    val ctx = LocalContext.current
    return remember(source, c.accent, c.t3, c.codeBg) {
        inlineMarkdownOf(source, c.accent, c.t3, c.codeBg) { url -> openHttpLink(ctx, url) }
    }
}

/**
 * Разбор строчной разметки. Ссылка `[текст](url)` (G-31) нажимается, только если это http(s), и рядом виден
 * домен — куда ведёт ссылка, видно до нажатия; другие схемы (`javascript:`, `intent:`, `file:`) — просто текст.
 */
internal fun inlineMarkdownOf(
    source: String,
    accent: Color,
    muted: Color,
    codeBg: Color,
    onLink: (String) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(source)) {
        append(source, last, m.range.first)
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1]!!.value) }
            // `.msg-ai code`: моноширинный 13,5 на фоне кода.
            g[2] != null -> withStyle(
                SpanStyle(fontFamily = com.dsh.core.designsystem.DshFonts.mono, fontSize = 13.5.sp, background = codeBg),
            ) { append("\u2009" + g[2]!!.value + "\u2009") }
            g[3] != null -> {
                val label = g[3]!!.value
                val url = g[4]!!.value
                val host = webLinkHost(url)
                if (host == null) {
                    withStyle(SpanStyle(color = accent)) { append(label) }
                } else {
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = url,
                            styles = androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = accent)),
                            linkInteractionListener = { onLink(url) },
                        ),
                    ) { append(label) }
                    if (!label.contains(host, ignoreCase = true)) withStyle(SpanStyle(color = muted)) { append(" ($host)") }
                }
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]!!.value) }
        }
        last = m.range.last + 1
    }
    append(source, last, source.length)
}

/** Домен http(s)-ссылки; другая схема или битый адрес — null (ссылка не нажимается). */
internal fun webLinkHost(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    uri.host?.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && it.isNotBlank() }?.removePrefix("www.")
}.getOrNull()

private fun openHttpLink(context: android.content.Context, url: String) {
    if (webLinkHost(url) == null) return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
