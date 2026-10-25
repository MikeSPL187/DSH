package com.deepseek.dsh.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deepseek.dsh.R
import com.deepseek.dsh.TermStatus
import com.deepseek.dsh.TermTab
import com.deepseek.dsh.TerminalScreen
import com.deepseek.dsh.core.data.DshRepository
import com.deepseek.dsh.core.designsystem.LocalCodeFontSize
import com.deepseek.dsh.core.model.TerminalInfo
import com.deepseek.dsh.core.protocol.TerminalConnection
import com.deepseek.dsh.core.designsystem.kit.rememberToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Блоки вкладок живут, пока жив процесс: уход со страницы и возврат их не теряют.
 * Ключ — хост, сессия и id терминала.
 */
object TerminalHub {
    private val models = HashMap<String, TermModel>()
    private val lastActive = HashMap<String, String>()

    fun model(host: String, sid: String, id: String): TermModel = models.getOrPut("$host/$sid/$id") { TermModel(id) }
    fun drop(host: String, sid: String, id: String) { models.remove("$host/$sid/$id") }
    fun active(host: String, sid: String): String? = lastActive["$host/$sid"]

    /** Последняя команда с ошибкой во вкладках сессии — для «Вывод терминала» в чате. */
    fun lastFailed(host: String, sid: String): TermBlock? = models.filterKeys { it.startsWith("$host/$sid/") }.values
        .flatMap { it.blocks }.filter { it.state == BlockState.Err }.maxByOrNull { it.startedAt }
    fun setActive(host: String, sid: String, id: String) { lastActive["$host/$sid"] = id }
}

/** История команд для ↑ / ↓, Tab и подсказок `.tsug` — на телефоне, последние 100. */
object TermHistory {
    private const val PREFS = "dsh_terminal"
    private const val MAX = 100
    val items = mutableStateListOf<String>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", null)
            ?.split('\n')?.filter { it.isNotBlank() }?.let { items.addAll(it) }
    }

    fun add(context: Context, cmd: String) {
        if (items.lastOrNull() != cmd) items.add(cmd)
        while (items.size > MAX) items.removeAt(0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", items.joinToString("\n")).apply()
    }
}

/**
 * Связка экрана «Терминал» с Host: список оболочек (`terminal/list`), подключение
 * к каждой вкладке (`terminal/attach`), ввод, Ctrl+C, новые и закрытые вкладки.
 *
 * @param distro имя дистрибутива для шапки («Debian 12»), если Linux на этом телефоне.
 * @param onBack «Назад» — у страницы; в боковой панели не показывается.
 */
