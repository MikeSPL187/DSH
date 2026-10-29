package com.dsh.feature.settings.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.FsEmpty
import com.dsh.core.designsystem.kit.FsView
import com.dsh.core.designsystem.kit.GLabel
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.SheetTitle
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.SiTile
import com.dsh.core.designsystem.kit.StChips
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.SubNote
import com.dsh.core.designsystem.kit.ToastState
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.rowClick
import com.dsh.core.designsystem.kit.small
import com.dsh.feature.settings.R
import kotlinx.coroutines.launch

/** Состояние MCP-сервера в Host: подключён, не запустился, ждёт перезапуска Host. */
enum class McpStatus { OK, ERROR, PENDING }

/**
 * Строка списка MCP-серверов.
 *
 * @param where адрес (`http`) или команда целиком (`stdio`).
 * @param tokenTail последние 4 символа токена; null — токена нет. Сам токен приложение не хранит и не показывает.
 */
@Immutable
data class McpRow(val name: String, val http: Boolean, val where: String, val tokenTail: String?, val status: McpStatus)

/** Экран «MCP-серверы»: строки и нужен ли перезапуск Host (что-то добавлено или удалено после запуска). */
@Immutable
data class McpUi(val servers: List<McpRow> = emptyList(), val restartNeeded: Boolean = false)

private val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")
private val URL = Regex("""^(https://[^\s/?#]+(?:[/?#]\S*)?|http://(localhost|127\.0\.0\.1)(:\d{1,5})?(/\S*)?)$""")

/** Ключ имени, как у ссылки на токен в Host: регистр и «-»/«_» не различаются. */
private fun nameKey(name: String) = name.uppercase().replace('-', '_')

/** Значение строки корня «MCP-серверы»: «2 сервера» или «нет». */
@Composable
internal fun mcpCount(ui: McpUi): String =
    if (ui.servers.isEmpty()) stringResource(R.string.st_mcp_none) else pluralStringResource(R.plurals.st_mcp_count, ui.servers.size, ui.servers.size)

@Composable
private fun statusText(s: McpStatus) = stringResource(
    when (s) {
        McpStatus.OK -> R.string.st_mcp_ok
        McpStatus.ERROR -> R.string.st_mcp_err
        McpStatus.PENDING -> R.string.st_mcp_pending
    },
)

@Composable
private fun statusColor(s: McpStatus) = when (s) {
    McpStatus.OK -> Dsh.colors.ok
    McpStatus.ERROR -> Dsh.colors.danger
    McpStatus.PENDING -> Dsh.colors.warn
}

/**
 * Страница `mcp`: баннер `.mcp-note` «Изменения вступят в силу после перезапуска Host» с кнопкой, группа «Серверы»
 * (адрес или команда одной строкой моноширинным, состояние справа), «Добавить сервер» и пояснение про токены.
 */
@Composable
internal fun McpPage(ui: McpUi, a: SettingsActions, toast: ToastState, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var restarting by remember { mutableStateOf(false) }
    val restarted = stringResource(R.string.st_mcp_restarted)
    val failed = stringResource(R.string.st_mcp_restart_failed)
    FsView {
        if (ui.restartNeeded || restarting) {
            Row(
                Modifier.padding(bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.warnSoft)
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.st_mcp_restart_note), style = body(14.sp, 19.6.sp), color = c.warn, modifier = Modifier.weight(1f))
                KBtn(
                    stringResource(if (restarting) R.string.st_mcp_restarting else R.string.st_mcp_restart), {
                        restarting = true
                        scope.launch {
                            val ok = a.restartHost()
                            restarting = false
                            toast.show(if (ok) restarted else failed)
                        }
                    },
                    kind = BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.sp, horizontal = 14.dp, enabled = !restarting, busy = restarting,
                )
            }
        }
        GLabel(stringResource(R.string.st_mcp_servers))
        if (ui.servers.isEmpty()) FsEmpty(stringResource(R.string.st_mcp_empty))
        else Gl { ui.servers.forEach { s -> row { McpServerRow(s) { onOpen(s.name) } } } }
        Gl(Modifier.padding(top = 12.dp)) { row { StRow(stringResource(R.string.st_mcp_add), icon = DshGlyphs.Plus, tile = Si.c1, chevron = true, onClick = onAdd) } }
        SubNote(stringResource(R.string.st_mcp_note))
    }
}

