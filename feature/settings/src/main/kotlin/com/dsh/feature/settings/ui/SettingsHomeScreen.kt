package com.dsh.feature.settings.ui

import com.dsh.core.designsystem.kit.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.feature.settings.R
import kotlinx.coroutines.launch

/** Страницы «Настроек» (как `PAGES` в прототипе). */
enum class StPage { Root, Models, Look, Agent, Host, Storage, Security, Notif, About }

/** Состояние ключа провайдера. */
enum class KeyState { Ok, Bad, Offline, Unknown, None }

@Immutable
data class ProviderRow(val id: String, val name: String, val sub: String, val ok: Boolean?)

@Immutable
data class RemoteHostRow(val id: String, val name: String, val fingerprint: String, val online: Boolean)

@Immutable
data class TrustedPluginRow(val id: String, val name: String, val letter: String, val hue: Float, val cert: String, val blocked: Boolean)

/** Место на телефоне, байты; null — ещё считается. */
@Immutable
data class StorageUsage(
    val linux: Long = 0,
    val projects: Long = 0,
    val cache: Long = 0,
    val other: Long = 0,
    val logs: Long = 0,
    val device: Long = 0,
) {
    val total: Long get() = linux + projects + cache + other
}

/** Всё, что показывают «Настройки». Значения уже отформатированы там, где это зависит от данных приложения. */
@Immutable
data class SettingsUi(
    val appVersion: String = "",
    val appBuild: Long = 0,
    val hostVersion: String? = null,
    val sdkVersion: String? = null,
    val distro: String? = null,
    val androidVersion: String = "",
    // Профиль и ключ
    val accountName: String? = null,
    val provider: String = "DeepSeek",
    val keyState: KeyState = KeyState.None,
    val keyLast4: String? = null,
    val keyAdded: String? = null,
    val balance: String? = null,
    val today: String? = null,
    val monthSpent: String? = null,
    val monthSpentFraction: Float = 0f,
    val balanceDays: Int? = null,
    val customProviders: List<ProviderRow> = emptyList(),
    val defaultModel: String = "",
    val reasoning: String = "",
    val automationModel: String = "",
    /** Модели, из которых можно выбрать, и названия уровней рассуждения. */
    val models: List<String> = emptyList(),
    val reasoningLevels: List<String> = emptyList(),
    val reasoningIndex: Int = 0,
    val autoModelName: String = "",
    val autoReasoningIndex: Int = 0,
    // Работа
    val pluginsOn: Int = 0,
    val automationsOn: Int = 0,
    /** true — «Сам в рабочей папке», false — «Спрашивать», null — Host не отдал пресеты. */
    val autoApprove: Boolean? = false,
    val clipboard: Boolean = false,
    /** Плагины Host из сети (npm, GitHub, рынок) разрешены (G-48). */
    val thirdPartyPlugins: Boolean = false,
    val workspaceShared: Boolean = false,
    // Оформление
    val theme: String = "system",
    val language: String = "ru",
    val codeSize: Int = 13,
    val haptics: Boolean = true,
    // Host
    val hostRunning: Boolean = false,
    val hostUptime: String? = null,
    val hostMemory: String? = null,
    val hostMemoryFraction: Float = 0f,
    val hostCpu: Int? = null,
    val sleepMinutes: Int = 15,
    val keepBg: Boolean = true,
    val remotes: List<RemoteHostRow> = emptyList(),
    /**
     * Работа с Host на компьютере. Пока клиент не умеет переключать адрес и токен
     * на удалённый Host, раздел скрыт (решение владельца: не показывать неработающее).
     */
    val remoteHostsSupported: Boolean = false,
    val rootfsUpdated: String? = null,
    // Хранилище
    val storage: StorageUsage? = null,
    // Безопасность
    val biometricAvailable: Boolean = false,
    val biometric: Boolean = false,
    val trusted: List<TrustedPluginRow> = emptyList(),
    // Уведомления
    val notifDone: Boolean = true,
    val notifAsk: Boolean = true,
    val notifAuto: String = "err",
    // О приложении
    val updateAvailable: String? = null,
)

/** Действия «Настроек». Пустые реализации — для снимков и предпросмотра. */
interface SettingsActions {
    fun openPlugins() {}
    fun openPlugin(id: String) { openPlugins() }
    fun openAutomation() {}
    fun setDefaultModel(model: String, reasoning: Int) {}
    fun setAutomationModel(model: String, reasoning: Int) {}
    /** Проверка ключа у провайдера до сохранения. */
    suspend fun checkKey(baseUrl: String?, key: String): KeyState = KeyState.Unknown
    /** Сохранить ключ DeepSeek; true — получилось. */
    suspend fun saveKey(key: String): Boolean = false
    suspend fun addProvider(name: String, baseUrl: String, key: String): Boolean = false
    fun manageKeysOnSite() {}
    suspend fun deleteKey(): Boolean = false
    fun setTheme(value: String) {}
    fun setLanguage(tag: String) {}
    fun setCodeSize(size: Int) {}
    fun setHaptics(on: Boolean) {}
    fun setAutoApprove(auto: Boolean) {}
    fun setClipboard(on: Boolean) {}
    fun setThirdPartyPlugins(on: Boolean) {}
    fun setWorkspaceShared(shared: Boolean) {}
    suspend fun restartHost(): Boolean = true
    suspend fun setHostRunning(run: Boolean): Boolean = true
    fun setSleep(minutes: Int) {}
    fun setKeepBg(on: Boolean) {}
    fun pairRemote() {}
    fun openTerminal() {}
    fun shareHostLog() {}
    /** Очистить кэш ("cache") или журналы ("logs"); возвращает освобождённые байты. */
    suspend fun clean(kind: String): Long = 0
    fun exportSessions() {}
    fun restoreBackup() {}
    fun setBiometric(on: Boolean) {}
    suspend fun wipeAll() {}
    fun setNotifDone(on: Boolean) {}
    fun setNotifAsk(on: Boolean) {}
    fun setNotifAuto(value: String) {}
    fun openSystemNotifications() {}
    suspend fun checkUpdates(): String? = null
    fun reportProblem() {}
    fun whatsNew() {}
    fun licenses() {}
}

