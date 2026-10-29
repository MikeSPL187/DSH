package com.dsh.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.kit.BtnKind
import com.dsh.core.designsystem.kit.FsView
import com.dsh.core.designsystem.kit.Gl
import com.dsh.core.designsystem.kit.KBtn
import com.dsh.core.designsystem.kit.SheetTitle
import com.dsh.core.designsystem.kit.Si
import com.dsh.core.designsystem.kit.Spinner
import com.dsh.core.designsystem.kit.StRow
import com.dsh.core.designsystem.kit.ToastState
import com.dsh.core.designsystem.kit.body
import com.dsh.core.designsystem.kit.small
import com.dsh.feature.settings.R
import com.dsh.feature.settings.logic.ProviderCatalog
import kotlinx.coroutines.launch

/**
 * Провайдеры моделей по API-ключу (Claude, OpenAI, Gemini, OpenRouter): выбор, ключ с проверкой у провайдера,
 * лист подключённого — «Заменить ключ» и «Удалить ключ». Вход по подписке не предлагается нигде: он запрещён
 * правилами провайдеров в сторонних приложениях ([ProviderCatalog]).
 */

/** Цвет плитки и описание провайдера каталога (`PCAT` прототипа). */
private fun tileOf(id: String): Color = when (id) {
    "anthropic" -> Si.c2
    "openai" -> Si.c4
    "google" -> Si.c3
    "openrouter" -> Si.c7
    else -> Si.c6
}

@Composable
private fun descOf(id: String): String = stringResource(
    when (id) {
        "anthropic" -> R.string.st_pv_desc_anthropic
        "openai" -> R.string.st_pv_desc_openai
        "google" -> R.string.st_pv_desc_google
        else -> R.string.st_pv_desc_openrouter
    },
)

@Composable
private fun leadOf(id: String): String = stringResource(
    when (id) {
        "anthropic" -> R.string.st_pv_lead_anthropic
        "openai" -> R.string.st_pv_lead_openai
        "google" -> R.string.st_pv_lead_google
        else -> R.string.st_pv_lead_openrouter
    },
)

/** Почему не подходит токен подписки — по провайдеру. */
@Composable
private fun subscriptionWhy(id: String): String = stringResource(
    when (id) {
        "anthropic" -> R.string.st_pv_sub_anthropic
        "openai" -> R.string.st_pv_sub_openai
        "google" -> R.string.st_pv_sub_google
        else -> R.string.st_pv_sub_other
    },
)

/** `.si.pv-l`: буква бренда в цветной плитке 30 dp — крупная и жирная (контраст 3:1 для крупного текста). */
@Composable
internal fun ProviderTile(name: String, id: String) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tileOf(id)), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = androidx.compose.ui.text.TextStyle(fontFamily = DshFonts.display, fontWeight = FontWeight.Bold, fontSize = 19.sp), color = Color.White)
    }
}

