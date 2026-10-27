package com.dsh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.model.ReferenceCandidate

/**
 * Незаконченная ссылка «@» в конце черновика (черновик набирается в конец, как и голосовой ввод).
 * @param start индекс `@` в тексте.
 * @param query текст после `@` (после `@"` — без кавычки).
 * @param quoted открыта кавычка `@"…`: путь с пробелами, только файлы.
 */
internal data class MentionQuery(val start: Int, val query: String, val quoted: Boolean) {
    /** Папка запроса до последнего `/` включительно; пусто — корень рабочей папки. */
    val dir: String get() = query.substring(0, query.lastIndexOf('/') + 1)

    /** Запрос — путь внутри папки или в кавычках: сессии не подходят. */
    val filesOnly: Boolean get() = quoted || dir.isNotEmpty()
}

/** Ссылка «@», которую человек сейчас набирает в конце [text]; null — её нет. */
internal fun trailingMention(text: String): MentionQuery? {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    val rest = text.substring(at + 1)
    if (rest.startsWith("\"")) {
        val inner = rest.substring(1)
        return if (inner.contains('"')) null else MentionQuery(at, inner, quoted = true)
    }
    if (rest.startsWith("[") || rest.any { it.isWhitespace() }) return null
    return MentionQuery(at, rest, quoted = false)
}

/** Черновик после выбора [candidate]: ссылка заменяет набранный запрос, за ней пробел. */
internal fun applyMention(text: String, query: MentionQuery, candidate: ReferenceCandidate): String =
    text.substring(0, query.start) + candidate.mention + " "

/** Черновик для перехода в папку [dir] (`app/src/`): список остаётся открытым. */
internal fun drillMention(text: String, query: MentionQuery, dir: String): String =
    text.substring(0, query.start) + (if (query.quoted || dir.any { it.isWhitespace() }) "@\"" else "@") + dir

/** Состояние списка «@». */
internal sealed interface MentionUi {
    data object Loading : MentionUi
    data object Failed : MentionUi
    data class Ready(val items: List<ReferenceCandidate>) : MentionUi
}

private val SESSION_MENTION = Regex("""@\[((?:\\.|[^\\\]\n])*)]\(dsh-session:[^)\s]+\)""")
private val FILE_MENTION = Regex("""(?<=^|\s)@(?:"[^"\n]+"|[^\s@\["]+)""")

/**
 * Ссылки в поле ввода: `@[название](dsh-session:id)` показывается как «@название» цветом сессии,
 * `@путь` — акцентом. В тексте сообщения ссылка остаётся целиком.
 */
internal class MentionTransformation(private val fileStyle: SpanStyle, private val sessionStyle: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val sessions = SESSION_MENTION.findAll(raw).toList()
        if (sessions.isEmpty() && !raw.contains('@')) return TransformedText(text, OffsetMapping.Identity)
        // Отрезки исходного текста → отрезки показанного: для ссылки на сессию показывается только «@название».
        val origStarts = ArrayList<Int>(); val shownStarts = ArrayList<Int>(); val lengths = ArrayList<Pair<Int, Int>>()
        val out = buildAnnotatedString {
            var pos = 0
            fun plain(end: Int) {
                if (end <= pos) return
                val chunk = raw.substring(pos, end)
                origStarts += pos; shownStarts += length; lengths += chunk.length to chunk.length
                var last = 0
                FILE_MENTION.findAll(chunk).forEach { m ->
                    append(chunk.substring(last, m.range.first))
                    withStyle(fileStyle) { append(m.value) }
                    last = m.range.last + 1
                }
                append(chunk.substring(last))
                pos = end
            }
            sessions.forEach { m ->
                plain(m.range.first)
                val shown = "@" + m.groupValues[1].replace(Regex("""\\(.)"""), "$1")
                origStarts += pos; shownStarts += length; lengths += m.value.length to shown.length
                withStyle(sessionStyle) { append(shown) }
                pos = m.range.last + 1
            }
            plain(raw.length)
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                for (i in origStarts.indices.reversed()) if (offset >= origStarts[i]) {
                    val (orig, shown) = lengths[i]
                    val inside = offset - origStarts[i]
                    return shownStarts[i] + if (orig == shown) inside.coerceAtMost(shown) else if (inside >= orig) shown else inside.coerceAtMost(shown)
                }
                return offset
            }

            override fun transformedToOriginal(offset: Int): Int {
                for (i in shownStarts.indices.reversed()) if (offset >= shownStarts[i]) {
                    val (orig, shown) = lengths[i]
                    val inside = offset - shownStarts[i]
                    return origStarts[i] + if (orig == shown) inside.coerceAtMost(orig) else if (inside >= shown) orig else inside
                }
                return offset
            }
        }
        return TransformedText(out, mapping)
    }
}

