package com.deepseek.dsh.runtime.host

/**
 * Разбор spec установки и минимальное сопоставление semver.
 *
 * «Добавить плагин» принимает три вида ввода: имя пакета npm, адрес репозитория GitHub, абсолютную локальную папку.
 * pnpm в Host под proot получает `ENOSYS` (rename/unlink/mkdtemp недоступны),
 * поэтому запасной установщик приложения разбирает только нужное ему, без полной семантики npm.
 */
internal sealed class PluginInstallSpec {
    /** Имя пакета npm, можно с `@range`. */
    data class Registry(val name: String, val range: String?) : PluginInstallSpec()

    /** Адрес репозитория GitHub, можно с `#ref`. */
    data class GitHub(val owner: String, val repo: String, val ref: String?) : PluginInstallSpec()

    /** Абсолютная папка внутри rootfs. */
    data class LocalPath(val path: String) : PluginInstallSpec()

    companion object {
        private val GITHUB = Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([^/\s#]+)/([^/\s#]+?)(?:\.git)?(?:#(\S+))?/?$""",
            RegexOption.IGNORE_CASE,
        )
        private val PACKAGE_NAME = Regex("""^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$""")

        /**
         * Проверка имени пакета npm (со scope). Установщик складывает имя в `node_modules/<name>` и удаляет
         * или заменяет по нему папки, поэтому любое имя из package.json или списка зависимостей сначала проходит проверку:
         * только строчные символы имён npm, без разделителей пути (кроме одного `/` у scope), без сегментов `.`/`..`,
         * абсолютных путей и слишком длинных имён.
         */
        fun isValidPackageName(name: String): Boolean =
            name.length in 1..214 && PACKAGE_NAME.matches(name)

        /**
         * Разобрать ввод пользователя.
         * @param raw spec из окна.
         * @return итог разбора; не распознано — null.
         */
        fun parse(raw: String): PluginInstallSpec? {
            val spec = raw.trim()
            if (spec.isEmpty()) return null
            GITHUB.matchEntire(spec)?.let { match ->
                val owner = match.groupValues[1]
                val repo = match.groupValues[2]
                val ref = match.groupValues[3].takeIf { it.isNotBlank() }
                return GitHub(owner, repo, ref)
            }
            if (spec.startsWith("/")) return LocalPath(spec)
            val at = spec.indexOf('@', 1)
            val name = if (at == -1) spec else spec.substring(0, at)
            val range = if (at == -1) null else spec.substring(at + 1).takeIf { it.isNotBlank() }
            if (!isValidPackageName(name)) return null
            if (range != null && !Semver.isRangeLike(range)) return null
            return Registry(name, range)
        }
    }
}

/**
 * semver по правилам npm: сравнение версий, разбор и сопоставление range.
 *
 * Обычный синтаксис range npm: точная версия, префиксы `=`/`v`, сравнения (`>`/`>=`/`<`/`<=`),
 * `^`/`~`/`~>`, x-range (`1.x`, `1.2.*`, `1`), range через дефис (`1.2.3 - 2.3.4`),
 * пересечение через пробел и объединение `||`, а также правило prerelease npm (prerelease подходит, только если
 * сравнение с тем же `[major, minor, patch]` явно содержит prerelease).
 *
 * **fail closed**: неразбираемый range или отсутствие подходящих кандидатов — null,
 * никакого отката к «самой высокой версии» — это тихо поставило бы несовместимую зависимость.
 */
internal object Semver {

    /** Разобранная версия; build metadata в сравнении не участвует. */
    data class Version(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String> = emptyList(),
    ) : Comparable<Version> {
        val isPrerelease: Boolean get() = prerelease.isNotEmpty()

        override fun compareTo(other: Version): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
            if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
            if (prerelease.isEmpty()) return 1
            if (other.prerelease.isEmpty()) return -1
            for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
                val left = prerelease.getOrNull(index) ?: return -1
                val right = other.prerelease.getOrNull(index) ?: return 1
                val leftNum = left.toLongOrNull()
                val rightNum = right.toLongOrNull()
                val diff = when {
                    leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
                    leftNum != null -> -1
                    rightNum != null -> 1
                    else -> left.compareTo(right)
                }
                if (diff != 0) return diff
            }
            return 0
        }

