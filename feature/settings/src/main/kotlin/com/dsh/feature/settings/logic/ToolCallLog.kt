package com.dsh.feature.settings.logic

import com.dsh.core.designsystem.kit.*
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Журнал вызовов инструментов агента: из него берутся «Журнал вызовов», счётчики
 * «вызовов за неделю / ошибок / последний» и список инструментов плагина.
 *
 * Пишется по готовым сообщениям `TOOL` (`\u0002TOOL\u0002id\u0002name\u0002status\u0002body\u0002args`)
 * и по ответам на запросы одобрения. Хранится последние [MAX] записей.
 */
object ToolCallLog {
    private const val PREFS = "dsh_tool_log"
    private const val MAX = 600

    /** [status]: `ok`, `err`, `ask` (спрашивали разрешение), `deny` (отклонено). */
    data class Entry(val callId: String, val tool: String, val at: Long, val durationMs: Long?, val status: String, val result: String)

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    private var app: Context? = null
    private val started = HashMap<String, Long>()

    fun init(context: Context) {
        app = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]")
        entries = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(o.optString("id"), o.optString("t"), o.optLong("at"), o.optLong("d", -1).takeIf { it >= 0 }, o.optString("s"), o.optString("r"))
            }
        }.getOrDefault(emptyList())
    }

    /** Разобрать сообщение `TOOL`; возвращает true, если журнал изменился. */
    fun ingest(content: String, at: Long = System.currentTimeMillis()): Boolean {
        val parts = content.split('\u0002')
        if (parts.size < 5 || parts[1] != "TOOL") return false
        val id = parts[2]; val name = parts[3]; val status = parts[4]; val body = parts.getOrNull(5).orEmpty()
        if (status == "running") { started[id] = at; return false }
        val s = if (status == "error") "err" else "ok"
        if (entries.any { it.callId == id && it.status == s }) return false
        val start = started.remove(id)
        add(Entry(id, name, at, start?.let { at - it }, s, summarize(body)))
        return true
    }

    /** Запрос одобрения: разрешён ли и кто ответил. */
    fun approval(tool: String, approved: Boolean, byUser: Boolean, at: Long = System.currentTimeMillis()) {
        add(Entry("ask-$at-$tool", tool, at, null, if (approved) "ask" else "deny", if (byUser) (if (approved) "allowed" else "denied") else (if (approved) "auto-allowed" else "auto-denied")))
    }

    private fun summarize(body: String): String = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(140)

    private fun add(e: Entry) {
        entries = (listOf(e) + entries).take(MAX)
        val a = JSONArray()
        entries.forEach { x -> a.put(JSONObject().put("id", x.callId).put("t", x.tool).put("at", x.at).put("d", x.durationMs ?: -1).put("s", x.status).put("r", x.result)) }
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("log", a.toString())?.apply()
    }

    fun clear() { entries = emptyList(); app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply() }

    /**
     * Принадлежит ли инструмент [tool] плагину с именем пакета [pkg] и названием [title]:
     * по префиксу имени (`git.log`, `git_log`, `git__log` → «git»).
     */
    fun belongs(tool: String, pkg: String, title: String): Boolean {
        val t = tool.lowercase()
        val keys = buildSet {
            val short = pkg.substringAfterLast('/').lowercase().removePrefix("dsh-plugin-").removePrefix("dsh-").removePrefix("plugin-").removeSuffix("-plugin")
            add(short); add(short.replace("-", "")); add(short.substringBefore('-'))
            add(title.lowercase().substringBefore(' '))
        }.filter { it.length >= 3 }
        val prefix = t.split('.', '_', ':', '/').firstOrNull().orEmpty()
        return keys.any { k -> prefix == k || t.startsWith("$k.") || t.startsWith("${k}_") || t.startsWith("${k}__") || t.startsWith("$k:") }
    }
}

/**
 * Режимы инструментов: `auto` — разрешать без вопроса, `ask` — спрашивать, `off` — отклонять.
 * Применяются к запросам одобрения Host (см. DshRepository.approvals.approvalPolicy).
 * Опасные действия (удаление, команды вне проекта) всегда спрашиваются — это решает вызывающий.
 */
object ToolPolicy {
    private const val PREFS = "dsh_tool_policy"
    private var app: Context? = null
    var modes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun init(context: Context) {
        app = context.applicationContext
        modes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    fun mode(tool: String): String? = modes[tool]

    fun set(tool: String, mode: String) {
        modes = modes + (tool to mode)
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(tool, mode)?.apply()
    }
}
