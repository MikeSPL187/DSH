package com.deepseek.dsh.feature.settings.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.clickable
import com.deepseek.dsh.core.designsystem.pressScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dsh.core.designsystem.Dsh
import com.deepseek.dsh.core.designsystem.DshGlyphs
import com.deepseek.dsh.feature.settings.R
import kotlinx.coroutines.launch

/** Режим инструмента: «Сразу», «Спросит», «Выкл.». */
enum class ToolMode { Auto, Ask, Off }

@Immutable
data class PluginTool(val name: String, val description: String?, val mode: ToolMode)

/** Строка «Доступ»: иконка из прототипа (`folder`, `bell`, `globe`, `copy`…), название, значение. */
@Immutable
data class PluginPerm(val icon: String, val name: String, val value: String, val granted: Boolean, val note: String? = null)

/** Настройка плагина с вариантами (enum из Config-схемы) или переключатель ([options] пуст). */
@Immutable
data class PluginSetting(val key: String, val name: String, val options: List<String>, val selected: Int, val toggle: Boolean? = null)

/** Запись журнала вызовов. [status]: `ok`, `err`, `ask`. */
@Immutable
data class PluginCall(val tool: String, val time: String, val duration: String, val status: String, val result: String)

@Immutable
data class PluginUi(
    val id: String,
    val name: String,
    val letter: String,
    val hue: Float,
    val native: Boolean,
    val version: String,
    val on: Boolean,
    val blocked: Boolean = false,
    val update: String? = null,
    val changelog: String? = null,
    val by: String? = null,
    val size: String? = null,
    val description: String = "",
    val compat: String? = null,
    val compatible: Boolean = true,
    val tools: List<PluginTool> = emptyList(),
    val perms: List<PluginPerm> = emptyList(),
    val settings: List<PluginSetting> = emptyList(),
    val pkg: String? = null,
    val cert: String? = null,
    val certWas: String? = null,
    val trustedAt: String? = null,
    val hasUi: Boolean = false,
    val calls: Int = 0,
    val errors: Int = 0,
    /** Последний вызов: (время, день) — «10:52», «сегодня». */
    val last: Pair<String, String>? = null,
    val log: List<PluginCall> = emptyList(),
    val removable: Boolean = true,
    val error: String? = null,
    /** Можно ли перезапустить/открыть исходники (плагин Host с файлами на устройстве). */
    val hasSource: Boolean = false,
) {
    val asks: Int get() = tools.count { it.mode == ToolMode.Ask }
}

@Immutable
data class CatalogUi(val id: String, val name: String, val letter: String, val hue: Float, val description: String)

/** Кандидат на доверие (нативный плагин, лист `#sheet-trust`). */
@Immutable
data class TrustCandidate(
    val id: String,
    val name: String,
    val letter: String,
    val hue: Float,
    val version: String,
    val pkg: String,
    val publisher: String?,
    val cert: String,
    val perms: List<PluginPerm>,
    val changed: Boolean = false,
)

/** Действия «Плагинов». */
interface PluginsActions {
    fun toggle(id: String, on: Boolean) {}
    fun setToolMode(id: String, tool: String, mode: ToolMode) {}
    fun setSetting(id: String, key: String, value: Int) {}
    fun setSettingToggle(id: String, key: String, on: Boolean) {}
    fun open(id: String) {}
    fun ask(id: String) {}
    fun fixWithAgent(id: String, call: PluginCall?) {}
    fun openSource(id: String) {}
    suspend fun restart(id: String): Boolean = true
    suspend fun update(id: String): Boolean = true
    suspend fun updateAll(): List<String> = emptyList()
    suspend fun uninstall(id: String): Boolean = true
    suspend fun install(catalogId: String): Boolean = true
    suspend fun installFromLink(link: String): Boolean = true
    fun installFromFile() {}
    fun createWithAgent() {}
    fun trust(candidateId: String) {}
    fun dismissTrust(candidateId: String) {}
}

private object NoPluginActions : PluginsActions

private sealed interface PlPage {
    data object List : PlPage
    data class Detail(val id: String) : PlPage
    data class Log(val id: String) : PlPage
}

/**
 * «Плагины» по `#screen-plugins` прототипа: поиск, фильтры, «Создать с агентом»,
 * заблокированные, установленные, рекомендуемые; карточка плагина и журнал вызовов;
 * листы «Установить плагин», «По ссылке» и «Доверять плагину?».
 */