private object NoActions : SettingsActions

private val PageIcon = mapOf(
    StPage.Models to ("key" to Si.c1), StPage.Look to ("sun" to Si.c8), StPage.Agent to ("spark" to Si.c2),
    StPage.Host to ("server" to Si.c3), StPage.Storage to ("hdd2" to Si.c6), StPage.Security to ("shield" to Si.c5),
    StPage.Notif to ("bell" to Si.c4), StPage.About to ("info" to Si.c6),
)

@Composable
private fun pageTitle(p: StPage): String = stringResource(
    when (p) {
        StPage.Root -> R.string.st_title
        StPage.Models -> R.string.st_models
        StPage.Look -> R.string.st_look
        StPage.Agent -> R.string.st_agent
        StPage.Host -> R.string.st_host
        StPage.Storage -> R.string.st_storage
        StPage.Security -> R.string.st_security
        StPage.Notif -> R.string.st_notif
        StPage.About -> R.string.st_about
    },
)

@Composable
private fun pageSub(p: StPage, ui: SettingsUi): String? = when (p) {
    StPage.Root -> "DSH ${ui.appVersion}"
    StPage.Models -> stringResource(R.string.st_models_sub)
    StPage.Look -> stringResource(R.string.st_look_sub)
    StPage.Agent -> stringResource(R.string.st_agent_sub)
    StPage.Host -> stringResource(R.string.st_host_sub)
    StPage.Storage -> stringResource(R.string.st_storage_sub)
    StPage.Security -> stringResource(R.string.st_security_sub)
    else -> null
}

/**
 * «Настройки» по `#screen-settings` прототипа: корень с поиском, профилем и балансом,
 * восемь вложенных страниц и листы ключа. [toast] общий с приложением — для итогов действий.
 */
@Composable
fun SettingsHomeScreen(
    ui: SettingsUi,
    onClose: () -> Unit,
    actions: SettingsActions = NoActions,
    initialPage: StPage = StPage.Root,
    toast: ToastState = rememberToast(),
) {
    val stack = remember { PageStack(StPage.Root).also { if (initialPage != StPage.Root) it.push(initialPage) } }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var rise by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(900); rise = false }
    DshStackScreen(
        stack = stack,
        onClose = onClose,
        sheetOpen = sheet != null,
        bar = { p, back -> SBar(pageTitle(p), pageSub(p, ui), back, backDescription = stringResource(R.string.st_back)) },
        overlay = {
            ToastHost(toast)
            KSheet(sheet == "key", onDismiss = { sheet = null }) { KeySheet(custom = false, actions, toast) { sheet = null } }
            KSheet(sheet == "provider", onDismiss = { sheet = null }) { KeySheet(custom = true, actions, toast) { sheet = null } }
            KSheet(sheet == "model", onDismiss = { sheet = null }) {
                ModelSheet(stringResource(R.string.st_model_default), ui.models, ui.defaultModel, ui.reasoningLevels, ui.reasoningIndex) { m, r -> actions.setDefaultModel(m, r); sheet = null }
            }
            KSheet(sheet == "automodel", onDismiss = { sheet = null }) {
                ModelSheet(stringResource(R.string.st_for_auto), ui.models, ui.autoModelName, ui.reasoningLevels, ui.autoReasoningIndex) { m, r -> actions.setAutomationModel(m, r); sheet = null }
            }
        },
    ) { p ->
        CompositionLocalProvider(LocalRise provides (rise && p == StPage.Root)) {
            when (p) {
                StPage.Root -> RootPage(ui, actions, stack::push)
                StPage.Models -> ModelsPage(ui, actions, toast, onSheet = { sheet = it }, onDeleted = { stack.pop() })
                StPage.Look -> LookPage(ui, actions, toast)
                StPage.Agent -> AgentPage(ui, actions, toast, stack::push)
                StPage.Host -> HostPage(ui, actions, toast)
                StPage.Storage -> StoragePage(ui, actions, toast)
                StPage.Security -> SecurityPage(ui, actions, toast, stack::push)
                StPage.Notif -> NotifPage(ui, actions)
                StPage.About -> AboutPage(ui, actions, toast)
            }
        }
    }
}

// ---------- Корень ----------

