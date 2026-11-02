package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import com.deepseek.dsh.core.designsystem.LocalConversationFontSize
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
fun MarkdownMessageText(text: String, color: Color = com.deepseek.dsh.core.designsystem.Dsh.colors.t1) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
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
                MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.line))
                is MarkdownBlock.Table -> androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(14.dp)),
                ) {
                  // Строки — на всю ширину карточки (фон шапки и разделители), широкая таблица прокручивается.
                  Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    block.rows.forEachIndexed { index, cells ->
                        Row(
                            Modifier.fillMaxWidth().then(if (index == 0) Modifier.background(c.s2) else Modifier)
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
 * Блок кода `.code`: шапка с языком и «Копировать», фон терминала, номера строк,
 * подсветка синтаксиса (цвета `.tk-*` прототипа), моноширинный с межстрочным 1,7;
 * строки «+»/«−» диффа подсвечены; длинный блок свёрнут до [CODE_PREVIEW_LINES] строк.
 */
@Composable
private fun CodeBlock(code: String, lang: String) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val ctx = LocalContext.current
    val codeSize = com.deepseek.dsh.core.designsystem.LocalCodeFontSize.current - 0.5f
    val lines = remember(code) { code.lines() }
    val family = remember(lang, code) { com.deepseek.dsh.core.designsystem.DshCodeHighlight.normalize(lang, code) }
    val diff = remember(code, family) {
        family == "diff" || (lines.count { it.startsWith("+") || it.startsWith("-") } >= lines.size / 2 && lines.any { it.startsWith("@@") || it.startsWith("+") })
    }
    val spans = remember(code, family) { if (diff) emptyList() else com.deepseek.dsh.core.designsystem.DshCodeHighlight.lines(lines, family) }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(code.hashCode()) { androidx.compose.runtime.mutableStateOf(false) }
    val collapsible = lines.size > CODE_COLLAPSE_LINES
    val shown = if (collapsible && !expanded) CODE_PREVIEW_LINES else lines.size
    val shape = RoundedCornerShape(16.dp)
    val label = com.deepseek.dsh.core.designsystem.DshCodeHighlight.label(lang).ifEmpty { stringResource(R.string.markdown_code) }
    val copied = stringResource(R.string.markdown_copied)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.termBg).border(1.dp, c.line, shape)) {
        // Шапка: язык слева, «Копировать» справа.
        Row(
            Modifier.fillMaxWidth().height(40.dp)
                .drawBehind { drawRect(c.line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = ckMono(12.sp, 16.sp, FontWeight.Medium), color = c.t2, modifier = Modifier.weight(1f), maxLines = 1)
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                    android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.material3.Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.Copy, null, tint = c.t2, modifier = Modifier.size(15.dp))
                Text(stringResource(R.string.markdown_copy), style = ckBody(12.5.sp, 16.sp, FontWeight.Medium), color = c.t2)
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            val style = ckMono(codeSize.sp, (codeSize * 1.7f).sp)
            for (i in 0 until shown) {
                val line = lines[i]
                val (bg, fg) = when {
                    diff && line.startsWith("+") -> c.okSoft to c.ok
                    diff && line.startsWith("-") -> c.dangerSoft to c.danger
                    diff && line.startsWith("@@") -> Color.Transparent to c.t3
                    else -> Color.Transparent to c.termText
                }
                Row(Modifier.background(bg).padding(end = 16.dp)) {
                    Text(
                        (i + 1).toString(), style = style, color = c.t3.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(38.dp).padding(end = 12.dp),
                    )
                    val styled = if (diff) AnnotatedString(line) else highlightedLine(line, spans.getOrNull(i).orEmpty(), c)
                    Text(styled, style = style, color = fg, softWrap = false)
                }
            }
        }
        if (collapsible) {
            Row(
                Modifier.fillMaxWidth().height(44.dp)
                    .drawBehind { drawRect(c.line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Icon(
                    com.deepseek.dsh.core.designsystem.DshGlyphs.Chev, null, tint = c.accent,
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
                Text(
                    if (expanded) stringResource(R.string.markdown_collapse) else stringResource(R.string.markdown_show_all, lines.size),
                    style = ckBody(13.sp, 17.sp, FontWeight.Medium), color = c.accent, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Блок длиннее этого числа строк сворачивается. */
private const val CODE_COLLAPSE_LINES = 40
/** Сколько строк видно в свёрнутом блоке. */
private const val CODE_PREVIEW_LINES = 24

/** Строка кода с цветами `.tk-k` (акцент), `.tk-s` (ok), `.tk-c` (t3 курсивом), `.tk-n` (warn). */
private fun highlightedLine(
    line: String,
    spans: List<com.deepseek.dsh.core.designsystem.DshCodeHighlight.Span>,
    c: com.deepseek.dsh.core.designsystem.DshColors,
): AnnotatedString = buildAnnotatedString {
    append(line)
    spans.forEach { s ->
        val style = when (s.kind) {
            com.deepseek.dsh.core.designsystem.DshCodeHighlight.Kind.Keyword -> SpanStyle(color = c.accent)
            com.deepseek.dsh.core.designsystem.DshCodeHighlight.Kind.Str -> SpanStyle(color = c.ok)
            com.deepseek.dsh.core.designsystem.DshCodeHighlight.Kind.Comment -> SpanStyle(color = c.t3, fontStyle = FontStyle.Italic)
            com.deepseek.dsh.core.designsystem.DshCodeHighlight.Kind.Num -> SpanStyle(color = c.warn)
        }
        addStyle(style, s.start.coerceIn(0, line.length), s.end.coerceIn(0, line.length))
    }
}

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
    /** Блок кода; [lang] — метка после ``` (пусто — без метки). */
    data class Code(val code: String, val lang: String = "") : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    /** Цитата; строки подряд с `>` — одна цитата, пустая строка `>` — перенос абзаца. */
    data class Quote(val text: String) : MarkdownBlock
    /** Разделитель `---`, `***`, `___`. */
    data object Rule : MarkdownBlock
}

/** Отметка задачи `- [x]`: квадрат 16 dp, отмечено — акцентом. */
@Composable
private fun ChecklistBox(checked: Boolean) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(16.dp).clip(shape).background(if (checked) c.accent else Color.Transparent).border(1.5.dp, if (checked) c.accent else c.t3, shape),
        contentAlignment = Alignment.Center,
    ) { if (checked) androidx.compose.material3.Icon(com.deepseek.dsh.core.designsystem.DshGlyphs.Check, null, tint = c.onAccent, modifier = Modifier.size(11.dp)) }
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val listRegex = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val taskRegex = Regex("^\\[([ xX])]\\s+(.*)$")
private val tableSeparatorRegex = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
private val fenceRegex = Regex("^\\s*```(.*)$")
private val ruleRegex = Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$")

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
                val lang = fence.groupValues[1].trim()
                val code = StringBuilder()
                index += 1
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index += 1
                }
                blocks += MarkdownBlock.Code(code.toString().trimEnd(), lang)
            }
            headingRegex.matches(trimmed) -> {
                flushParagraph()
                val match = headingRegex.find(trimmed)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
            }
            ruleRegex.matches(trimmed) -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quote += lines[index].trim().trimStart('>').trim()
                    index += 1
                }
                // Соседние строки — один абзац (через пробел), пустая строка `>` — новый абзац.
                blocks += MarkdownBlock.Quote(quote.joinToString("\n").replace(Regex("(?<!\n)\n(?!\n)"), " ").replace(Regex("\n{2,}"), "\n").trim())
                continue
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
                // Перенос строки внутри абзаца сохраняется, как в ChatGPT и Claude: «по одному на строку»
                // не склеивается в одну строку.
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        index += 1
    }
    flushParagraph()
    return blocks
}

private val inlineCodeRegex = Regex("`([^`]+)`")
private val boldItalicRegex = Regex("\\*\\*\\*([^*]+)\\*\\*\\*")
private val boldRegex = Regex("\\*\\*(.+?)\\*\\*|(?<![\\w])__(.+?)__(?![\\w])")
private val strikeRegex = Regex("~~(.+?)~~")
private val linkRegex = Regex("!?\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
private val autoLinkRegex = Regex("https?://[^\\s<>()\\[\\]`]+")
private val italicRegex = Regex("(?<![*\\w])\\*(?![\\s*])([^*]+?)\\*(?![*\\w])|(?<![_\\w])_(?![\\s_])([^_]+?)_(?![_\\w])")

/** Вид строчного фрагмента. */
internal enum class InlineKind { Code, BoldItalic, Bold, Strike, Link, AutoLink, Italic }

/** Найденный строчный фрагмент: [range] — вся разметка, [text] — содержимое, [url] — адрес ссылки. */
internal data class InlineMatch(val kind: InlineKind, val range: IntRange, val text: String, val url: String? = null)

/** Ближайший к началу строчный фрагмент; при равенстве — по порядку [InlineKind] (код важнее всего). */
internal fun nextInline(source: String): InlineMatch? = listOfNotNull(
    inlineCodeRegex.find(source)?.let { InlineMatch(InlineKind.Code, it.range, it.groupValues[1]) },
    boldItalicRegex.find(source)?.let { InlineMatch(InlineKind.BoldItalic, it.range, it.groupValues[1]) },
    boldRegex.find(source)?.let { InlineMatch(InlineKind.Bold, it.range, it.groupValues[1].ifEmpty { it.groupValues[2] }) },
    strikeRegex.find(source)?.let { InlineMatch(InlineKind.Strike, it.range, it.groupValues[1]) },
    linkRegex.find(source)?.let { InlineMatch(InlineKind.Link, it.range, it.groupValues[1], it.groupValues[2]) },
    autoLinkRegex.find(source)?.let { m ->
        // Знаки препинания в конце адреса — часть предложения, а не ссылки.
        val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', '»', '"', '\'')
        InlineMatch(InlineKind.AutoLink, m.range.first until m.range.first + url.length, url, url)
    },
    italicRegex.find(source)?.let { InlineMatch(InlineKind.Italic, it.range, it.groupValues[1].ifEmpty { it.groupValues[2] }) },
).minWithOrNull(compareBy<InlineMatch> { it.range.first }.thenBy { it.kind.ordinal })

/** Открыть ссылку из ответа: только http/https, остальные схемы (intent:, file:, javascript:) игнорируются. */
private fun openAnswerLink(context: android.content.Context, url: String) {
    val uri = android.net.Uri.parse(url)
    if (uri.scheme?.lowercase() != "https" && uri.scheme?.lowercase() != "http") return
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Строчные стили: код, жирный, курсив, зачёркнутый, ссылки (нажимаются); вложенность — рекурсией. */
@Composable
private fun inlineMarkdown(source: String, color: Color): AnnotatedString {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val ctx = LocalContext.current
    return remember(source, color, c) {
        buildAnnotatedString { appendInline(source, c) { url -> openAnswerLink(ctx, url) } }
    }
}

private fun AnnotatedString.Builder.appendInline(
    source: String,
    c: com.deepseek.dsh.core.designsystem.DshColors,
    depth: Int = 0,
    onLink: (String) -> Unit,
) {
    var rest = source
    while (rest.isNotEmpty()) {
        val m = if (depth > 4) null else nextInline(rest)
        if (m == null) { append(rest); break }
        if (m.range.first > 0) append(rest.substring(0, m.range.first))
        when (m.kind) {
            // `.msg-ai code`: моноширинный 13,5 на фоне кода.
            InlineKind.Code -> withStyle(
                SpanStyle(fontFamily = com.deepseek.dsh.core.designsystem.DshFonts.mono, fontSize = 13.5.sp, background = c.codeBg),
            ) { append("\u2009" + m.text + "\u2009") }
            InlineKind.BoldItalic -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)) { appendInline(m.text, c, depth + 1, onLink) }
            InlineKind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(m.text, c, depth + 1, onLink) }
            InlineKind.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(m.text, c, depth + 1, onLink) }
            InlineKind.Strike -> withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { appendInline(m.text, c, depth + 1, onLink) }
            InlineKind.Link, InlineKind.AutoLink -> {
                val url = m.url.orEmpty()
                val web = url.startsWith("http://", true) || url.startsWith("https://", true)
                if (web) {
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = url,
                            styles = androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = c.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)),
                            linkInteractionListener = { onLink(url) },
                        ),
                    ) { append(m.text) }
                } else {
                    // Не веб-адрес (путь, якорь) — просто акцентный текст.
                    withStyle(SpanStyle(color = c.accent)) { append(m.text) }
                }
            }
        }
        rest = rest.substring(m.range.last + 1)
    }
}
