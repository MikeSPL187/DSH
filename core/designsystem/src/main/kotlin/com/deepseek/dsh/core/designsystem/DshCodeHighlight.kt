package com.deepseek.dsh.core.designsystem

/**
 * Подсветка кода в стиле прототипа (`.tk-k` акцент, `.tk-s` ok, `.tk-c` t3 курсивом, `.tk-n` warn)
 * для блоков кода в ответах агента. Без сторонних библиотек: однопроходный разбор строки
 * на комментарии, строки, числа и ключевые слова; язык — по метке блока (```kotlin) или угадывается.
 */
object DshCodeHighlight {
    enum class Kind { Keyword, Str, Comment, Num }

    /** Отрезок подсветки: [start, end) в исходной строке. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    private val keywords: Map<String, Set<String>> = run {
        val c = setOf("if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "default", "goto", "sizeof", "struct", "union", "enum", "typedef", "static", "const", "extern", "void", "int", "char", "long", "short", "unsigned", "signed", "float", "double", "bool", "true", "false", "NULL", "include", "define")
        val cpp = c + setOf("class", "public", "private", "protected", "namespace", "using", "template", "typename", "virtual", "override", "new", "delete", "this", "nullptr", "auto", "try", "catch", "throw", "operator", "constexpr")
        val java = setOf("package", "import", "class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static", "final", "abstract", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "default", "try", "catch", "finally", "throw", "throws", "this", "super", "null", "true", "false", "void", "int", "long", "boolean", "char", "double", "float", "byte", "short", "var", "record", "instanceof", "synchronized")
        val kotlin = setOf("package", "import", "class", "object", "interface", "enum", "data", "sealed", "open", "abstract", "override", "private", "internal", "public", "protected", "fun", "val", "var", "const", "lateinit", "suspend", "inline", "return", "if", "else", "when", "for", "while", "do", "in", "is", "as", "by", "try", "catch", "finally", "throw", "this", "super", "null", "true", "false", "companion", "init", "typealias", "get", "set", "it")
        val js = setOf("import", "export", "from", "default", "as", "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "new", "class", "extends", "this", "super", "null", "undefined", "true", "false", "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "in", "of", "yield", "delete", "void", "static", "get", "set")
        val ts = js + setOf("interface", "type", "enum", "implements", "public", "private", "protected", "readonly", "declare", "namespace", "abstract", "keyof", "never", "unknown", "any", "string", "number", "boolean")
        val py = setOf("def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "import", "from", "as", "with", "try", "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield", "global", "nonlocal", "assert", "del", "async", "await", "None", "True", "False", "self", "print")
        val go = setOf("package", "import", "func", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go", "defer", "select", "chan", "map", "struct", "interface", "type", "var", "const", "nil", "true", "false", "make", "new", "len", "append", "error", "string", "int", "bool")
        val rust = setOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "pub", "use", "mod", "crate", "self", "Self", "super", "return", "if", "else", "match", "for", "while", "loop", "in", "break", "continue", "as", "ref", "move", "async", "await", "dyn", "where", "unsafe", "true", "false", "Some", "None", "Ok", "Err")
        val sh = setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function", "return", "export", "local", "readonly", "echo", "cd", "ls", "mkdir", "rm", "cp", "mv", "cat", "grep", "sed", "awk", "tar", "git", "sudo", "apt", "npm", "pip", "set", "source", "exit", "true", "false")
        val sql = setOf("select", "from", "where", "insert", "into", "values", "update", "delete", "create", "table", "drop", "alter", "index", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "offset", "and", "or", "not", "null", "as", "distinct", "primary", "key", "foreign", "references", "union", "case", "when", "then", "else", "end", "is", "in", "like", "count", "sum")
        val swift = setOf("import", "class", "struct", "enum", "protocol", "extension", "func", "let", "var", "return", "if", "else", "guard", "switch", "case", "default", "for", "in", "while", "repeat", "break", "continue", "self", "Self", "nil", "true", "false", "public", "private", "internal", "static", "override", "init", "throws", "try", "catch", "async", "await", "some", "any")
        val generic = kotlin + js + py
        mapOf(
            "c" to c, "cpp" to cpp, "java" to java, "kotlin" to kotlin, "js" to js, "ts" to ts, "python" to py,
            "go" to go, "rust" to rust, "sh" to sh, "sql" to sql, "swift" to swift, "generic" to generic,
            "json" to setOf("true", "false", "null"), "yaml" to setOf("true", "false", "null", "yes", "no"),
            "toml" to setOf("true", "false"),
        )
    }

    /** Привести метку блока к семейству языка; пустая метка — угадать по содержимому. */
    fun normalize(lang: String, code: String = ""): String {
        val l = lang.trim().lowercase().substringBefore(' ').substringBefore('{')
        return when (l) {
            "kt", "kts", "kotlin" -> "kotlin"
            "java" -> "java"
            "js", "javascript", "jsx", "mjs", "cjs", "node" -> "js"
            "ts", "typescript", "tsx" -> "ts"
            "py", "python", "python3" -> "python"
            "sh", "bash", "zsh", "shell", "console", "terminal", "fish" -> "sh"
            "go", "golang" -> "go"
            "rs", "rust" -> "rust"
            "c", "h" -> "c"
            "cpp", "c++", "cc", "cxx", "hpp", "objc", "cs", "csharp", "c#" -> "cpp"
            "sql", "sqlite", "postgres", "mysql" -> "sql"
            "swift" -> "swift"
            "json", "jsonc", "json5" -> "json"
            "yaml", "yml" -> "yaml"
            "toml", "ini", "properties" -> "toml"
            "diff", "patch" -> "diff"
            "md", "markdown" -> "markdown"
            "text", "txt", "plain", "plaintext", "log", "output" -> "text"
            "html", "xml", "svg", "vue" -> "xml"
            "css", "scss", "less" -> "css"
            "" -> guess(code)
            else -> if (l in keywords) l else "generic"
        }
    }