@Composable
private fun RootPage(ui: SettingsUi, a: SettingsActions, go: (StPage) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val index = stringArrayResource(R.array.st_index)
    val themeName = themeLabel(ui.theme)
    FsView {
        FsSearch(query, { query = it }, stringResource(R.string.st_search), Modifier.padding(bottom = 12.dp).rise(0))
        if (query.isNotBlank()) {
            val pages = listOf(StPage.Models, StPage.Look, StPage.Agent, StPage.Host, StPage.Storage, StPage.Security, StPage.Notif, StPage.About)
            val titles = pages.map { pageTitle(it) }
            val hits = pages.indices.filter { i -> (titles[i] + " " + index.getOrElse(i) { "" }).lowercase().contains(query.trim().lowercase()) }
            if (hits.isEmpty()) FsEmpty(stringResource(R.string.st_nothing))
            else Gl { hits.forEach { i -> row { PageRow(pages[i], titles[i], null) { go(pages[i]) } } } }
            return@FsView
        }
        Profile(ui, Modifier.rise(1)) { go(StPage.Models) }
        StatRow(
            listOf(
                Triple(ui.balance ?: "—", stringResource(R.string.st_bal_balance), false),
                Triple(ui.today ?: "—", stringResource(R.string.st_bal_today), false),
                Triple(ui.monthSpent ?: "—", stringResource(R.string.st_bal_tokens), false),
            ),
            Modifier.padding(top = 8.dp).rise(2),
        )
        GLabel(stringResource(R.string.st_g_work), Modifier.rise(3))
        Gl(Modifier.rise(4)) {
            row { PageRow(StPage.Models, pageTitle(StPage.Models), ui.defaultModel.ifBlank { null }) { go(StPage.Models) } }
            row { PageRow(StPage.Agent, pageTitle(StPage.Agent), approveLabel(ui.autoApprove)) { go(StPage.Agent) } }
            row { StRow(stringResource(R.string.st_plugins), icon = DshGlyphs.Puzzle, tile = Si.c8, value = pluralStringResource(R.plurals.st_plugins_on, ui.pluginsOn, ui.pluginsOn), onClick = a::openPlugins) }
            row { StRow(stringResource(R.string.st_automation), icon = DshGlyphs.Clock, tile = Si.c7, value = pluralStringResource(R.plurals.st_auto_on, ui.automationsOn, ui.automationsOn), onClick = a::openAutomation) }
        }
        GLabel(stringResource(R.string.st_g_device), Modifier.rise(5))
        Gl(Modifier.rise(6)) {
            row { PageRow(StPage.Host, pageTitle(StPage.Host), stringResource(if (ui.hostRunning) R.string.st_host_running else R.string.st_host_stopped)) { go(StPage.Host) } }
            row { PageRow(StPage.Storage, pageTitle(StPage.Storage), ui.storage?.let { bytes(it.total) }) { go(StPage.Storage) } }
            row { PageRow(StPage.Notif, pageTitle(StPage.Notif), null) { go(StPage.Notif) } }
        }
        GLabel(stringResource(R.string.st_g_app), Modifier.rise(7))
        Gl(Modifier.rise(8)) {
            row { PageRow(StPage.Look, pageTitle(StPage.Look), themeName) { go(StPage.Look) } }
            row { PageRow(StPage.Security, pageTitle(StPage.Security), null) { go(StPage.Security) } }
            row { PageRow(StPage.About, pageTitle(StPage.About), ui.appVersion) { go(StPage.About) } }
        }
        VersionFooter(ui, Modifier.rise(9))
    }
}

@Composable
private fun PageRow(p: StPage, title: String, value: String?, onClick: () -> Unit) {
    val (ic, tile) = PageIcon.getValue(p)
    StRow(title, icon = DshGlyphs.get(ic), tile = tile, value = value, chevron = true, onClick = onClick)
}

@Composable
private fun themeLabel(theme: String) = stringResource(
    when (theme) { "light" -> R.string.st_theme_light; "dark" -> R.string.st_theme_dark; else -> R.string.st_theme_system },
)

@Composable
private fun approveLabel(auto: Boolean?) = when (auto) {
    true -> stringResource(R.string.st_appr_auto_short)
    false -> stringResource(R.string.st_appr_ask_short)
    null -> null
}