@Composable
fun PluginsHomeScreen(
    plugins: List<PluginUi>,
    catalog: List<CatalogUi>,
    onClose: () -> Unit,
    actions: PluginsActions = NoPluginActions,
    trust: TrustCandidate? = null,
    initialPlugin: String? = null,
    initialLog: Boolean = false,
    initialSheet: String? = null,
    toast: ToastState = rememberToast(),
    /** Собственный раздел встроенного плагина (например, подготовка модели голосового ввода). */
    extraSection: (@Composable (pluginId: String) -> Unit)? = null,
) {
    val stack = remember {
        PageStack<PlPage>(PlPage.List).also {
            if (initialPlugin != null) it.push(PlPage.Detail(initialPlugin))
            if (initialPlugin != null && initialLog) it.push(PlPage.Log(initialPlugin))
        }
    }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var rise by remember { mutableStateOf(true) }
    var fresh by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(900); rise = false }
    // Плагин удалили — уходим со страницы, которой больше нет.
    LaunchedEffect(plugins.map { it.id }) {
        val top = stack.top
        val id = (top as? PlPage.Detail)?.id ?: (top as? PlPage.Log)?.id
        if (id != null && plugins.none { it.id == id }) stack.resetTo(PlPage.List)
    }
    val enabled = plugins.count { it.on && !it.blocked }
    val updates = plugins.count { it.update != null }
    DshStackScreen(
        stack = stack,
        onClose = onClose,
        sheetOpen = sheet != null || trust != null,
        bar = { p, back ->
            val pl = when (p) { is PlPage.Detail -> plugins.find { it.id == p.id }; is PlPage.Log -> plugins.find { it.id == p.id }; else -> null }
            val (title, sub) = when (p) {
                PlPage.List -> stringResource(R.string.pl_title) to (pluralStringResource(R.plurals.pl_on, enabled, enabled) + if (updates > 0) " · " + pluralStringResource(R.plurals.pl_updates, updates, updates) else "")
                is PlPage.Log -> stringResource(R.string.pl_log_title) to pl?.name
                is PlPage.Detail -> (pl?.name ?: "") to pl?.let { stringResource(if (it.native) R.string.pl_kind_native_full else R.string.pl_kind_host_full) + " · " + it.version }
            }
            SBar(title, sub, back, backDescription = stringResource(R.string.st_back)) {
                if (p == PlPage.List) IconBtn(DshGlyphs.Plus, stringResource(R.string.pl_install)) { sheet = "add" }
                else Spacer(Modifier.size(44.dp))
            }
        },
        overlay = {
            ToastHost(toast)
            KSheet(sheet == "add", onDismiss = { sheet = null }) { AddSheet(onFile = { sheet = null; actions.installFromFile() }, onLink = { sheet = "link" }, onCreate = { sheet = null; actions.createWithAgent() }) }
            KSheet(sheet == "link", onDismiss = { sheet = null }) { LinkSheet(actions, toast) { sheet = null } }
            KSheet(trust != null, onDismiss = { trust?.let { actions.dismissTrust(it.id) } }) {
                trust?.let { t -> PluginTrustSheet(t, onCancel = { actions.dismissTrust(t.id) }, onTrust = { actions.trust(t.id); fresh = t.id }) }
            }
        },
    ) { p ->
        CompositionLocalProvider(LocalRise provides (rise && p == PlPage.List)) {
            when (p) {
                PlPage.List -> PluginList(plugins, catalog, actions, toast, fresh, onFresh = { fresh = it }, open = { stack.push(PlPage.Detail(it)) }, onAdd = { sheet = "add" })
                is PlPage.Detail -> plugins.find { it.id == p.id }?.let { PluginDetail(it, actions, toast, onLog = { stack.push(PlPage.Log(it.id)) }, onReinstall = { sheet = "add" }, extra = extraSection) }
                is PlPage.Log -> plugins.find { it.id == p.id }?.let { PluginLog(it, actions) }
            }
        }
    }
}

// ---------- Список ----------

