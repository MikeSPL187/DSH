package com.dsh.core.designsystem

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

/** Язык блока кода (после ```) → расширение для [highlightCode]. */
fun codeLangExt(lang: String): String = when (lang.lowercase()) {
    "bash", "shell", "zsh", "console" -> "sh"
    "kotlin" -> "kt"
    "markdown" -> "md"
    "python", "py" -> "py"
    else -> lang.lowercase()
}

/**
 * Подсветка кода как `hl()` в прототипе: комментарии, строки, ключевые слова, секции TOML, заголовки Markdown.
 * Общая для «Файлов», правки и блоков кода в чате. [ext] — расширение файла или язык блока (`kt`, `sh`, `md`…).
 */
fun highlightCode(lines: List<String>, ext: String, c: DshColors): AnnotatedString = buildAnnotatedString {
    val token = Regex("(\"[^\"]*\"|'[^']*')|\\b(package|import|class|object|interface|override|fun|val|var|set|if|else|then|fi|for|while|return|echo|mkdir|tar|true|false|null|def|from|const|let|function|export)\\b|^(\\[[\\w.-]+\\])")
    lines.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        val comment = Regex("^\\s*(#|//)").containsMatchIn(line) && !(ext == "md" && line.startsWith("#"))
        when {
            comment -> withStyle(SpanStyle(color = c.t3, fontStyle = FontStyle.Italic)) { append(line) }
            ext == "md" && line.startsWith("#") -> withStyle(SpanStyle(color = c.accent)) { append(line) }
            else -> {
                var last = 0
                for (m in token.findAll(line)) {
                    append(line.substring(last, m.range.first))
                    val color = if (m.groups[1] != null) c.ok else c.accent
                    withStyle(SpanStyle(color = color)) { append(m.value) }
                    last = m.range.last + 1
                }
                append(line.substring(last))
            }
        }
    }
}