/** Профиль `.st-prof`: аватар 52 с буквой, имя 18/600 и способ подключения. */
@Composable
private fun Profile(ui: SettingsUi, modifier: Modifier, onClick: () -> Unit) {
    val name = ui.accountName ?: ui.provider
    val sub = when (ui.keyState) {
        KeyState.None -> stringResource(R.string.st_prof_nokey)
        else -> stringResource(R.string.st_prof_key, ui.provider)
    }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Dsh.colors.s1).border(1.dp, Dsh.colors.line, shape).rowClick(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF0B37E), Color(0xFFE2767A)))),
            contentAlignment = Alignment.Center,
        ) { Text(name.take(1).uppercase(), style = body(20.sp, 24.sp, FontWeight.SemiBold), color = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(name, style = display(18.sp, 23.sp), color = Dsh.colors.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = small(13.sp, 17.sp), color = if (ui.keyState == KeyState.None) Dsh.colors.warn else Dsh.colors.t2)
        }
        Icon(DshGlyphs.Right, null, tint = Dsh.colors.t3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun VersionFooter(ui: SettingsUi, modifier: Modifier = Modifier, build: Boolean = false, top: Int = 22) {
    Column(modifier.fillMaxWidth().padding(top = top.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DSH ${ui.appVersion}" + if (build && ui.appBuild > 0) " (${ui.appBuild})" else "", style = display(15.sp, 20.sp), color = Dsh.colors.t2)
        val parts = if (build) listOfNotNull(ui.hostVersion?.let { "Host $it" }, ui.sdkVersion?.let { "SDK $it" })
        else listOfNotNull(ui.hostVersion?.let { "Host $it" }, ui.distro, ui.androidVersion.ifBlank { null })
        Text(parts.joinToString(" · "), style = small(12.5.sp, 20.sp), color = Dsh.colors.t3, textAlign = TextAlign.Center)
    }
}

// ---------- Модели и ключи ----------

@Composable
private fun ModelsPage(ui: SettingsUi, a: SettingsActions, toast: ToastState, onSheet: (String) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val arm = rememberArm()
    FsView {
        StCard {
            Text(stringResource(R.string.st_bal_title, ui.provider), style = small(12.5.sp, 16.sp), color = Dsh.colors.t3)
            Spacer(Modifier.height(4.dp))
            BigWithNote(ui.balance ?: "—", ui.balanceDays?.let { pluralStringResource(R.plurals.st_bal_days, it, it) })
            if (ui.monthSpent != null) {
                Spacer(Modifier.height(14.dp))
                val month = stringArrayResource(R.array.st_month_spent)[java.time.LocalDate.now().monthValue - 1]
                Meter(month, ui.monthSpent, ui.monthSpentFraction)
            }
        }
        GLabel(stringResource(R.string.st_providers))
        Gl {
            row {
                val sub = when {
                    ui.keyLast4 == null -> stringResource(R.string.st_key_none)
                    ui.keyAdded != null -> stringResource(R.string.st_key_dots_added, ui.keyLast4, ui.keyAdded)
                    else -> stringResource(R.string.st_key_dots, ui.keyLast4)
                }
                val (v, vc) = when (ui.keyState) {
                    KeyState.Ok -> stringResource(R.string.st_key_ok) to Dsh.colors.ok
                    KeyState.Bad -> stringResource(R.string.st_key_bad) to Dsh.colors.danger
                    KeyState.Offline -> stringResource(R.string.st_key_offline) to Dsh.colors.t3
                    KeyState.Unknown -> stringResource(R.string.st_key_unknown) to Dsh.colors.t3
                    KeyState.None -> stringResource(R.string.st_key_add) to Dsh.colors.accent
                }
                StRow(ui.provider, icon = DshGlyphs.Key, tile = Si.c1, sub = sub, value = v, valueColor = vc, onClick = if (ui.keyState == KeyState.None) ({ onSheet("key") }) else null)
            }
            ui.customProviders.forEach { p ->
                row {
                    StRow(
                        p.name, icon = DshGlyphs.Server, tile = Si.c2, sub = p.sub,
                        value = when (p.ok) { true -> stringResource(R.string.st_key_ok); false -> stringResource(R.string.st_key_bad); null -> null },
                        valueColor = if (p.ok == false) Dsh.colors.danger else Dsh.colors.ok,
                    )
                }
            }
            row { StRow(stringResource(R.string.st_custom), icon = DshGlyphs.Plus, tile = Si.c6, sub = stringResource(R.string.st_custom_sub), chevron = true, onClick = { onSheet("provider") }) }
        }
        GLabel(stringResource(R.string.st_defaults))
        Gl {
            row { StRow(stringResource(R.string.st_model), icon = DshGlyphs.Cpu, tile = Si.c1, value = ui.defaultModel.ifBlank { "—" }, onClick = { onSheet("model") }) }
            row { StRow(stringResource(R.string.st_reasoning), icon = DshGlyphs.Sliders, tile = Si.c2, value = ui.reasoning.ifBlank { "—" }, onClick = { onSheet("model") }) }
            row { StRow(stringResource(R.string.st_for_auto), icon = DshGlyphs.Clock, tile = Si.c7, value = ui.automationModel.ifBlank { "—" }, onClick = { onSheet("automodel") }) }
        }
        GLabel(stringResource(R.string.st_key))
        Gl {
            row { StRow(stringResource(if (ui.keyLast4 != null) R.string.st_key_replace else R.string.st_key_add_full), icon = DshGlyphs.Key, tile = Si.c1, chevron = true, onClick = { onSheet("key") }) }
            row { StRow(stringResource(R.string.st_key_site), icon = DshGlyphs.Globe, tile = Si.c2, chevron = true, onClick = a::manageKeysOnSite) }
            if (ui.keyLast4 != null) row {
                val done = stringResource(R.string.st_key_deleted)
                StRow(
                    stringResource(if (arm.armed) R.string.st_key_delete_arm else R.string.st_key_delete),
                    icon = DshGlyphs.Trash, tile = Si.c5, titleColor = Dsh.colors.danger,
                    onClick = { arm.tap { scope.launch { if (a.deleteKey()) { toast.show(done); onDeleted() } } } },
                )
            }
        }
        SubNote(stringResource(R.string.st_key_note))
    }
}

/** Лист «Заменить ключ» / «Свой провайдер»: поле скрыто точками, «Показать», «Вставить», проверка у провайдера. */
@Composable
private fun KeySheet(custom: Boolean, a: SettingsActions, toast: ToastState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<KeyState?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Адрес своего API, введённый до конца (поле потеряло фокус или нажато «Готово»): ключ уходит только на него —
    // пауза в наборе `https://openai.co…` отправила бы ключ на чужой домен.
    var committedUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(key, url, committedUrl) {
        state = null
        if (key.isBlank()) return@LaunchedEffect
        if (custom && url.trim() != committedUrl) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        busy = true
        state = a.checkKey(if (custom) committedUrl else null, key.trim())
        busy = false
    }
    SheetTitle(stringResource(if (custom) R.string.st_custom else R.string.st_key_replace))
    Text(stringResource(if (custom) R.string.st_custom_lead else R.string.st_key_lead), style = body(14.5.sp, 21.sp), color = Dsh.colors.t2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
    if (custom) {
        Field(name, { name = it }, stringResource(R.string.st_custom_name))
        Spacer(Modifier.height(8.dp))
        Field(url, { url = it }, stringResource(R.string.st_custom_url), keyboard = KeyboardType.Uri, onCommit = { committedUrl = url.trim() })
        Spacer(Modifier.height(8.dp))
    }
    Field(
        key, { key = it }, stringResource(R.string.st_key_field), secret = !shown, keyboard = KeyboardType.Password,
        trailing = {
            IconBtn(DshGlyphs.Eye, stringResource(if (shown) R.string.st_key_hide else R.string.st_key_show), size = 40.dp, iconSize = 18.dp, tint = Dsh.colors.t3) { shown = !shown }
            KBtn(stringResource(R.string.st_key_paste), {
                // Вставленный ключ не остаётся в буфере обмена: его читают клавиатура и другие приложения.
                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { key = it; clipboard.setText(AnnotatedString("")) }
            }, height = 36.dp, radius = 10.dp, fontSize = 13.sp, horizontal = 10.dp, ink = Dsh.colors.accent)
        },
    )
    val (msg, color) = when {
        busy -> stringResource(R.string.st_key_checking) to Dsh.colors.t3
        state == KeyState.Ok -> stringResource(R.string.st_key_works) to Dsh.colors.ok
        state == KeyState.Bad -> stringResource(R.string.st_key_rejected) to Dsh.colors.danger
        state == KeyState.Offline -> stringResource(R.string.st_key_noconn) to Dsh.colors.warn
        else -> stringResource(R.string.st_key_safe) to Dsh.colors.t3
    }
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) Spinner(color, 13.dp) else Icon(if (state == KeyState.Ok) DshGlyphs.Check else if (state == KeyState.Bad) DshGlyphs.Alert else DshGlyphs.Lock, null, tint = color, modifier = Modifier.size(15.dp))
        Text(msg, style = small(12.5.sp, 17.sp), color = color)
    }
    val okToSave = !busy && key.isNotBlank() && state != KeyState.Bad && (!custom || (name.isNotBlank() && url.startsWith("https://") && url.length > 9))
    val saved = stringResource(R.string.st_key_saved)
    val failed = stringResource(R.string.st_key_save_failed)
    var saving by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBtn(stringResource(R.string.st_cancel), close, Modifier.weight(1f))
        KBtn(
            stringResource(R.string.st_save), {
                saving = true
                scope.launch {
                    val ok = if (custom) a.addProvider(name.trim(), url.trim(), key.trim()) else a.saveKey(key.trim())
                    saving = false
                    if (ok) { key = ""; toast.show(saved); close() } else toast.show(failed)
                }
            },
            Modifier.weight(1f), kind = BtnKind.Primary, enabled = okToSave, busy = saving,
        )
    }
}

/** Лист выбора модели: список моделей и сегменты уровня рассуждения. */
@Composable
private fun ModelSheet(title: String, models: List<String>, current: String, levels: List<String>, level: Int, onPick: (String, Int) -> Unit) {
    var m by remember(current) { mutableStateOf(current) }
    var r by remember(level) { mutableStateOf(level) }
    SheetTitle(title)
    if (models.isEmpty()) {
        FsEmpty(stringResource(R.string.st_models_empty))
    } else {
        Gl(Modifier.selectableGroup()) { models.forEach { name -> row { Rad(name, null, name == m) { m = name } } } }
    }
    if (levels.isNotEmpty()) {
        GLabel(stringResource(R.string.st_reasoning))
        Segm(levels, r, { r = it })
    }
    KBtn(stringResource(R.string.st_done), { onPick(m, r) }, Modifier.fillMaxWidth().padding(top = 16.dp), kind = BtnKind.Primary, height = 50.dp, radius = 16.dp, enabled = m.isNotBlank())
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    onCommit: (() -> Unit)? = null,
) {
    val c = Dsh.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(c.s2).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = body(15.sp), color = c.t3, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = (if (secret || keyboard == KeyboardType.Password) mono(14.sp, FontWeight.Normal) else body(15.sp)).copy(color = c.t1),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (secret) PasswordVisualTransformation('•') else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = if (onCommit != null) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { onCommit?.invoke(); defaultKeyboardAction(ImeAction.Done) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }.onFocusChanged {
                    if (focused && !it.isFocused) onCommit?.invoke()
                    focused = it.isFocused
                },
            )
        }
        trailing?.invoke()
    }
}