/**
 * Список «@» над строкой ввода: файлы и папки (переход внутрь — шевроном, назад — крошками пути),
 * затем сессии; загрузка, «ничего не найдено» и ошибка с «Повторить».
 */
@Composable
internal fun MentionPanel(
    query: MentionQuery,
    state: MentionUi,
    onPick: (ReferenceCandidate) -> Unit,
    onDrill: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val c = Dsh.colors
    Column(
        Modifier.fillMaxWidth().heightIn(max = 292.dp).verticalScroll(rememberScrollState())
            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 6.dp),
    ) {
        if (query.dir.isNotEmpty()) Crumbs(query.dir, onDrill)
        when (state) {
            MentionUi.Loading -> repeat(3) {
                Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.s2))
            }
            MentionUi.Failed -> StateRow(DshGlyphs.Alert, c.warn, stringResource(R.string.mention_error)) {
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.s2).clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_retry), style = ckBody(14.sp), color = c.t1) }
            }
            is MentionUi.Ready -> {
                val files = state.items.filter { it.kind != ReferenceCandidate.Kind.SESSION }
                val sessions = state.items.filter { it.kind == ReferenceCandidate.Kind.SESSION }
                if (files.isEmpty() && sessions.isEmpty()) {
                    val shown = if (query.quoted) "\"${query.query}" else query.query
                    StateRow(DshGlyphs.Search, c.t3, stringResource(R.string.mention_empty, shown)) {}
                }
                if (files.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.mention_files))
                    files.forEach { f -> CandidateRow(f, query, onPick, onDrill) }
                }
                if (sessions.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.mention_sessions))
                    sessions.forEach { s -> CandidateRow(s, query, onPick, onDrill) }
                }
            }
        }
    }
}

@Composable
private fun Crumbs(dir: String, onDrill: (String) -> Unit) {
    val c = Dsh.colors
    val parts = dir.trimEnd('/').split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(stringResource(R.string.mention_root)) { onDrill("") }
        parts.forEachIndexed { i, part ->
            Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(12.dp))
            Crumb(part) { onDrill(parts.take(i + 1).joinToString("/") + "/") }
        }
    }
}

@Composable
private fun Crumb(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ckBody(12.5.sp), color = Dsh.colors.accent, maxLines = 1) }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = ckBody(11.5.sp, 16.sp, FontWeight.SemiBold), color = Dsh.colors.t3,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun StateRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = ckBody(13.5.sp, 18.sp), color = Dsh.colors.t2, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun CandidateRow(item: ReferenceCandidate, query: MentionQuery, onPick: (ReferenceCandidate) -> Unit, onDrill: (String) -> Unit) {
    val c = Dsh.colors
    val dir = item.kind == ReferenceCandidate.Kind.DIRECTORY
    val session = item.kind == ReferenceCandidate.Kind.SESSION
    // Внутри папки показывается имя относительно неё, как в прототипе.
    val name = if (!session && item.title.startsWith(query.dir)) item.title.removePrefix(query.dir) else item.title
    val needle = query.query.substring(query.dir.length)
    val detail = when {
        session -> listOfNotNull(
            item.createdAt.takeIf { it > 0 }?.let {
                android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            },
            item.detail.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        query.dir.isNotEmpty() -> ""
        dir -> stringResource(R.string.mention_folder)
        else -> stringResource(R.string.mention_file)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onPick(item) }
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tile = when {
            session -> c.ok.copy(alpha = 0.14f)
            dir -> c.accentSoft
            else -> c.s2
        }
        val tint = when {
            session -> c.ok
            dir -> c.accent
            else -> c.t2
        }
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
            Icon(if (session) DshGlyphs.Msg else if (dir) DshGlyphs.Folder else DshGlyphs.File, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(highlight(name, needle, c.accent), style = ckBody(14.5.sp, 19.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = ckBody(12.5.sp, 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dir) {
            val open = stringResource(R.string.mention_open_dir)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onDrill(item.title) }
                    .semantics { contentDescription = open },
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp)) }
        }
    }
}

private fun highlight(text: String, needle: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val i = if (needle.isEmpty()) -1 else text.indexOf(needle, ignoreCase = true)
    if (i < 0) { append(text); return@buildAnnotatedString }
    append(text.substring(0, i))
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(i, i + needle.length)) }
    append(text.substring(i + needle.length))
}