    /** Подпись языка в шапке блока. */
    fun label(lang: String): String {
        val l = lang.trim().substringBefore(' ').substringBefore('{')
        return when (l.lowercase()) {
            "kt", "kts", "kotlin" -> "Kotlin"
            "js", "javascript", "mjs", "cjs" -> "JavaScript"
            "jsx" -> "JSX"
            "ts", "typescript" -> "TypeScript"
            "tsx" -> "TSX"
            "py", "python", "python3" -> "Python"
            "sh", "bash" -> "Bash"
            "zsh" -> "Zsh"
            "shell", "console", "terminal" -> "Shell"
            "go", "golang" -> "Go"
            "rs", "rust" -> "Rust"
            "cpp", "c++", "cc", "cxx", "hpp" -> "C++"
            "cs", "csharp", "c#" -> "C#"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "toml" -> "TOML"
            "sql" -> "SQL"
            "html" -> "HTML"
            "xml" -> "XML"
            "css" -> "CSS"
            "md", "markdown" -> "Markdown"
            "diff", "patch" -> "Diff"
            "" -> ""
            else -> l.replaceFirstChar { it.uppercase() }
        }
    }

    private fun guess(code: String): String {
        val t = code.trimStart()
        return when {
            t.startsWith("{") || t.startsWith("[") -> "json"
            t.startsWith("$ ") || t.startsWith("#!/") -> "sh"
            Regex("^\\s*(fun |val |package |import [a-z]+\\.)", RegexOption.MULTILINE).containsMatchIn(code) -> "kotlin"
            Regex("^\\s*(def |from \\w+ import|import \\w+$)", RegexOption.MULTILINE).containsMatchIn(code) -> "python"
            Regex("^\\s*(const |let |function |export )", RegexOption.MULTILINE).containsMatchIn(code) -> "js"
            t.startsWith("<") -> "xml"
            else -> "text"
        }
    }

    private fun lineComment(lang: String): List<String> = when (lang) {
        "python", "sh", "yaml", "toml" -> listOf("#")
        "sql" -> listOf("--")
        "text", "markdown", "json", "diff", "xml" -> emptyList()
        "generic" -> listOf("//", "#")
        else -> listOf("//")
    }