/** «Добавить провайдера» (`provadd`): четыре провайдера по API-ключу, свой адрес и плашка «Только API-ключи». */
@Composable
internal fun ProviderAddPage(ui: SettingsUi, onPick: (String) -> Unit, onCustom: () -> Unit) {
    val c = Dsh.colors
    FsView {
        Gl {
            ProviderCatalog.all.forEach { spec ->
                row {
                    val on = ui.customProviders.any { it.id == spec.id && it.catalog }
                    StRow(
                        spec.name, sub = descOf(spec.id), leading = { ProviderTile(spec.name, spec.id) },
                        value = if (on) stringResource(R.string.st_pv_connected) else null, chevron = true, onClick = { onPick(spec.id) },
                    )
                }
            }
            row { StRow(stringResource(R.string.st_custom), icon = DshGlyphs.Server, tile = Si.c6, sub = stringResource(R.string.st_pv_custom_sub), chevron = true, onClick = onCustom) }
        }
        // `.pv-safe`
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(DshGlyphs.Shield, null, tint = c.ok, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Column {
                Text(stringResource(R.string.st_pv_safe_title), style = body(14.sp, 20.sp, FontWeight.SemiBold), color = c.t1)
                Text(stringResource(R.string.st_pv_safe_body), style = small(13.sp, 19.5.sp), color = c.t2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * Ключ провайдера (`provkey`): лид, поле с точками, «Показать» и «Вставить» (буфер после вставки очищается),
 * проверка у провайдера с паузой 400 мс, токен подписки — сразу отказ с объяснением; «Где взять ключ»; «Подключить».
 */
@Composable
internal fun ProviderKeyPage(id: String, a: SettingsActions, toast: ToastState, initialKey: String = "", onDone: () -> Unit) {
    val c = Dsh.colors
    val spec = ProviderCatalog.spec(id) ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Ключ не сохраняется в состоянии экрана: он не должен попасть в сохранённое состояние активности.
    var key by remember(id) { mutableStateOf(initialKey) }
    var shown by remember(id) { mutableStateOf(false) }
    var state by remember(id) { mutableStateOf<KeyState?>(null) }
    var models by remember(id) { mutableStateOf<Int?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var saving by remember(id) { mutableStateOf(false) }
    val issue = ProviderCatalog.issue(id, key)
    LaunchedEffect(key) {
        state = null; models = null
        if (key.isBlank() || ProviderCatalog.issue(id, key) != null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        busy = true
        val (s, n) = a.checkProviderKey(id, key.trim())
        state = s; models = n
        busy = false
    }
    val connected = stringResource(R.string.st_pv_added, spec.name)
    val failed = stringResource(R.string.st_pv_add_failed)
    FsView {
        Text(leadOf(id), style = body(14.5.sp, 21.75.sp), color = c.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 16.dp))
        ObField(
            stringResource(R.string.st_key_field), key, { key = it }, spec.placeholder, mono = true, icon = DshGlyphs.Key, secret = !shown,
            keyboard = KeyboardType.Password,
            verdict = when { issue != null || state == KeyState.Bad -> false; state == KeyState.Ok -> true; else -> null },
        ) {
            val label = stringResource(if (shown) R.string.st_key_hide else R.string.st_key_show)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { shown = !shown }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.Eye, null, tint = if (shown) c.accent else c.t3, modifier = Modifier.size(18.dp)) }
            KBtn(stringResource(R.string.st_key_paste), {
                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { key = it; clipboard.setText(AnnotatedString("")) }
            }, height = 40.dp, radius = 12.dp, fontSize = 14.sp, horizontal = 14.dp, ink = c.accent)
        }
        // `#pvState`: состояние ключа; ошибка формата и токен подписки — до обращения к провайдеру.
        val (msg, color) = when {
            issue == ProviderCatalog.Issue.SUBSCRIPTION -> subscriptionWhy(id) to c.danger
            issue == ProviderCatalog.Issue.FORMAT -> stringResource(R.string.st_pv_format, spec.name, spec.keyPrefixes.first()) to c.danger
            busy -> stringResource(R.string.st_pv_checking, spec.by) to c.t3
            state == KeyState.Ok -> (models?.let { pluralStringResource(R.plurals.st_pv_works, it, it) } ?: stringResource(R.string.st_key_works)) to c.ok
            state == KeyState.Bad -> stringResource(R.string.st_key_rejected) to c.danger
            state == KeyState.Offline -> stringResource(R.string.st_key_noconn) to c.warn
            else -> stringResource(R.string.st_pv_safe_store) to c.t3
        }
        Row(
            Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                if (busy) Spinner(color, 13.dp)
                else Icon(
                    when { color == c.danger -> DshGlyphs.Alert; state == KeyState.Ok -> DshGlyphs.Check; else -> DshGlyphs.Lock },
                    null, tint = color, modifier = Modifier.size(15.dp),
                )
            }
            Text(msg, style = small(13.sp, 18.85.sp), color = color)
        }
        Gl(Modifier.padding(top = 16.dp)) {
            row { StRow(stringResource(R.string.st_pv_where), icon = DshGlyphs.Link, tile = Si.c6, sub = spec.site, chevron = true, onClick = { a.openProviderSite(id) }) }
        }
        KBtn(
            stringResource(R.string.st_pv_connect), {
                saving = true
                scope.launch {
                    val ok = a.addCatalogProvider(id, key.trim())
                    saving = false
                    if (ok) { key = ""; toast.show(connected); onDone() } else toast.show(failed)
                }
            },
            Modifier.padding(top = 16.dp).fillMaxWidth(), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp,
            enabled = issue == null && state == KeyState.Ok && !busy && !saving, busy = saving,
        )
    }
}

/** Лист подключённого провайдера (`#sheet-prov`): ключ «••••» и 4 символа, модели, оплата; «Удалить ключ» — со второго нажатия. */
@Composable
internal fun ColumnScope.ProviderSheet(row: ProviderRow, a: SettingsActions, toast: ToastState, onReplace: (() -> Unit)?, close: () -> Unit) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    var armed by remember(row.id) { mutableStateOf(false) }
    var busy by remember(row.id) { mutableStateOf(false) }
    val removed = stringResource(R.string.st_pv_removed, row.name)
    val failed = stringResource(R.string.st_pv_remove_failed)
    val spec = ProviderCatalog.spec(row.id)
    SheetTitle(row.name)
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.s2).padding(horizontal = 14.dp, vertical = 2.dp)) {
        KvRow(stringResource(R.string.st_pv_key), row.last4?.let { "••••$it" } ?: stringResource(R.string.st_key_none))
        row.models?.let { Line(); KvRow(stringResource(R.string.st_pv_models), "$it") }
        Line()
        KvRow(stringResource(R.string.st_pv_billing), spec?.site ?: row.sub)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(
            stringResource(if (armed) R.string.st_pv_delete_confirm else R.string.st_pv_delete), {
                if (!armed) { armed = true; return@KBtn }
                busy = true
                scope.launch {
                    val ok = a.removeProvider(row.id)
                    busy = false
                    if (ok) { close(); toast.show(removed) } else toast.show(failed)
                }
            },
            Modifier.weight(1f), kind = BtnKind.Ghost, ink = c.danger, height = 52.dp, radius = 16.dp, fontSize = 16.sp, horizontal = 10.dp, enabled = !busy, busy = busy,
        )
        if (onReplace != null) KBtn(stringResource(R.string.st_pv_replace), onReplace, Modifier.weight(1.2f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
        else KBtn(stringResource(R.string.st_done), close, Modifier.weight(1.2f), kind = BtnKind.Primary, height = 52.dp, radius = 16.dp, fontSize = 16.sp)
    }
}

/** Строка подключённого провайдера на «Моделях и ключах». */
@Composable
internal fun ProviderListRow(p: ProviderRow, onClick: () -> Unit) {
    val c = Dsh.colors
    val sub = buildString {
        append(p.last4?.let { stringResource(R.string.st_pv_key_dots, it) } ?: p.sub)
        p.models?.let { append(" · "); append(pluralStringResource(R.plurals.st_pv_models_n, it, it)) }
    }
    StRow(
        p.name, sub = sub,
        leading = if (p.catalog) ({ ProviderTile(p.name, p.id) }) else null, icon = if (p.catalog) null else DshGlyphs.Server, tile = Si.c2,
        value = when (p.ok) { true -> stringResource(R.string.st_key_ok); false -> stringResource(R.string.st_key_bad); null -> null },
        valueColor = if (p.ok == false) c.danger else c.ok, chevron = true, onClick = onClick,
    )
}