/** `.st-row.mcp-row`: подпись — адрес или команда моноширинным 12 sp в одну строку с многоточием. */
@Composable
private fun McpServerRow(s: McpRow, onClick: () -> Unit) {
    val c = Dsh.colors
    val state = statusText(s.status)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).rowClick(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "${s.name}, $state" }
            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SiTile(if (s.http) DshGlyphs.Globe else DshGlyphs.Term, if (s.http) Si.c7 else Si.c6)
        Column(Modifier.weight(1f)) {
            Text(s.name, style = body(15.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.where, style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.sp, lineHeight = 16.sp), color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(state, style = body(14.sp, 18.sp), color = statusColor(s.status), maxLines = 1)
        Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
    }
}

/** Лист `#sheet-mcp`: адрес или команда целиком, токен «••••» и 4 символа, состояние; «Удалить» — со второго нажатия. */
@Composable
internal fun ColumnScope.McpSheet(row: McpRow, a: SettingsActions, toast: ToastState, close: () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var armed by remember(row.name) { mutableStateOf(false) }
    var busy by remember(row.name) { mutableStateOf(false) }
    val removed = stringResource(R.string.st_mcp_removed)
    val failed = stringResource(R.string.st_mcp_remove_failed)
    SheetTitle(row.name)
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (row.http) R.string.st_mcp_url else R.string.st_mcp_cmd), style = body(14.sp, 19.6.sp), color = c.t2)
            Text(row.where, style = TextStyle(fontFamily = DshFonts.mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp), color = c.t1)
        }
        if (row.http) {
            Line()
            KvRow(stringResource(R.string.st_mcp_token), row.tokenTail?.let { "••••$it" } ?: stringResource(R.string.st_mcp_no_token))
        }
        Line()
        KvRow(
            stringResource(R.string.st_mcp_state),
            when (row.status) {
                McpStatus.OK -> stringResource(R.string.st_mcp_state_ok, row.name)
                McpStatus.ERROR -> stringResource(R.string.st_mcp_state_err)
                McpStatus.PENDING -> stringResource(R.string.st_mcp_state_pending)
            },
            error = row.status == McpStatus.ERROR,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(
            stringResource(if (armed) R.string.st_mcp_delete_confirm else R.string.st_mcp_delete), {
                if (!armed) { armed = true; return@KBtn }
                busy = true
                scope.launch {
                    val ok = a.mcpRemove(row.name)
                    busy = false
                    if (ok) { close(); toast.show(removed) } else toast.show(failed)
                }
            },
            Modifier.weight(1f), kind = BtnKind.Ghost, ink = c.danger, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp, enabled = !busy, busy = busy,
        )
        KBtn(stringResource(R.string.st_done), close, Modifier.weight(1.35f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

@Composable
internal fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Dsh.colors.line))

/** Строка `.mcp-kv div`: подпись t2 слева, значение 500 справа (ошибка — красным, обычным начертанием). */
@Composable
internal fun KvRow(label: String, value: String, error: Boolean = false) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = body(14.sp, 19.6.sp), color = c.t2)
        Text(
            value, style = body(14.sp, 19.6.sp, if (error) FontWeight.Normal else FontWeight.Medium), color = if (error) c.danger else c.t1,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Страница «Новый MCP-сервер» (`mcpadd`): «По адресу | Команда на телефоне», имя, адрес и токен скрытым полем с
 * «Показать» — или команда. Ошибка — под полями; «Добавить» доступна, когда всё заполнено верно.
 */
@Composable
internal fun McpAddPage(ui: McpUi, a: SettingsActions, toast: ToastState, onDone: () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var http by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf("") }
    // Токен не сохраняется в состоянии экрана: он не должен попасть в сохранённое состояние активности.
    var token by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val n = name.trim()
    val w = where.trim()
    val error = when {
        n.isNotEmpty() && !NAME.matches(n) -> stringResource(R.string.st_mcp_bad_name)
        n.isNotEmpty() && ui.servers.any { nameKey(it.name) == nameKey(n) } -> stringResource(R.string.st_mcp_taken)
        http && w.isNotEmpty() && !URL.matches(w) -> stringResource(R.string.st_mcp_bad_url)
        else -> ""
    }
    val added = stringResource(R.string.st_mcp_added)
    val failed = stringResource(R.string.st_mcp_add_failed)
    FsView {
        StChips(listOf(stringResource(R.string.st_mcp_by_url), stringResource(R.string.st_mcp_by_cmd)), if (http) 0 else 1, { http = it == 0; where = "" }, Modifier.padding(bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ObField(stringResource(R.string.st_mcp_name), name, { name = it.take(32) }, "github")
            Hint(stringResource(R.string.st_mcp_name_hint))
            if (http) {
                ObField(stringResource(R.string.st_mcp_url), where, { where = it }, "https://example.com/mcp", mono = true, keyboard = KeyboardType.Uri)
                ObField(stringResource(R.string.st_mcp_token_opt), token, { token = it.trim() }, "ghp_…", mono = true, icon = DshGlyphs.Key, secret = !shown, keyboard = KeyboardType.Password) {
                    val label = stringResource(if (shown) R.string.st_mcp_hide else R.string.st_mcp_show)
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { shown = !shown }.semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { Icon(DshGlyphs.Eye, null, tint = if (shown) c.accent else c.t3, modifier = Modifier.size(18.dp)) }
                }
                Hint(stringResource(R.string.st_mcp_token_hint))
            } else {
                ObField(stringResource(R.string.st_mcp_cmd), where, { where = it }, "npx -y @modelcontextprotocol/server-…", mono = true)
                Hint(stringResource(R.string.st_mcp_cmd_hint))
            }
            Text(
                error, style = body(13.sp, 18.sp), color = c.danger,
                modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            KBtn(
                stringResource(R.string.st_mcp_add_go), {
                    busy = true
                    scope.launch {
                        val ok = a.mcpAdd(http, n, w, token.ifEmpty { null })
                        busy = false
                        if (ok) { token = ""; onDone(); toast.show(added) } else toast.show(failed)
                    }
                },
                Modifier.fillMaxWidth(), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp,
                enabled = error.isEmpty() && n.isNotEmpty() && w.isNotEmpty() && !busy, busy = busy,
            )
        }
    }
}

/** `.mcp-hint`: поднята на 6 к своему полю. */
@Composable
private fun Hint(text: String) = Text(text, style = small(12.5.sp, 18.sp), color = Dsh.colors.t3, modifier = Modifier.padding(horizontal = 4.dp).layoutUp(6))

private fun Modifier.layoutUp(dp: Int): Modifier = this.then(
    Modifier.layout { m, cons ->
        val p = m.measure(cons)
        val up = dp.dp.roundToPx()
        layout(p.width, (p.height - up).coerceAtLeast(0)) { p.place(0, -up) }
    },
)

/**
 * `.ob-field`: подпись 13 t2 над полем `.ob-in` 54 dp (фон s1, рамка 1,5 line; в фокусе — акцент и кольцо 4 dp).
 * [secret] прячет текст точками; [trailing] — кнопка «Показать».
 */
@Composable
internal fun ObField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    icon: ImageVector? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    /** Итог проверки (`.ob-field.ok` / `.bad`): рамка зелёная или красная, пока поле не в фокусе; null — обычная. */
    verdict: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Dsh.colors
    var focused by remember { mutableStateOf(false) }
    val rest = when (verdict) { true -> lerp(c.line, c.ok, 0.6f); false -> lerp(c.line, c.danger, 0.6f); null -> c.line }
    val edge by animateColorAsState(if (focused) c.accent else rest, tween(250), label = "mcp-edge")
    val ring by animateColorAsState(if (focused) c.accentSoft else Color.Transparent, tween(250), label = "mcp-ring")
    val shape = RoundedCornerShape(16.dp)
    Column {
        Text(label, style = body(13.sp, 17.sp), color = c.t2, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().drawBehind {
                val w = 4.dp.toPx()
                drawRoundRect(ring, Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), CornerRadius(16.dp.toPx() + w / 2), style = Stroke(w))
            }.height(54.dp).clip(shape).background(c.s1).border(1.5.dp, edge, shape).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = c.t3, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(end = if (trailing == null) 8.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
                val style = if (mono) TextStyle(fontFamily = DshFonts.mono, fontSize = 14.sp, color = c.t1) else body(15.sp).copy(color = c.t1)
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value, onChange, singleLine = true, textStyle = style, cursorBrush = SolidColor(c.accent),
                    visualTransformation = if (secret) PasswordVisualTransformation('•') else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused },
                )
            }
            trailing?.invoke()
        }
    }
}