@Composable
private fun PluginList(
    plugins: List<PluginUi>,
    catalog: List<CatalogUi>,
    a: PluginsActions,
    toast: ToastState,
    fresh: String?,
    onFresh: (String) -> Unit,
    open: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var updatingAll by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    fun matches(p: PluginUi): Boolean {
        val f = when (filter) { "on" -> p.on && !p.blocked; "host" -> !p.native; "apk" -> p.native; else -> true }
        return f && (q.isEmpty() || (p.name + " " + p.tools.joinToString(" ") { it.name }).lowercase().contains(q))
    }
    val blocked = plugins.filter { it.blocked }
    val ok = plugins.filter { !it.blocked && matches(it) }
    val upd = plugins.filter { it.update != null }
    val updatedMsg = stringResource(R.string.pl_updated_all)
    FsView {
        FsSearch(query, { query = it }, stringResource(R.string.pl_search), Modifier.padding(bottom = 10.dp).rise(0))
        Filters(
            listOf(
                Triple("all", stringResource(R.string.pl_f_all), plugins.size),
                Triple("on", stringResource(R.string.pl_f_on), plugins.count { it.on && !it.blocked }),
                Triple("host", "Host", plugins.count { !it.native }),
                Triple("apk", stringResource(R.string.pl_f_native), plugins.count { it.native }),
            ),
            filter, { filter = it }, Modifier.rise(1),
        )
        if (q.isEmpty()) CreateCard(Modifier.padding(top = 10.dp).rise(2), onClick = a::createWithAgent)
        if (blocked.isNotEmpty() && q.isEmpty()) {
            FsSec(stringResource(R.string.pl_attention), Modifier.rise(3))
            Column(Modifier.rise(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blocked.forEach { p ->
                    AlertCard(
                        stringResource(R.string.pl_blocked_title, p.name), stringResource(R.string.pl_blocked_sub),
                        Modifier.rowClickShape(RoundedCornerShape(20.dp)) { open(p.id) },
                    ) { Icon(DshGlyphs.Right, null, tint = Dsh.colors.danger, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        FsSec(
            stringResource(R.string.pl_installed), Modifier.rise(5),
            count = ok.size.toString(),
            trailing = if (upd.isNotEmpty() && q.isEmpty()) ({
                Row(
                    Modifier.touchOverflow().rowClickShape(RoundedCornerShape(10.dp)) {
                        if (!updatingAll) {
                            updatingAll = true
                            scope.launch { val names = a.updateAll(); updatingAll = false; if (names.isNotEmpty()) toast.show(updatedMsg.format(names.joinToString(", "))) }
                        }
                    }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (updatingAll) Spinner(Dsh.colors.accent, 12.dp)
                    Text(
                        if (updatingAll) stringResource(R.string.pl_updating) else stringResource(R.string.pl_update_all, upd.size),
                        style = small(13.sp, 16.sp, FontWeight.Medium), color = Dsh.colors.accent,
                    )
                }
            }) else null,
        )
        if (ok.isNotEmpty()) {
            Gl(Modifier.rise(6)) { ok.forEach { p -> row { PluginRow(p, p.id == fresh, a, toast) { open(p.id) } } } }
        } else if (plugins.isEmpty() && q.isEmpty()) {
            EmptyInstalled(onAdd)
        } else {
            FsEmpty(
                if (q.isNotEmpty()) stringResource(R.string.pl_nothing_q, query.trim()) else stringResource(R.string.pl_nothing),
                stringResource(R.string.pl_nothing_hint),
            )
        }
        val rec = catalog.filter { c -> plugins.none { it.id == c.id } }
        if (rec.isNotEmpty() && q.isEmpty()) {
            FsSec(stringResource(R.string.pl_recommended), Modifier.rise(7))
            Row(
                Modifier.rise(8).padding(horizontal = 0.dp).fillMaxWidth().horizontalScrollEdge().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { rec.forEach { c -> CatalogCard(c, a, toast, onInstalled = onFresh) } }
        }
        Text(
            stringResource(R.string.pl_hint),
            style = small(12.5.sp, 18.sp), color = Dsh.colors.t3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp).rise(9),
        )
    }
}

/** Горизонтальная лента на всю ширину экрана (`margin: 0 -16px; padding: 0 16px`). */
@Composable
private fun Modifier.horizontalScrollEdge(): Modifier = this
    .layoutBleed(16.dp)
    .horizontalScroll(rememberScrollState())
    .padding(horizontal = 16.dp)

/** Выйти за поля страницы на [bleed] с каждой стороны. */
private fun Modifier.layoutBleed(bleed: androidx.compose.ui.unit.Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = bleed.roundToPx() * 2
        val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
    },
)

@Composable
private fun Modifier.rowClickShape(shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier = clip(shape).rowClick(onClick = onClick)

@Composable
private fun Filters(items: List<Triple<String, String, Int>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().horizontalScrollEdge().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (k, n, count) ->
            val on = k == selected
            val c = Dsh.colors
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(if (on) c.t1 else c.s1)
                    .then(if (on) Modifier else Modifier.border(1.dp, c.line, CircleShape))
                    .selectable(on, role = Role.Tab) { onSelect(k) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(n, style = body(13.5.sp, 17.sp), color = if (on) c.bg else c.t2)
                Text(count.toString(), style = small(12.sp, 15.sp), color = if (on) c.bg.copy(alpha = 0.7f) else c.t3)
            }
        }
    }
}

/** «Создать плагин с агентом» `.pl-create`. */
@Composable
private fun CreateCard(modifier: Modifier, onClick: () -> Unit) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().pressScale(interaction, 0.98f).clip(shape).background(accentCardBrush(0.4f))
            .border(1.dp, mix(c.accent, 0.25f, c.line), shape)
            .clickable(interaction, androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick).padding(17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = c.accent, spotColor = c.accent).clip(RoundedCornerShape(14.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Spark, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.pl_create), style = body(15.5.sp, 20.sp, FontWeight.SemiBold), color = c.t1)
            Text(stringResource(R.string.pl_create_sub), style = small(13.sp, 18.sp), color = c.t2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Красная карточка `.pl-alert`. */
@Composable
private fun AlertCard(title: String, sub: String, modifier: Modifier = Modifier, top: Boolean = false, subColor: Color = Dsh.colors.danger, extra: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Dsh.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.dangerSoft).border(1.dp, c.danger.copy(alpha = 0.25f), shape).padding(15.dp),
        verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Gic(DshGlyphs.Shield, Tone.Red)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1)
            Text(sub, style = small(13.sp, if (top) 19.sp else 17.sp), color = subColor, modifier = Modifier.padding(top = 2.dp))
            extra?.invoke()
        }
        trailing?.invoke()
    }
}

/** Метка вида `.kind`: HOST или APK. */
@Composable
private fun KindTag(native: Boolean) {
    val c = Dsh.colors
    Text(
        if (native) "APK" else "HOST",
        style = small(10.5.sp, 13.sp, FontWeight.SemiBold).copy(letterSpacing = 0.02.sp * 10.5f),
        color = if (native) mix(c.accent, 0.8f, c.t1) else c.t2,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (native) c.accentSoft else c.s2).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Строка плагина `.plr`: иконка, имя с меткой, подпись и переключатель. */
@Composable
private fun PluginRow(p: PluginUi, fresh: Boolean, a: PluginsActions, toast: ToastState, onClick: () -> Unit) {
    val c = Dsh.colors
    val bg = remember { Animatable(if (fresh) c.accentSoft else Color.Transparent) }
    LaunchedEffect(fresh) { if (fresh) { bg.snapTo(c.accentSoft); bg.animateTo(Color.Transparent, tween(1_600)) } }
    val onMsg = stringResource(R.string.pl_turned_on, p.name)
    val offMsg = stringResource(R.string.pl_turned_off, p.name)
    Row(
        Modifier.fillMaxWidth().background(bg.value).heightIn(min = 64.dp).rowClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlIc(p.letter, p.hue, off = !p.on || p.blocked)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = body(15.sp, 19.5.sp, FontWeight.Medium), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                KindTag(p.native)
            }
            RowSub(p)
        }
        if (p.blocked) Icon(DshGlyphs.Right, null, tint = c.t3, modifier = Modifier.size(18.dp))
        else Tsw(p.on, { on -> a.toggle(p.id, on); toast.show(if (on) onMsg else offMsg) }, stringResource(R.string.pl_enable, p.name))
    }
}