@Composable
fun DshTerminalRoute(
    repository: DshRepository,
    hostId: String,
    sessionId: String?,
    distro: String?,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val codeSize = LocalCodeFontSize.current
    var fontSize by remember(codeSize) { mutableStateOf(codeSize - 0.5f) }
    var tabs by remember(hostId, sessionId) { mutableStateOf<List<TerminalInfo>>(emptyList()) }
    var shells by remember(hostId, sessionId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var activeId by remember(hostId, sessionId) { mutableStateOf(sessionId?.let { TerminalHub.active(hostId, it) }) }
    var loadSerial by remember { mutableIntStateOf(0) }
    var listFailed by remember(hostId, sessionId) { mutableStateOf(false) }
    val conns = remember(hostId, sessionId) { mutableStateMapOf<String, TerminalConnection>() }
    val writable = remember(hostId, sessionId) { mutableStateMapOf<String, Boolean>() }
    val closed = remember(hostId, sessionId) { mutableStateMapOf<String, Boolean>() }
    val failed = remember(hostId, sessionId) { mutableStateMapOf<String, Boolean>() }
    val reopen = remember(hostId, sessionId) { mutableStateMapOf<String, Int>() }
    var size by remember { mutableStateOf(80 to 24) }
    LaunchedEffect(Unit) { TermHistory.load(context) }

    fun bashPath(): String? = shells.firstOrNull { it.second.contains("bash", true) || it.first.endsWith("/bash") }?.first

    // Список вкладок; пустой — создаём первую оболочку.
    LaunchedEffect(hostId, sessionId, loadSerial) {
        val sid = sessionId ?: return@LaunchedEffect
        listFailed = false
        shells = runCatching { repository.listTerminalShells(hostId, sid) }.getOrDefault(emptyList())
        var list = runCatching { repository.listTerminals(hostId, sid) }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            val id = activeId ?: "main"
            repository.createTerminal(hostId, sid, id, bashPath(), size.first, size.second)
            list = runCatching { repository.listTerminals(hostId, sid) }.getOrDefault(emptyList())
        }
        if (list.isEmpty()) { listFailed = true; return@LaunchedEffect }
        tabs = list
        if (list.none { it.id == activeId }) activeId = list.first().id
        activeId?.let { TerminalHub.setActive(hostId, sid, it) }
    }

    // Подключение к каждой вкладке: так у фоновых вкладок крутится признак работы.
    if (sessionId != null) for (t in tabs) key(t.id) {
        LaunchedEffect(t.id, reopen[t.id] ?: 0) {
            val model = TerminalHub.model(hostId, sessionId, t.id)
            closed.remove(t.id); failed.remove(t.id)
            val conn = repository.openTerminal(hostId, sessionId, size.first, size.second, t.id)
            if (conn == null) { failed[t.id] = true; return@LaunchedEffect }
            conns[t.id] = conn
            try {
                conn.frames.collect { raw ->
                    val frame = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@collect
                    fun text(k: String) = (frame[k] as? JsonPrimitive)?.contentOrNull
                    fun state() = ((frame["info"] as? JsonObject)?.get("state") as? JsonPrimitive)?.contentOrNull
                    when (text("type")) {
                        "snapshot" -> {
                            writable[t.id] = frame["writable"]?.toString() == "true"
                            if (state() == "exited") closed[t.id] = true
                            if (!model.setupSent && looksIdle(text("screen").orEmpty())) conn.write(model.setupLine())
                        }
                        "output" -> model.feed(text("data").orEmpty())
                        "state" -> {
                            writable[t.id] = frame["writable"]?.toString() == "true"
                            if (state() == "exited") { closed[t.id] = true; model.closed() }
                        }
                        "closed" -> { closed[t.id] = true; model.closed() }
                        "error" -> failed[t.id] = true
                    }
                }
            } finally {
                conn.close()
                conns.remove(t.id)
            }
        }
        LaunchedEffect(t.id) { while (true) { delay(500); TerminalHub.model(hostId, sessionId, t.id).checkSetupTimeout() } }
    }

    val sid = sessionId
    val active = tabs.firstOrNull { it.id == activeId }
    val model = if (sid != null && active != null) TerminalHub.model(hostId, sid, active.id) else null
    val status = when {
        sid == null -> TermStatus.NoSession
        listFailed -> TermStatus.Unavailable
        active == null -> TermStatus.Connecting
        closed[active.id] == true -> TermStatus.Closed
        failed[active.id] == true -> TermStatus.Unavailable
        conns[active.id] == null -> TermStatus.Connecting
        else -> TermStatus.Ready
    }
    val connected = status == TermStatus.Ready
    val subtitle = when (status) {
        TermStatus.Ready, TermStatus.Closed -> listOfNotNull(distro, model?.cwd).joinToString(" · ").ifEmpty { null }
        TermStatus.Connecting -> context.getString(R.string.tm_sub_connecting)
        else -> context.getString(R.string.tm_sub_offline)
    }
    val conn = active?.let { conns[it.id] }

    TerminalScreen(
        tabs = tabs.map { t ->
            val m = if (sid != null) TerminalHub.model(hostId, sid, t.id) else null
            TermTab(t.id, t.title.ifBlank { t.shellName }.ifBlank { "bash" }, m?.live != null)
        },
        activeId = activeId,
        model = model,
        status = status,
        subtitle = subtitle,
        connected = connected,
        writable = active?.let { writable[it.id] } ?: false,
        history = TermHistory.items,
        fontSize = fontSize,
        toast = toast,
        onBack = onBack,
        onFont = {
            fontSize = if (fontSize >= 14f) 11.5f else fontSize + 1.25f
            toast.show(context.getString(R.string.tm_font_toast, java.text.DecimalFormat("0.##").format(fontSize)))
        },
        onClear = { model?.clearLocal(false) },
        onSelectTab = { id -> activeId = id; sid?.let { TerminalHub.setActive(hostId, it, id) } },
        onAddTab = {
            if (sid != null) scope.launch {
                val id = "term-" + System.currentTimeMillis()
                val made = repository.createTerminal(hostId, sid, id, bashPath(), size.first, size.second)
                tabs = runCatching { repository.listTerminals(hostId, sid) }.getOrDefault(tabs)
                if (made != null) {
                    activeId = made.id; TerminalHub.setActive(hostId, sid, made.id)
                    toast.show(context.getString(R.string.tm_add_toast, made.shellName.ifBlank { "bash" }))
                }
            }
        },
        onCloseTab = { id ->
            if (sid != null) scope.launch {
                repository.closeTerminal(hostId, sid, id)
                conns[id]?.close()
                TerminalHub.drop(hostId, sid, id)
                if (activeId == id) activeId = null
                loadSerial++
            }
        },
        onRun = { cmd ->
            val m = model ?: return@TerminalScreen
            val c = conn ?: return@TerminalScreen
            TermHistory.add(context, cmd)
            if (cmd == "clear") { m.clearLocal(true); c.write("clear\r"); return@TerminalScreen }
            m.submit(cmd)
            c.write(cmd + "\r")
        },
        onStdin = { text -> conn?.write(text + "\r") },
        onRaw = { raw -> conn?.write(raw) },
        onExplain = { b ->
            val e = Explain()
            b.explain = e
            scope.launch { TermExplainer.explain(context, b, e) }
        },
        onCopy = { b ->
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", "$ ${b.cmd}\n${b.out.plain()}"))
            toast.show(context.getString(R.string.tm_copied))
        },
        onRetryStatus = {
            val a = active
            when {
                a == null || listFailed -> loadSerial++
                closed[a.id] == true && sid != null -> scope.launch {
                    repository.createTerminal(hostId, sid, a.id, bashPath(), size.first, size.second)
                    TerminalHub.model(hostId, sid, a.id).reset()
                    reopen[a.id] = (reopen[a.id] ?: 0) + 1
                }
                else -> reopen[a.id] = (reopen[a.id] ?: 0) + 1
            }
        },
        onResize = { cols, rows ->
            size = cols to rows
            conns.values.forEach { it.resize(cols, rows) }
        },
    )
}

/** Оболочка ждёт команду (а не занята программой) — можно тихо настроить метки. */
internal fun looksIdle(screen: String): Boolean {
    val last = screen.lines().lastOrNull { it.isNotBlank() }?.trimEnd() ?: return true
    return last.endsWith("$") || last.endsWith("#") || last.endsWith(">") || last.endsWith("%")
}