/** Крупное число `.st-card .big` и пояснение `small` в одной строке с переносом, как в прототипе. */
@Composable
private fun BigWithNote(big: String, note: String?) {
    Text(
        buildAnnotatedString {
            append(big)
            if (note != null) withStyle(body(14.sp).copy(color = Dsh.colors.t3).toSpanStyle()) { append(" $note") }
        },
        style = display(26.sp, 25.sp), color = Dsh.colors.t1,
    )
}

// ---------- Оформление ----------

@Composable
private fun LookPage(ui: SettingsUi, a: SettingsActions, toast: ToastState) {
    var size by remember(ui.codeSize) { mutableStateOf(ui.codeSize.toFloat()) }
    FsView {
        GLabel(stringResource(R.string.st_theme), top = 4.dp)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light" to R.string.st_theme_light, "dark" to R.string.st_theme_dark, "system" to R.string.st_theme_system).forEach { (k, n) ->
                ThemeCard(k, stringResource(n), ui.theme == k, Modifier.weight(1f)) { a.setTheme(k) }
            }
        }
        GLabel(stringResource(R.string.st_language))
        val langs = listOf("ru" to "Русский", "en" to "English")
        val instant = stringResource(R.string.st_lang_instant)
        StChips(langs.map { it.second }, langs.indexOfFirst { it.first == ui.language }, { i -> a.setLanguage(langs[i].first); if (langs[i].first != ui.language) toast.show(instant) })
        GLabel(stringResource(R.string.st_code_size))
        Gl {
            row {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A", style = small(12.sp), color = Dsh.colors.t3)
                        KSlider(size.toInt(), 11..16, { size = it.toFloat() }, { a.setCodeSize(size.toInt()) }, stringResource(R.string.st_code_size), Modifier.weight(1f))
                        Text("A", style = body(18.sp, 22.sp, FontWeight.SemiBold), color = Dsh.colors.t2)
                    }
                    val term = if (Dsh.colors.isDark) Color(0xFF0B0C0F) to Color(0xFFDCDEE6) else Color(0xFFFCFCFE) to Color(0xFF23242E)
                    Text(
                        "kotlin = \"2.0.21\"",
                        style = mono(size.toInt().sp, FontWeight.Normal),
                        color = term.second,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(term.first)
                            .border(1.dp, Dsh.colors.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Gl(Modifier.padding(top = 12.dp)) { row { StToggle(stringResource(R.string.st_haptics), ui.haptics, a::setHaptics, DshGlyphs.Phone, Si.c6) } }
        SubNote(stringResource(R.string.st_chat_size_note))
    }
}

/** Карточка темы `.st-th`: превью 64 с тремя полосками и подпись. */
@Composable
private fun ThemeCard(kind: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Dsh.colors
    val border by animateColorAsState(if (on) c.accent else c.line, tween(200), label = "th")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .then(
                if (on) Modifier.drawBehind {
                    val r = 4.dp.toPx()
                    drawRoundRect(c.accentSoft, topLeft = androidx.compose.ui.geometry.Offset(-r, -r), size = androidx.compose.ui.geometry.Size(size.width + 2 * r, size.height + 2 * r), cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                } else Modifier,
            )
            .clip(shape).background(c.s1).border(1.5.dp, border, shape)
            .selectable(on, role = Role.RadioButton, onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val light = Color(0xFFF6F6F9); val dark = Color(0xFF0E0F12); val lb = Color(0xFFE4E4EC); val db = Color(0xFF282930)
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, c.line, RoundedCornerShape(11.dp))) {
            val w = maxWidth
            when (kind) {
                "light" -> Box(Modifier.fillMaxWidth().height(64.dp).background(light))
                "dark" -> Box(Modifier.fillMaxWidth().height(64.dp).background(dark))
                else -> Row(Modifier.fillMaxWidth().height(64.dp)) { Box(Modifier.weight(1f).fillMaxHeight().background(light)); Box(Modifier.weight(1f).fillMaxHeight().background(dark)) }
            }
            val bar1 = if (kind == "dark") db else lb
            val bar2 = if (kind == "light") lb else db
            Box(Modifier.offset(8.dp, 10.dp).width(w * 0.7f - 8.dp).height(7.dp).clip(CircleShape).background(bar1))
            Box(Modifier.offset(8.dp, 24.dp).width(w * 0.55f - 8.dp).height(7.dp).clip(CircleShape).background(bar2))
            Box(Modifier.offset(w * 0.4f, 40.dp).width(w * 0.6f - 8.dp).height(7.dp).clip(CircleShape).background(Color(0xFF5A4CF0)))
        }
        Text(label, style = body(13.5.sp, 17.sp, FontWeight.Medium), color = if (on) c.t1 else c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Агент ----------

@Composable
private fun AgentPage(ui: SettingsUi, a: SettingsActions, toast: ToastState, go: (StPage) -> Unit) {
    val opened = stringResource(R.string.st_clip_on)
    val closed = stringResource(R.string.st_clip_off)
    FsView {
        GLabel(stringResource(R.string.st_edits), top = 4.dp)
        Gl(Modifier.selectableGroup()) {
            row { Rad(stringResource(R.string.st_appr_ask), stringResource(R.string.st_appr_ask_sub), ui.autoApprove == false) { a.setAutoApprove(false) } }
            row { Rad(stringResource(R.string.st_appr_auto), stringResource(R.string.st_appr_auto_sub), ui.autoApprove == true) { a.setAutoApprove(true) } }
            row { RadNever(stringResource(R.string.st_appr_never), stringResource(R.string.st_appr_never_sub)) }
        }
        if (ui.autoApprove == null) SubNote(stringResource(R.string.st_appr_unavailable))
        GLabel(stringResource(R.string.st_phone_access))
        Gl {
            row {
                StToggle(stringResource(R.string.st_clipboard), ui.clipboard, { on -> a.setClipboard(on); toast.show(if (on) opened else closed) }, DshGlyphs.Copy, Si.c4,
                    sub = stringResource(if (ui.clipboard) R.string.st_clipboard_on_sub else R.string.st_clipboard_sub))
            }
            // Чужой код из сети с правами приложения — только по явному решению (G-48).
            row {
                StToggle(stringResource(R.string.st_third_party), ui.thirdPartyPlugins, { on -> a.setThirdPartyPlugins(on) }, DshGlyphs.Puzzle, Si.c5,
                    sub = stringResource(if (ui.thirdPartyPlugins) R.string.st_third_party_on_sub else R.string.st_third_party_sub))
            }
        }
        GLabel(stringResource(R.string.st_sessions))
        Gl {
            row { StRow(stringResource(R.string.st_compress), icon = DshGlyphs.Flow, tile = Si.c1, value = stringResource(R.string.st_compress_auto)) }
            row { StRow(stringResource(R.string.st_workspace), icon = DshGlyphs.Folder, tile = Si.c2, value = stringResource(if (ui.workspaceShared) R.string.st_ws_shared else R.string.st_ws_private), chevron = true, onClick = { go(StPage.Storage) }) }
        }
    }
}

// ---------- Host ----------

@Composable
private fun HostPage(ui: SettingsUi, a: SettingsActions, toast: ToastState) {
    val scope = rememberCoroutineScope()
    var restarting by remember { mutableStateOf(false) }
    var toggling by remember { mutableStateOf(false) }
    val msgRestarted = stringResource(R.string.st_host_restarted)
    val msgStarted = stringResource(R.string.st_host_started)
    val msgStopped = stringResource(R.string.st_host_stopped_toast)
    val msgFailed = stringResource(R.string.st_host_failed)
    FsView {
        StCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Si.c3), contentAlignment = Alignment.Center) {
                    Icon(DshGlyphs.Server, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.st_host_local), style = display(18.sp, 23.sp), color = Dsh.colors.t1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        LiveDot(ui.hostRunning)
                        val t = if (ui.hostRunning) listOfNotNull(stringResource(R.string.st_host_works), ui.hostUptime).joinToString(" · ") else stringResource(R.string.st_host_off)
                        Text(t, style = small(13.sp, 17.sp), color = if (ui.hostRunning) Dsh.colors.ok else Dsh.colors.t3)
                    }
                }
            }
            if (ui.hostRunning && (ui.hostMemory != null || ui.hostCpu != null)) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.hostMemory?.let { Meter(stringResource(R.string.st_host_mem), it, ui.hostMemoryFraction) }
                    ui.hostCpu?.let { Meter(stringResource(R.string.st_host_cpu), "$it%", it / 100f) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KBtn(
                    stringResource(if (restarting) R.string.st_host_restarting else R.string.st_host_restart),
                    { restarting = true; scope.launch { toast.show(if (a.restartHost()) msgRestarted else msgFailed); restarting = false } },
                    Modifier.weight(1f), icon = DshGlyphs.Redo, radius = 13.dp, fontSize = 14.sp, horizontal = 6.dp, enabled = ui.hostRunning, busy = restarting, ink = if (ui.hostRunning) null else Dsh.colors.t3,
                )
                KBtn(
                    stringResource(if (ui.hostRunning) R.string.st_host_stop else R.string.st_host_start),
                    {
                        toggling = true
                        val run = !ui.hostRunning
                        scope.launch { toast.show(if (a.setHostRunning(run)) (if (run) msgStarted else msgStopped) else msgFailed); toggling = false }
                    },
                    Modifier.weight(1f), icon = if (ui.hostRunning) DshGlyphs.Stop else DshGlyphs.Play, radius = 13.dp, fontSize = 14.sp, horizontal = 6.dp, busy = toggling,
                )
            }
        }
        GLabel(stringResource(R.string.st_battery))
        Gl {
            row {
                Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SiTile(DshGlyphs.Moon, Si.c4)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.st_sleep), style = body(15.sp), color = Dsh.colors.t1)
                            Text(stringResource(R.string.st_sleep_sub), style = small(), color = Dsh.colors.t3)
                        }
                    }
                    val opts = listOf(5, 15, 30, 0)
                    val labels = opts.map { if (it == 0) stringResource(R.string.st_never) else stringResource(R.string.st_min, it) }
                    Opts(labels, opts.indexOf(ui.sleepMinutes).coerceAtLeast(0), { a.setSleep(opts[it]) }, Modifier.padding(top = 18.dp, bottom = 4.dp), wide = true)
                }
            }
            row { StToggle(stringResource(R.string.st_keep_bg), ui.keepBg, a::setKeepBg, DshGlyphs.Bolt, Si.c3, sub = stringResource(R.string.st_keep_bg_sub)) }
        }
        if (ui.remoteHostsSupported) GLabel(stringResource(R.string.st_remotes))
        if (ui.remoteHostsSupported) Gl {
            ui.remotes.forEach { r ->
                row { StRow(r.name, icon = DshGlyphs.Server, tile = Si.c2, sub = "HTTPS · ${r.fingerprint}", value = stringResource(if (r.online) R.string.st_online else R.string.st_offline), valueColor = if (r.online) Dsh.colors.ok else Dsh.colors.t3) }
            }
            row { StRow(stringResource(R.string.st_pair), icon = DshGlyphs.Plus, tile = Si.c6, sub = stringResource(R.string.st_pair_sub), chevron = true, onClick = a::pairRemote) }
        }
        GLabel(stringResource(R.string.st_env))
        Gl {
            row { StRow(ui.distro ?: "Linux", icon = DshGlyphs.Box, tile = Si.c6, value = ui.rootfsUpdated?.let { stringResource(R.string.st_updated, it) }) }
            row { StRow(stringResource(R.string.st_open_term), icon = DshGlyphs.Term, tile = Si.c6, chevron = true, onClick = a::openTerminal) }
            row { StRow(stringResource(R.string.st_host_log), icon = DshGlyphs.File, tile = Si.c6, chevron = true, onClick = a::shareHostLog) }
        }
    }
}