@Composable
private fun RowSub(p: PluginUi) {
    val c = Dsh.colors
    val style = small(12.5.sp, 16.sp)
    when {
        p.blocked -> Text(stringResource(R.string.pl_sub_blocked), style = style, color = c.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.update != null -> Text(stringResource(R.string.pl_sub_update, p.update), style = style, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.error != null -> Text(p.error, style = style, color = c.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
        !p.on -> Text(stringResource(R.string.pl_sub_off, p.version), style = style, color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> {
            val tools = if (p.tools.isEmpty()) stringResource(R.string.pl_sub_ready) else pluralStringResource(R.plurals.pl_tools, p.tools.size, p.tools.size)
            val asks = if (p.asks > 0) " · " + stringResource(R.string.pl_sub_asks, p.asks) else ""
            val errs = if (p.errors > 0) pluralStringResource(R.plurals.pl_errors, p.errors, p.errors) else null
            Text(
                buildAnnotatedString {
                    append(tools + asks)
                    if (errs != null) { append(" · "); withStyle(SpanStyle(color = c.danger)) { append(errs) } }
                },
                style = style, color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyInstalled(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(Dsh.colors.s2), contentAlignment = Alignment.Center) {
            Icon(DshGlyphs.Puzzle, null, tint = Dsh.colors.t3, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(R.string.pl_empty), style = body(15.sp, 20.sp, FontWeight.Medium), color = Dsh.colors.t1, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.pl_empty_sub), style = small(13.sp, 18.sp), color = Dsh.colors.t3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        KBtn(stringResource(R.string.pl_install), onAdd, Modifier.padding(top = 14.dp), kind = BtnKind.Ghost, icon = DshGlyphs.Plus, height = 40.dp, radius = 12.dp, fontSize = 14.sp, ink = Dsh.colors.accent)
    }
}

/** Карточка каталога `.cat-card` 168 dp. */
@Composable
private fun CatalogCard(c: CatalogUi, a: PluginsActions, toast: ToastState, onInstalled: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val done = stringResource(R.string.pl_installed_toast, c.name)
    val failed = stringResource(R.string.pl_install_failed, c.name)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.width(168.dp).clip(shape).background(Dsh.colors.s1).border(1.dp, Dsh.colors.line, shape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlIc(c.letter, c.hue)
        Spacer(Modifier.height(4.dp))
        Text(c.name, style = body(15.sp, 19.5.sp, FontWeight.Medium), color = Dsh.colors.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.description, style = small(12.5.sp, 17.5.sp), color = Dsh.colors.t2, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 52.dp))
        KBtn(
            if (busy) "" else stringResource(R.string.pl_install_short),
            { busy = true; scope.launch { val ok = a.install(c.id); busy = false; if (ok) { onInstalled(c.id); toast.show(done) } else toast.show(failed) } },
            Modifier.fillMaxWidth().padding(top = 10.dp), height = 40.dp, radius = 12.dp, fontSize = 14.sp, ink = Dsh.colors.accent, busy = busy,
        )
    }
}

// ---------- Карточка плагина ----------

@Composable
private fun PluginDetail(p: PluginUi, a: PluginsActions, toast: ToastState, onLog: () -> Unit, onReinstall: () -> Unit, extra: (@Composable (String) -> Unit)? = null) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val arm = rememberArm()
    var updating by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val onMsg = stringResource(R.string.pl_turned_on, p.name)
    val offMsg = stringResource(R.string.pl_turned_off, p.name)
    val removed = stringResource(R.string.pl_removed, p.name)
    val updatedMsg = stringResource(R.string.pl_updated, p.name, p.update ?: "")
    val restartedMsg = stringResource(R.string.pl_restarted)
    val failedMsg = stringResource(R.string.pl_failed)
    FsView {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlIc(p.letter, p.hue, big = true, off = !p.on || p.blocked)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = display(21.sp, 26.sp), color = c.t1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val kind = stringResource(if (p.native) R.string.pl_kind_native else R.string.pl_kind_host)
                Text(listOfNotNull(p.by, kind, p.size).joinToString(" · "), style = small(13.sp, 17.sp), color = c.t3, modifier = Modifier.padding(top = 2.dp))
            }
            if (!p.blocked) Tsw(p.on, { on -> a.toggle(p.id, on); toast.show(if (on) onMsg else offMsg) }, stringResource(R.string.pl_enable, p.name))
        }
        if (p.description.isNotBlank()) Text(p.description, style = body(14.5.sp, 21.75.sp), color = c.t2, modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp))
        if (p.blocked) {
            AlertCard(
                stringResource(R.string.pl_sig_changed), stringResource(R.string.pl_sig_changed_text, p.trustedAt ?: "—"),
                Modifier.padding(top = 14.dp), top = true, subColor = c.t2,
                extra = {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KBtn(stringResource(R.string.pl_delete_short), { scope.launch { if (a.uninstall(p.id)) toast.show(removed) } }, Modifier.weight(1f), kind = BtnKind.Surface, height = 42.dp, radius = 12.dp, fontSize = 14.sp, ink = c.danger, horizontal = 4.dp)
                        KBtn(stringResource(R.string.pl_reinstall), onReinstall, Modifier.weight(1f), kind = BtnKind.Surface, height = 42.dp, radius = 12.dp, fontSize = 14.sp, horizontal = 4.dp)
                    }
                },
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.hasUi) Quick(DshGlyphs.Play, stringResource(R.string.pl_q_open), Modifier.weight(1f)) { a.open(p.id) }
                Quick(DshGlyphs.Spark, stringResource(R.string.pl_q_ask), Modifier.weight(1f)) { a.ask(p.id) }
                Quick(DshGlyphs.Clock, stringResource(R.string.pl_q_log), Modifier.weight(1f), onClick = onLog)
                if (!p.hasUi && p.hasSource) Quick(DshGlyphs.Code, stringResource(R.string.pl_q_code), Modifier.weight(1f)) { a.openSource(p.id) }
            }
            StatRow(
                listOf(
                    Triple(p.calls.toString(), pluralStringResource(R.plurals.pl_calls_week, p.calls), false),
                    Triple(p.errors.toString(), pluralStringResource(R.plurals.pl_errors_word, p.errors), p.errors > 0),
                    Triple(p.last?.first ?: "—", p.last?.let { stringResource(R.string.pl_last, it.second) } ?: stringResource(R.string.pl_never_called), false),
                ),
                Modifier.padding(top = 10.dp), valueSize = 18.sp, padV = 11.dp,
            )
        }
        if (p.update != null) {
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(shape).background(c.s1).border(1.dp, mix(c.accent, 0.3f, c.line), shape).padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pl_update_n, p.update), style = body(14.sp, 19.6.sp, FontWeight.SemiBold), color = c.t1)
                    if (!p.changelog.isNullOrBlank()) Text(p.changelog, style = small(12.5.sp, 17.5.sp), color = c.t2)
                }
                KBtn(
                    if (updating) "" else stringResource(R.string.pl_update), {
                        updating = true
                        scope.launch { val ok = a.update(p.id); updating = false; toast.show(if (ok) updatedMsg else failedMsg) }
                    },
                    kind = BtnKind.Primary, height = 40.dp, radius = 12.dp, fontSize = 14.sp, horizontal = 16.dp, busy = updating,
                )
            }
        }
        GLabel(stringResource(R.string.pl_tools))
        if (p.tools.isEmpty()) {
            Gl { row { Gr(stringResource(R.string.pl_tools_none), sub = stringResource(R.string.pl_tools_none_sub)) } }
        } else {
            Gl {
                p.tools.forEach { t ->
                    row {
                        Gr(minHeight = 56.dp, center = {
                            Text(t.name, style = mono(13.5.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!t.description.isNullOrBlank()) Text(t.description, style = small(), color = c.t3)
                        }) { ModeChip(t, enabled = !p.blocked) { a.setToolMode(p.id, t.name, it) } }
                    }
                }
            }
            SubNote(stringResource(R.string.pl_modes_note))
        }
        if (p.settings.isNotEmpty()) {
            GLabel(stringResource(R.string.pl_settings))
            Gl {
                p.settings.forEach { s ->
                    row {
                        if (s.toggle != null) {
                            Gr(s.name, onClick = { a.setSettingToggle(p.id, s.key, !s.toggle) }) { Box(Modifier.padding(start = 0.dp)) { com.deepseek.dsh.core.designsystem.DshSwitch(s.toggle, { a.setSettingToggle(p.id, s.key, it) }, contentDescription = s.name) } }
                        } else {
                            Gr(s.name) { Opts(s.options, s.selected, { k -> a.setSetting(p.id, s.key, k) }) }
                        }
                    }
                }
            }
        }
        if (extra != null && !p.blocked) extra(p.id)
        if (p.perms.isNotEmpty()) {
            GLabel(stringResource(R.string.pl_access))
            Gl {
                p.perms.forEach { perm ->
                    row { Gr(perm.name, sub = perm.note, leading = { Gic(DshGlyphs.get(perm.icon), if (perm.granted) Tone.Ok else Tone.Plain) }, value = perm.value) }
                }
            }
        }
        if (p.native && p.pkg != null) {
            GLabel(stringResource(R.string.pl_signature))
            Gl {
                row { Gr(stringResource(R.string.pl_package)) { Kv(p.pkg) } }
                p.cert?.let { cert -> row { Gr(stringResource(if (p.blocked) R.string.pl_cert_new else R.string.pl_cert)) { Kv(cert, if (p.blocked) c.danger else c.t2) } } }
                if (p.blocked) p.certWas?.let { was -> row { Gr(stringResource(R.string.pl_cert_was)) { Kv(was) } } }
                else p.trustedAt?.let { t -> row { Gr(stringResource(R.string.pl_trusted_at), value = t) } }
            }
        }
        GLabel(stringResource(R.string.pl_about))
        Gl {
            row { Gr(stringResource(R.string.pl_version), value = p.version) }
            if (p.compat != null) row {
                Gr(
                    stringResource(if (p.compatible) R.string.pl_compatible else R.string.pl_incompatible), sub = p.compat,
                    leading = { Gic(if (p.compatible) DshGlyphs.Check else DshGlyphs.Alert, if (p.compatible) Tone.Ok else Tone.Warn) },
                )
            }
        }
        if (!p.native && !p.blocked && p.hasSource) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KBtn(stringResource(R.string.pl_sources), { a.openSource(p.id) }, Modifier.weight(1f), kind = BtnKind.Surface, icon = DshGlyphs.Folder, height = 46.dp, fontSize = 14.sp, horizontal = 6.dp)
                KBtn(
                    stringResource(if (restarting) R.string.pl_restarting else R.string.pl_restart),
                    { restarting = true; scope.launch { val ok = a.restart(p.id); restarting = false; toast.show(if (ok) restartedMsg else failedMsg) } },
                    Modifier.weight(1f), kind = BtnKind.Surface, icon = DshGlyphs.Redo, height = 46.dp, fontSize = 14.sp, busy = restarting, horizontal = 6.dp,
                )
            }
        }
        if (p.removable) {
            KBtn(
                stringResource(if (arm.armed) R.string.pl_delete_arm else R.string.pl_delete),
                { arm.tap { scope.launch { if (a.uninstall(p.id)) toast.show(removed) else toast.show(failedMsg) } } },
                Modifier.fillMaxWidth().padding(top = if (!p.native && !p.blocked && p.hasSource) 8.dp else 20.dp),
                kind = BtnKind.Surface, icon = DshGlyphs.Trash, height = 48.dp, radius = 15.dp, ink = c.danger,
            )
        }
    }
}

