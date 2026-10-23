package com.deepseek.dsh.runtime.host

/**
 * 安装 spec 的解析与最小 semver 匹配。
 *
 * 插件页的「添加插件」接受三类输入：npm 包名、GitHub 仓库地址、本地绝对目录。
 * Host 内的 pnpm 在 proot 下会拿到 `ENOSYS`（rename/unlink/mkdtemp 不可用），
 * 因此 App 侧兜底安装只实现自己需要的那部分解析，不追求完整的 npm 语义。
 */
internal sealed class PluginInstallSpec {
    /** npm 包名，可带 `@range`。 */
    data class Registry(val name: String, val range: String?) : PluginInstallSpec()

    /** GitHub 仓库地址，可带 `#ref`。 */
    data class GitHub(val owner: String, val repo: String, val ref: String?) : PluginInstallSpec()

    /** rootfs 内的绝对目录。 */
    data class LocalPath(val path: String) : PluginInstallSpec()

    companion object {
        private val GITHUB = Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([^/\s#]+)/([^/\s#]+?)(?:\.git)?(?:#(\S+))?/?$""",
            RegexOption.IGNORE_CASE,
        )
        private val PACKAGE_NAME = Regex("""^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$""")

        /**
         * npm 包名校验（含 scope）。安装器会把包名拼成 `node_modules/<name>` 并在其上
         * 删除/替换目录，所以任何来自 package.json 或依赖表的名字都必须先过这一关：
         * 只允许小写 npm 名字字符，不允许路径分隔符（scope 的单个 `/` 除外）、`.`/`..`
         * 段、绝对路径或超长名字。
         */
        fun isValidPackageName(name: String): Boolean =
            name.length in 1..214 && PACKAGE_NAME.matches(name)

        /**
         * 解析用户输入。
         * @param raw 对话框里的原始 spec。
         * @return 解析结果；无法识别时为 null。
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
 * npm 语义的 semver：版本比较、range 解析与匹配。
 *
 * 覆盖 npm 常用 range 语法：精确版本、`=`/`v` 前缀、比较符（`>`/`>=`/`<`/`<=`）、
 * `^`/`~`/`~>`、x-range（`1.x`、`1.2.*`、`1`）、连字符 range（`1.2.3 - 2.3.4`）、
 * 空格交集与 `||` 并集，以及 npm 的 prerelease 规则（prerelease 版本只在同一
 * `[major, minor, patch]` 的比较符显式带 prerelease 时才匹配）。
 *
 * **fail closed**：无法解析的 range、或没有任何候选满足 range 时返回 null，
 * 绝不退回「取最高版本」——那会静默装上不兼容的依赖。
 */
internal object Semver {

    /** 解析后的版本；build metadata 不参与比较。 */
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

    /** 部分版本：`1`、`1.2`、`1.x`、`1.2.*`、`*`；null 分量表示通配。 */
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

    /** 解析成可比较的版本；非 semver 返回 null。 */
    fun parseVersion(raw: String): Version? {
        val match = VERSION.matchEntire(raw.trim()) ?: return null
        return Version(
            match.groupValues[1].toLongOrNull() ?: return null,
            match.groupValues[2].toLongOrNull() ?: return null,
            match.groupValues[3].toLongOrNull() ?: return null,
            match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty(),
        )
    }

    /** 是否是可解析的 range 或常见 dist-tag。 */
    fun isRangeLike(raw: String): Boolean =
        raw == "latest" || raw == "next" || parseRange(raw) != null

    /** 比较两个版本；任一无法解析时按字符串比较（只用于展示排序）。 */
    fun compare(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        if (left == null || right == null) return a.compareTo(b)
        return left.compareTo(right)
    }

    /** 版本是否满足 range；range 无法解析时为 false（fail closed）。 */
    fun satisfies(version: String, range: String?): Boolean {
        val parsed = parseVersion(version) ?: return false
        val sets = parseRange(range ?: "*") ?: return false
        return sets.any { set -> testSet(set, parsed) }
    }

    /**
     * 从候选版本里挑出满足 range 的最高版本。
     *
     * @return 满足的最高版本；range 无法解析或没有满足项时为 null。
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
        // npm：prerelease 只有在同一 [major,minor,patch] 的比较符显式带 prerelease 时才可选。
        return set.any { it.version.isPrerelease && it.version.sameTuple(version) }
    }

    /** 解析 range 为「并集 of 交集」；无法解析时 null。 */
    private fun parseRange(raw: String): List<List<Comparator>>? {
        val text = raw.trim()
        if (text.isEmpty()) return listOf(emptyList())
        val sets = text.split("||").map { it.trim() }
        return sets.map { part -> parseComparatorSet(part) ?: return null }
    }

    private fun parseComparatorSet(raw: String): List<Comparator>? {
        if (raw.isEmpty()) return emptyList()
        // 连字符 range：`a - b`（两边必须有空格）。
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
        // 把「>= 1.2.3」这样的空格合并到运算符上。
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
                // 精确或 x-range（`1.2.3`、`=1.2.3`、`1.x`、`1`、`*`）。
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

    /** `^`：不改变最左侧非零分量（npm 对 0.x / 0.0.x 的特殊语义）。 */
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