// ---------- Хранилище ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoragePage(ui: SettingsUi, a: SettingsActions, toast: ToastState) {
    val s = ui.storage
    val colors = listOf(Color(0xFF5A4CF0), Color(0xFF2F7DE1), Color(0xFFD97706), Color(0xFF6B7280))
    val asked = stringResource(R.string.st_ws_shared_toast)
    FsView {
        StCard {
            BigWithNote(s?.let { bytes(it.total) } ?: "…", if (s != null && s.device > 0) stringResource(R.string.st_of_device, bytes(s.device)) else null)
            val parts = s?.let { listOf(it.linux, it.projects, it.cache, it.other) } ?: emptyList()
            val sum = parts.sum().coerceAtLeast(1)
            Row(Modifier.padding(top = 4.dp, bottom = 10.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Dsh.colors.s3)) {
                parts.forEachIndexed { i, v -> if (v > 0) Box(Modifier.weight(v.toFloat() / sum).fillMaxHeight().background(colors[i])) }
                val rest = 1f - parts.sum().toFloat() / sum
                if (rest > 0.001f) Spacer(Modifier.weight(rest))
            }
            val names = listOf(R.string.st_part_linux, R.string.st_part_projects, R.string.st_part_cache, R.string.st_part_other)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(colors[i]))
                        Text(stringResource(n) + " " + (parts.getOrNull(i)?.let { bytes(it) } ?: "…"), style = small(), color = Dsh.colors.t2)
                    }
                }
            }
        }
        GLabel(stringResource(R.string.st_free))
        Gl {
            row { CleanRow(stringResource(R.string.st_cache), stringResource(R.string.st_cache_sub), s?.cache, "cache", a, toast) }
            row { CleanRow(stringResource(R.string.st_logs), stringResource(R.string.st_logs_sub), s?.logs, "logs", a, toast) }
        }
        GLabel(stringResource(R.string.st_workspace))
        Gl(Modifier.selectableGroup()) {
            row { Rad(stringResource(R.string.st_ws_private), stringResource(R.string.st_ws_private_sub), !ui.workspaceShared) { a.setWorkspaceShared(false) } }
            row { Rad(stringResource(R.string.st_ws_shared), stringResource(R.string.st_ws_shared_sub), ui.workspaceShared) { if (!ui.workspaceShared) toast.show(asked); a.setWorkspaceShared(true) } }
        }
        GLabel(stringResource(R.string.st_backup))
        Gl {
            row { StRow(stringResource(R.string.st_export), icon = DshGlyphs.Share, tile = Si.c3, sub = stringResource(R.string.st_export_sub), chevron = true, onClick = a::exportSessions) }
            row { StRow(stringResource(R.string.st_restore), icon = DshGlyphs.Down, tile = Si.c6, chevron = true, onClick = a::restoreBackup) }
        }
    }
}