@Composable
private fun Kv(text: String, color: Color = Dsh.colors.t2) {
    Text(text, style = mono(12.5.sp, androidx.compose.ui.text.font.FontWeight.Normal), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 210.dp))
}

/** Быстрое действие `.pd-quick button`: 58 dp, иконка акцентом над подписью. */
@Composable
private fun Quick(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.height(58.dp).clip(shape).background(Dsh.colors.s1).border(1.dp, Dsh.colors.line, shape).rowClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = Dsh.colors.accent, modifier = Modifier.size(18.dp))
        Text(label, style = small(12.5.sp, 16.sp, FontWeight.Medium), color = Dsh.colors.t1, maxLines = 1)
    }
}

/** Кнопка режима `.mode`: нажатие переключает «Сразу» → «Спросит» → «Выкл.». */
@Composable
private fun ModeChip(t: PluginTool, enabled: Boolean, onMode: (ToolMode) -> Unit) {
    val c = Dsh.colors
    val (bg, fg, label) = when (t.mode) {
        ToolMode.Auto -> Triple(c.okSoft, c.ok, stringResource(R.string.pl_mode_auto))
        ToolMode.Ask -> Triple(c.warnSoft, c.warn, stringResource(R.string.pl_mode_ask))
        ToolMode.Off -> Triple(c.s2, c.t3, stringResource(R.string.pl_mode_off))
    }
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(t.mode) { scale.snapTo(0.88f); scale.animateTo(1f, com.deepseek.dsh.core.designsystem.DshMotion.lively()) }
    val desc = stringResource(R.string.pl_mode_desc, t.name, label)
    Box(
        Modifier.widthIn(min = 88.dp).height(40.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(10.dp)).background(bg)
            .rowClick(enabled = enabled) { onMode(when (t.mode) { ToolMode.Auto -> ToolMode.Ask; ToolMode.Ask -> ToolMode.Off; ToolMode.Off -> ToolMode.Auto }) }
            .semantics { contentDescription = desc }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = small(13.sp, 16.sp, FontWeight.Medium), color = fg) }
}

