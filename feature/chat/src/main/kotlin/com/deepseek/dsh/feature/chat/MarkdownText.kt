package com.deepseek.dsh.feature.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染（官方 markdown 渲染器的移动端子集）。
 *
 * 支持标题（#~######）、无序/有序列表、代码块、表格、引用，以及行内的粗体、
 * 斜体、行内代码与链接文本。不引入第三方依赖，也不做 HTML 与嵌套列表——官方
 * 客户端里对话正文最常见的这几类块已经能正确显示，而不是把 `##`、`|` 当普通字符。
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
                is MarkdownBlock.Code -> CodeBlock(block.code)
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
private fun CodeBlock(code: String) {
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val ctx = LocalContext.current
    val size = com.deepseek.dsh.core.designsystem.LocalCodeFontSize.current - 0.5f
    val lines = remember(code) { code.lines() }
    val diff = remember(code) { lines.count { it.startsWith("+") || it.startsWith("-") } >= lines.size / 2 && lines.any { it.startsWith("@@") || it.startsWith("+") } }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.termBg).border(1.dp, c.line, shape)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            lines.forEachIndexed { i, line ->
                val (bg, fg) = when {
                    diff && line.startsWith("+") -> c.okSoft to c.ok
                    diff && line.startsWith("-") -> c.dangerSoft to c.danger
                    diff && line.startsWith("@@") -> Color.Transparent to c.t3
                    else -> Color.Transparent to c.termText
                }
                Row(Modifier.background(bg).padding(end = 44.dp)) {
                    Text(
                        (i + 1).toString(), style = ckMono(size.sp, (size * 1.7f).sp), color = c.t3.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(38.dp).padding(end = 12.dp),
                    )
                    Text(line, style = ckMono(size.sp, (size * 1.7f).sp), color = fg, softWrap = false)
                }
            }
        }
        val copied = stringResource(R.string.markdown_copied)
        Box(Modifier.align(Alignment.TopEnd)) {
            ChatIconBtn(com.deepseek.dsh.core.designsystem.DshGlyphs.Copy, stringResource(R.string.markdown_copy), tint = c.t3, iconSize = 16.dp) {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Markdown 的块级元素子集。 */
private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(
        val marker: String,
        val text: String,
        val depth: Int = 0,
        /** 任务列表的勾选状态；null 表示普通列表项。 */
        val checked: Boolean? = null,
    ) : MarkdownBlock
    data class Code(val code: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
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

/** 把 Markdown 文本切成块；未知块一律按段落处理，保证原文不丢。 */
private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
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
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
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
                // 任务列表：- [ ] / - [x]
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

private val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
private val codeRegex = Regex("`([^`]+)`")
private val linkRegex = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val italicRegex = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")

/** 行内样式：粗体、行内代码、链接文本、斜体。 */
@Composable
private fun inlineMarkdown(source: String, color: Color): AnnotatedString = buildAnnotatedString {
    var rest = source
    while (rest.isNotEmpty()) {
        val candidates = listOfNotNull(
            boldRegex.find(rest)?.let { Triple(0, it.range, it.groupValues[1]) },
            codeRegex.find(rest)?.let { Triple(1, it.range, it.groupValues[1]) },
            linkRegex.find(rest)?.let { Triple(2, it.range, it.groupValues[1]) },
            italicRegex.find(rest)?.let { Triple(3, it.range, it.groupValues[1]) },
        ).minByOrNull { it.second.first }
        if (candidates == null) {
            append(rest)
            break
        }
        if (candidates.second.first > 0) {
            append(rest.substring(0, candidates.second.first))
        }
        when (candidates.first) {
            0 -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(candidates.third) }
            // `.msg-ai code`: моноширинный 13,5 на фоне кода.
            1 -> withStyle(
                SpanStyle(
                    fontFamily = com.deepseek.dsh.core.designsystem.DshFonts.mono,
                    fontSize = 13.5.sp,
                    background = com.deepseek.dsh.core.designsystem.Dsh.colors.codeBg,
                ),
            ) { append("\u2009" + candidates.third + "\u2009") }
            2 -> withStyle(SpanStyle(color = com.deepseek.dsh.core.designsystem.Dsh.colors.accent)) {
                append(candidates.third)
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(candidates.third) }
        }
        rest = rest.substring(candidates.second.last + 1)
    }
}