@Composable
private fun CleanRow(title: String, sub: String, size: Long?, kind: String, a: SettingsActions, toast: ToastState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val freed = stringResource(R.string.st_freed)
    val units = stringArrayResource(R.array.st_units)
    StRow(title, sub = sub) {
        val label = when {
            done -> stringResource(R.string.st_done)
            size == null -> "…"
            else -> bytes(size)
        }
        KBtn(
            if (busy) "" else label,
            { busy = true; scope.launch { val f = a.clean(kind); busy = false; done = true; toast.show(freed.format(formatBytes(f, units))) } },
            height = 40.dp, radius = 10.dp, fontSize = 13.5.sp, horizontal = 12.dp,
            ink = if (done) Dsh.colors.ok else Dsh.colors.accent, busy = busy, enabled = !done && (size ?: 0) > 0,
        )
    }
}

// ---------- Безопасность ----------

@Composable
private fun SecurityPage(ui: SettingsUi, a: SettingsActions, toast: ToastState, go: (StPage) -> Unit) {
    val scope = rememberCoroutineScope()
    val arm = rememberArm()
    var wiping by remember { mutableStateOf(false) }
    FsView {
        if (ui.biometricAvailable) {
            Gl { row { StToggle(stringResource(R.string.st_bio), ui.biometric, a::setBiometric, DshGlyphs.Lock, Si.c1, sub = stringResource(R.string.st_bio_sub)) } }
        }
        if (ui.trusted.isNotEmpty()) {
            GLabel(stringResource(R.string.st_trusted), top = if (ui.biometricAvailable) 16.dp else 4.dp)
            Gl {
                ui.trusted.forEach { t ->
                    row {
                        StRow(
                            t.name,
                            leading = { PlIc(t.letter, t.hue, off = t.blocked, size = 30.dp, radius = 9.dp, fontSize = 13.sp) },
                            sub = if (t.blocked) stringResource(R.string.st_sig_changed) else t.cert,
                            subColor = if (t.blocked) Dsh.colors.danger else Dsh.colors.t3,
                            value = if (t.blocked) null else stringResource(R.string.st_trusted_ok),
                            valueColor = Dsh.colors.ok,
                        ) {
                            if (t.blocked) KBtn(stringResource(R.string.st_open), { a.openPlugin(t.id) }, height = 40.dp, radius = 10.dp, fontSize = 13.5.sp, horizontal = 12.dp, ink = Dsh.colors.accent)
                        }
                    }
                }
            }
        }
        GLabel(stringResource(R.string.st_protection))
        Gl {
            row { StRow(stringResource(R.string.st_api_keys), icon = DshGlyphs.Key, tile = Si.c3, value = stringResource(R.string.st_in_keystore)) }
            if (ui.remoteHostsSupported) row { StRow(stringResource(R.string.st_remotes_short), icon = DshGlyphs.Server, tile = Si.c2, value = stringResource(R.string.st_https_only)) }
            row { StRow(stringResource(R.string.st_clip_short), icon = DshGlyphs.Copy, tile = Si.c4, value = stringResource(if (ui.clipboard) R.string.st_clip_open else R.string.st_clip_closed), chevron = true, onClick = { go(StPage.Agent) }) }
        }
        Gl(Modifier.padding(top = 12.dp)) {
            row {
                StRow(
                    stringResource(if (arm.armed) R.string.st_wipe_arm else R.string.st_wipe),
                    icon = DshGlyphs.Trash, tile = Si.c5, titleColor = Dsh.colors.danger,
                    onClick = { if (!wiping) arm.tap { wiping = true; scope.launch { a.wipeAll(); wiping = false } } },
                ) { if (wiping) Spinner(Dsh.colors.danger) }
            }
        }
    }
}