// ---------- Журнал ----------

@Composable
private fun PluginLog(p: PluginUi, a: PluginsActions) {
    val c = Dsh.colors
    FsView {
        if (p.log.isEmpty()) {
            FsEmpty(stringResource(R.string.pl_log_empty))
            return@FsView
        }
        FsSec(stringResource(R.string.pl_log_recent), count = p.log.size.toString())
        Gl {
            p.log.forEach { e ->
                row {
                    val (ic, tone) = when (e.status) { "err" -> DshGlyphs.X to Tone.Red; "ask" -> DshGlyphs.Shield to Tone.Warn; else -> DshGlyphs.Check to Tone.Ok }
                    Gr(
                        leading = { Gic(ic, tone) },
                        center = {
                            Text(e.tool, style = mono(13.5.sp), color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.result, style = small(), color = if (e.status == "err") c.danger else c.t3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(e.time, style = body(14.5.sp, 19.sp), color = c.t2)
                            Text(e.duration, style = small(11.5.sp, 15.sp), color = c.t3)
                        }
                    }
                }
            }
        }
        if (p.errors > 0 || p.log.any { it.status == "err" }) {
            KBtn(stringResource(R.string.pl_fix), { a.fixWithAgent(p.id, p.log.firstOrNull { it.status == "err" }) }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = DshGlyphs.Spark, height = 46.dp)
        }
    }
}

// ---------- Листы ----------

@Composable
private fun AddSheet(onFile: () -> Unit, onLink: () -> Unit, onCreate: () -> Unit) {
    SheetTitle(stringResource(R.string.pl_install))
    SheetList {
        row { SheetItem(DshGlyphs.File, stringResource(R.string.pl_from_file), stringResource(R.string.pl_from_file_sub), onClick = onFile) }
        row { SheetItem(DshGlyphs.Link, stringResource(R.string.pl_from_link), stringResource(R.string.pl_from_link_sub), onClick = onLink) }
        row { SheetItem(DshGlyphs.Spark, stringResource(R.string.pl_create_agent), stringResource(R.string.pl_create_agent_sub), iconTint = Dsh.colors.accent, onClick = onCreate) }
    }
}

@Composable
private fun LinkSheet(a: PluginsActions, toast: ToastState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val done = stringResource(R.string.pl_link_done)
    val failed = stringResource(R.string.pl_link_failed)
    SheetTitle(stringResource(R.string.pl_from_link))
    Text(stringResource(R.string.pl_link_lead), style = body(14.5.sp, 21.sp), color = Dsh.colors.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Dsh.colors.s2).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (link.isEmpty()) Text(stringResource(R.string.pl_link_ph), style = body(15.sp), color = Dsh.colors.t3, maxLines = 1)
            BasicTextField(
                link, { link = it }, singleLine = true, textStyle = mono(14.sp, FontWeight.Normal).copy(color = Dsh.colors.t1),
                cursorBrush = SolidColor(Dsh.colors.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "link" },
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.st_cancel), close, Modifier.weight(1f))
        KBtn(
            stringResource(R.string.pl_install_short), {
                busy = true
                scope.launch { val ok = a.installFromLink(link.trim()); busy = false; if (ok) { toast.show(done); close() } else toast.show(failed) }
            },
            Modifier.weight(1f), kind = BtnKind.Primary, enabled = link.isNotBlank(), busy = busy,
        )
    }
}