        fun sameTuple(other: Version): Boolean =
            major == other.major && minor == other.minor && patch == other.patch
    }

    private val VERSION = Regex(
        """^[=v\s]*(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
            """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
    )

    /** Частичная версия: `1`, `1.2`, `1.x`, `1.2.*`, `*`; null в компоненте — любое значение. */
    private data class Partial(
        val major: Long?,
        val minor: Long?,
        val patch: Long?,
        val prerelease: List<String>,
    )

    private val PARTIAL = Regex(
        """^v?(\*|x|X|0|[1-9]\d*)(?:\.(\*|x|X|0|[1-9]\d*)(?:\.(\*|x|X|0|[1-9]\d*)""" +
            """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?)?)?$""",
    )

    private enum class Op { LT, LTE, GT, GTE, EQ }

    private data class Comparator(val op: Op, val version: Version) {
        fun test(candidate: Version): Boolean {
            val c = candidate.compareTo(version)
            return when (op) {
                Op.LT -> c < 0
                Op.LTE -> c <= 0
                Op.GT -> c > 0
                Op.GTE -> c >= 0
                Op.EQ -> c == 0
            }
        }
    }

    /** Разобрать в сравнимую версию; не semver — null. */
    fun parseVersion(raw: String): Version? {
        val match = VERSION.matchEntire(raw.trim()) ?: return null
        return Version(
            match.groupValues[1].toLongOrNull() ?: return null,
            match.groupValues[2].toLongOrNull() ?: return null,
            match.groupValues[3].toLongOrNull() ?: return null,
            match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty(),
        )
    }

    /** Разбирается ли как range или обычный dist-tag. */
    fun isRangeLike(raw: String): Boolean =
        raw == "latest" || raw == "next" || parseRange(raw) != null

    /** Сравнить версии; если какую-то не разобрать — как строки (только для порядка при показе). */
    fun compare(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        if (left == null || right == null) return a.compareTo(b)
        return left.compareTo(right)
    }

    /** Подходит ли версия под range; range не разобрать — false (fail closed). */
    fun satisfies(version: String, range: String?): Boolean {
        val parsed = parseVersion(version) ?: return false
        val sets = parseRange(range ?: "*") ?: return false
        return sets.any { set -> testSet(set, parsed) }
    }

    /**
     * Самая высокая из кандидатов версия, подходящая под range.
     *
     * @return самая высокая подходящая; range не разобрать или подходящих нет — null.
     */
    fun maxSatisfying(versions: Collection<String>, range: String?): String? {
        val sets = parseRange(range?.trim().orEmpty().ifEmpty { "*" }) ?: return null
        return versions
            .mapNotNull { raw -> parseVersion(raw)?.let { raw to it } }
            .filter { (_, version) -> sets.any { set -> testSet(set, version) } }
            .maxWithOrNull { a, b -> a.second.compareTo(b.second) }
            ?.first
    }

    private fun testSet(set: List<Comparator>, version: Version): Boolean {
        if (!set.all { it.test(version) }) return false
        if (!version.isPrerelease) return true
        // npm: prerelease можно выбрать, только если сравнение с тем же [major,minor,patch] явно содержит prerelease.
        return set.any { it.version.isPrerelease && it.version.sameTuple(version) }
    }

    /** Разобрать range в «объединение пересечений»; не разобрать — null. */
    private fun parseRange(raw: String): List<List<Comparator>>? {
        val text = raw.trim()
        if (text.isEmpty()) return listOf(emptyList())
        val sets = text.split("||").map { it.trim() }
        return sets.map { part -> parseComparatorSet(part) ?: return null }
    }

    private fun parseComparatorSet(raw: String): List<Comparator>? {
        if (raw.isEmpty()) return emptyList()
        // Range через дефис: `a - b` (пробелы с обеих сторон обязательны).
        val hyphen = Regex("""^(\S+)\s+-\s+(\S+)$""").matchEntire(raw)
        if (hyphen != null) {
            val low = parsePartial(hyphen.groupValues[1]) ?: return null
            val high = parsePartial(hyphen.groupValues[2]) ?: return null
            val result = mutableListOf<Comparator>()
            lowerBound(low)?.let { result += Comparator(Op.GTE, it) }
            when {
                high.major == null -> Unit
                high.minor == null -> result += Comparator(Op.LT, Version(high.major + 1, 0, 0, listOf("0")))
                high.patch == null -> result += Comparator(Op.LT, Version(high.major, high.minor + 1, 0, listOf("0")))
                else -> result += Comparator(Op.LTE, Version(high.major, high.minor, high.patch, high.prerelease))
            }
            return result
        }
        // Пробел в «>= 1.2.3» присоединяется к оператору.
        val normalized = raw.replace(Regex("""(>=|<=|>|<|=|\^|~>|~)\s+"""), "$1")
        val tokens = normalized.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return tokens.flatMap { token -> parseComparator(token) ?: return null }
    }

    private fun parseComparator(token: String): List<Comparator>? {
        val operator = listOf("~>", ">=", "<=", ">", "<", "=", "^", "~").firstOrNull { token.startsWith(it) }
        val body = if (operator == null) token else token.removePrefix(operator)
        val partial = parsePartial(body) ?: return null
        val (major, minor, patch) = Triple(partial.major, partial.minor, partial.patch)
        val pre = partial.prerelease
        return when (operator) {
            "^" -> caret(partial)
            "~", "~>" -> when {
                major == null -> emptyList()
                minor == null -> listOf(
                    Comparator(Op.GTE, Version(major, 0, 0)),
                    Comparator(Op.LT, Version(major + 1, 0, 0, listOf("0"))),
                )
                else -> listOf(
                    Comparator(Op.GTE, Version(major, minor, patch ?: 0, pre)),
                    Comparator(Op.LT, Version(major, minor + 1, 0, listOf("0"))),
                )
            }
            ">" -> when {
                major == null -> listOf(Comparator(Op.LT, Version(0, 0, 0, listOf("0"))))
                minor == null -> listOf(Comparator(Op.GTE, Version(major + 1, 0, 0)))
                patch == null -> listOf(Comparator(Op.GTE, Version(major, minor + 1, 0)))
                else -> listOf(Comparator(Op.GT, Version(major, minor, patch, pre)))
            }
            ">=" -> lowerBound(partial)?.let { listOf(Comparator(Op.GTE, it)) } ?: emptyList()
            "<" -> when {
                major == null -> listOf(Comparator(Op.LT, Version(0, 0, 0, listOf("0"))))
                else -> listOf(Comparator(Op.LT, Version(major, minor ?: 0, patch ?: 0, if (patch == null) listOf("0") else pre)))
            }
            "<=" -> when {
                major == null -> emptyList()
                minor == null -> listOf(Comparator(Op.LT, Version(major + 1, 0, 0, listOf("0"))))
                patch == null -> listOf(Comparator(Op.LT, Version(major, minor + 1, 0, listOf("0"))))
                else -> listOf(Comparator(Op.LTE, Version(major, minor, patch, pre)))
            }
            else -> when {
                // Точная версия или x-range (`1.2.3`, `=1.2.3`, `1.x`, `1`, `*`).
                major == null -> emptyList()
                minor == null -> listOf(
                    Comparator(Op.GTE, Version(major, 0, 0)),
                    Comparator(Op.LT, Version(major + 1, 0, 0, listOf("0"))),
                )
                patch == null -> listOf(
                    Comparator(Op.GTE, Version(major, minor, 0)),
                    Comparator(Op.LT, Version(major, minor + 1, 0, listOf("0"))),
                )
                else -> listOf(Comparator(Op.EQ, Version(major, minor, patch, pre)))
            }
        }
    }

    /** `^`: не меняет самую левую ненулевую часть (особые правила npm для 0.x / 0.0.x). */
    private fun caret(p: Partial): List<Comparator> {
        val major = p.major ?: return emptyList()
        val minor = p.minor
        val patch = p.patch
        val low = Version(major, minor ?: 0, patch ?: 0, p.prerelease)
        val high = when {
            major > 0 -> Version(major + 1, 0, 0, listOf("0"))
            minor == null -> Version(1, 0, 0, listOf("0"))
            minor > 0 -> Version(0, minor + 1, 0, listOf("0"))
            patch == null -> Version(0, 1, 0, listOf("0"))
            else -> Version(0, 0, patch + 1, listOf("0"))
        }
        return listOf(Comparator(Op.GTE, low), Comparator(Op.LT, high))
    }

    private fun lowerBound(p: Partial): Version? {
        val major = p.major ?: return null
        return Version(major, p.minor ?: 0, p.patch ?: 0, if (p.patch == null) emptyList() else p.prerelease)
    }

    private fun parsePartial(raw: String): Partial? {
        val text = raw.trim()
        if (text == "*" || text == "x" || text == "X" || text.isEmpty()) {
            return Partial(null, null, null, emptyList())
        }
        val match = PARTIAL.matchEntire(text) ?: return null
        fun part(index: Int): Long? = match.groupValues[index]
            .takeIf { it.isNotEmpty() && it != "*" && it != "x" && it != "X" }
            ?.toLongOrNull()
        val major = part(1)
        val minor = if (major == null) null else part(2)
        val patch = if (minor == null) null else part(3)
        val pre = if (patch == null) emptyList()
        else match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        return Partial(major, minor, patch, pre)
    }
}