// ---------- Уведомления ----------

@Composable
private fun NotifPage(ui: SettingsUi, a: SettingsActions) {
    FsView {
        Gl {
            row { StToggle(stringResource(R.string.st_n_done), ui.notifDone, a::setNotifDone, DshGlyphs.Check, Si.c3, sub = stringResource(R.string.st_n_done_sub)) }
            row { StToggle(stringResource(R.string.st_n_ask), ui.notifAsk, a::setNotifAsk, DshGlyphs.Shield, Si.c4, sub = stringResource(R.string.st_n_ask_sub)) }
        }
        GLabel(stringResource(R.string.st_n_auto))
        val keys = listOf("always", "err", "never")
        StChips(listOf(stringResource(R.string.st_always), stringResource(R.string.st_on_error), stringResource(R.string.st_never)), keys.indexOf(ui.notifAuto), { a.setNotifAuto(keys[it]) })
        SubNote(stringResource(R.string.st_n_auto_note))
        Gl(Modifier.padding(top = 12.dp)) { row { StRow(stringResource(R.string.st_n_system), icon = DshGlyphs.Gear, tile = Si.c6, chevron = true, onClick = a::openSystemNotifications) } }
    }
}

// ---------- О приложении ----------

@Composable
private fun AboutPage(ui: SettingsUi, a: SettingsActions, toast: ToastState) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember(ui.updateAvailable) { mutableStateOf(ui.updateAvailable) }
    val latest = stringResource(R.string.st_latest_toast)
    FsView {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Dsh.colors.accent, spotColor = Dsh.colors.accent).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Dsh.colors.accent2, Dsh.colors.accent))),
                contentAlignment = Alignment.Center,
            ) { Icon(DshGlyphs.get("dsh"), null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            VersionFooter(ui, build = true, top = 12)
        }
        Spacer(Modifier.height(10.dp))
        Gl {
            row {
                StRow(
                    stringResource(R.string.st_check_updates), icon = DshGlyphs.Redo, tile = Si.c3,
                    value = if (checking) null else update?.let { stringResource(R.string.st_update_to, it) } ?: stringResource(R.string.st_up_to_date),
                    valueColor = if (update != null) Dsh.colors.accent else Dsh.colors.t3,
                    onClick = { checking = true; scope.launch { update = a.checkUpdates(); checking = false; if (update == null) toast.show(latest) } },
                ) { if (checking) Spinner(Dsh.colors.t3) }
            }
            row { StRow(stringResource(R.string.st_report), icon = DshGlyphs.Bug, tile = Si.c5, sub = stringResource(R.string.st_report_sub), chevron = true, onClick = a::reportProblem) }
            row { StRow(stringResource(R.string.st_whats_new), icon = DshGlyphs.Book, tile = Si.c2, chevron = true, onClick = a::whatsNew) }
            row { StRow(stringResource(R.string.st_licenses), icon = DshGlyphs.File, tile = Si.c6, chevron = true, onClick = a::licenses) }
        }
        SubNote(stringResource(R.string.st_about_note), top = 12.dp)
    }
}

// ---------- Формат ----------

/** Байты по-человечески: «3,1 ГБ», «410 МБ». */
@Composable
fun bytes(b: Long): String = formatBytes(b, stringArrayResource(R.array.st_units))

fun formatBytes(b: Long, units: Array<String>): String {
    var v = b.toDouble()
    var u = 0
    while (v >= 1000 && u < units.lastIndex) { v /= 1024; u++ }
    val s = if (v < 10 && u > 0) String.format(java.util.Locale.getDefault(), "%.1f", v) else String.format(java.util.Locale.getDefault(), "%.0f", v)
    return "$s ${units[u]}"
}