@Composable
fun PluginTrustSheet(t: TrustCandidate, onCancel: () -> Unit, onTrust: () -> Unit) {
    val c = Dsh.colors
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PlIc(t.letter, t.hue, big = true)
        Column {
            Text(t.name, style = display(19.sp, 24.sp), color = c.t1)
            Text(stringResource(R.string.pl_kind_native_full) + " · " + t.version, style = small(13.sp, 17.sp), color = c.t3)
        }
    }
    if (t.changed) {
        Text(stringResource(R.string.pl_trust_changed), style = body(14.sp, 21.sp, FontWeight.Medium), color = c.danger, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
    }
    Text(stringResource(R.string.pl_trust_lead), style = body(14.sp, 21.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
    Gl {
        row { Gr(stringResource(R.string.pl_package)) { Kv(t.pkg) } }
        t.publisher?.let { pub -> row { Gr(stringResource(R.string.pl_publisher), value = pub) } }
        row { Gr(stringResource(R.string.pl_cert)) { Kv(t.cert) } }
    }
    if (t.perms.isNotEmpty()) {
        GLabel(stringResource(R.string.pl_requests), top = 14.dp)
        Gl {
            t.perms.forEach { perm -> row { Gr(perm.name, sub = perm.note, leading = { Gic(DshGlyphs.get(perm.icon), if (perm.icon == "globe") Tone.Warn else Tone.Plain) }) } }
        }
    }
    IconNote(DshGlyphs.Shield, stringResource(R.string.pl_trust_note), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.st_cancel), onCancel, Modifier.weight(1f))
        KBtn(stringResource(R.string.pl_trust), onTrust, Modifier.weight(1f), kind = BtnKind.Primary)
    }
}

/** Лист доверия поверх любого экрана: нативный плагин найден, пока пользователь был не в «Плагинах». */
@Composable
fun PluginTrustOverlay(t: TrustCandidate, onTrust: () -> Unit, onDismiss: () -> Unit) {
    KSheet(true, onDismiss = onDismiss) { PluginTrustSheet(t, onCancel = onDismiss, onTrust = onTrust) }
}