    /**
     * Разобрать одну строку. [inBlock] — строка начинается внутри блочного комментария `/* … */`;
     * возвращает отрезки и признак «блочный комментарий не закрыт».
     */
    fun line(line: String, lang: String, inBlock: Boolean = false): Pair<List<Span>, Boolean> {
        if (lang == "text" || lang == "diff") return emptyList<Span>() to false
        val spans = mutableListOf<Span>()
        val kws = keywords[lang] ?: emptySet()
        val ci = lang == "sql"
        val lc = lineComment(lang)
        val blocks = lang !in setOf("python", "sh", "yaml", "toml", "text", "markdown", "json", "xml")
        var i = 0
        var block = inBlock
        if (block) {
            val end = line.indexOf("*/")
            if (end < 0) return listOf(Span(0, line.length, Kind.Comment)) to true
            spans += Span(0, end + 2, Kind.Comment); i = end + 2; block = false
        }
        if (lang == "markdown") {
            if (line.trimStart().startsWith("#")) spans += Span(0, line.length, Kind.Keyword)
            return spans to false
        }
        while (i < line.length) {
            val ch = line[i]
            // Секция TOML/INI в начале строки.
            if (lang == "toml" && i == 0 && ch == '[') {
                val end = line.indexOf(']')
                if (end > 0) { spans += Span(0, end + 1, Kind.Keyword); i = end + 1; continue }
            }
            if (lc.any { line.startsWith(it, i) } && (ch != '#' || lang != "generic" || i == 0 || line[i - 1].isWhitespace())) {
                spans += Span(i, line.length, Kind.Comment); break
            }
            if (blocks && line.startsWith("/*", i)) {
                val end = line.indexOf("*/", i + 2)
                if (end < 0) { spans += Span(i, line.length, Kind.Comment); return spans to true }
                spans += Span(i, end + 2, Kind.Comment); i = end + 2; continue
            }
            if (lang == "xml" && line.startsWith("<!--", i)) {
                val end = line.indexOf("-->", i + 4)
                val stop = if (end < 0) line.length else end + 3
                spans += Span(i, stop, Kind.Comment); i = stop; continue
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                // Апостроф в словах (don't) в sh/тексте не открывает строку.
                if (ch == '\'' && i > 0 && line[i - 1].isLetterOrDigit() && lang != "python" && lang != "sql") { i++; continue }
                var j = i + 1
                while (j < line.length && line[j] != ch) { if (line[j] == '\\') j++; j++ }
                val stop = (j + 1).coerceAtMost(line.length)
                spans += Span(i, stop, Kind.Str); i = stop; continue
            }
            if (ch.isDigit() && (i == 0 || !(line[i - 1].isLetterOrDigit() || line[i - 1] == '_'))) {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                spans += Span(i, j, Kind.Num); i = j; continue
            }
            if (lang == "xml" && ch == '<') {
                var j = i + 1
                if (j < line.length && (line[j] == '/' || line[j] == '?' || line[j] == '!')) j++
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "-_:.")) j++
                if (j > i + 1) { spans += Span(i, j, Kind.Keyword); i = j; continue }
            }
            if (ch.isLetter() || ch == '_' || ch == '@') {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val hit = if (ci) word.lowercase() in kws else word in kws || (ch == '@' && word.length > 1)
                if (hit && (lang != "json" && lang != "yaml" || word.lowercase() in kws)) spans += Span(i, j, Kind.Keyword)
                // Ключ YAML `name:` — ключевым цветом.
                if (lang == "yaml" && spans.none { it.start == i } && j < line.length && line[j] == ':' && line.substring(0, i).isBlank()) spans += Span(i, j, Kind.Keyword)
                i = j; continue
            }
            i++
        }
        return spans to block
    }

    /** Разобрать весь блок по строкам (с переносом блочного комментария). */
    fun lines(code: List<String>, lang: String): List<List<Span>> {
        var block = false
        return code.map { l -> val (s, b) = line(l, lang, block); block = b; s }
    }
}
