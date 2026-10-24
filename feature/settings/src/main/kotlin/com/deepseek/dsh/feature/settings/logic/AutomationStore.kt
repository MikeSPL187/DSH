package com.deepseek.dsh.feature.settings.logic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Как часто запускать: каждый день, по будням, раз в неделю, каждый час. */
enum class AutoFreq { Daily, Work, Week, Hour }

/** Запуск в истории. [status]: `ok`, `err`, `skip` (перенесён: условие не выполнено), `run` (идёт). */
data class AutoRun(val at: Long, val status: String, val durationMs: Long?, val result: String, val sessionId: String? = null)

/**
 * Автоматизация: что сделать (промпт), когда, при каких условиях и что агенту можно без вас.
 *
 * @property days дни недели 0 = пн … 6 = вс (для [AutoFreq.Week] — один день).
 * @property perm `read` — только читать и сообщать, `edit` — править файлы в проекте.
 * @property notify `always`, `err`, `changes`.
 */
data class Automation(
    val id: String,
    val name: String,
    val icon: String = "flow",
    val tone: String = "",
    val on: Boolean = true,
    val freq: AutoFreq = AutoFreq.Daily,
    val time: String = "09:00",
    val days: List<Int> = listOf(0),
    val charge: Boolean = false,
    val wifi: Boolean = false,
    val perm: String = "read",
    val notify: String = "err",
    val project: String = "",
    val model: String = "",
    val reasoning: Int = 0,
    val prompt: String = "",
    val history: List<AutoRun> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** Хранилище автоматизаций (SharedPreferences, JSON) и расчёт времени следующего запуска. */
object AutomationStore {
    private const val PREFS = "dsh_automations"
    private var app: Context? = null

    var items by mutableStateOf<List<Automation>>(emptyList())
        private set

    /** Модель и уровень рассуждения для новых автоматизаций («Настройки → Для автоматизаций»). */
    var defaultModel by mutableStateOf("" to 0)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        items = runCatching { decode(JSONArray(p.getString("items", "[]"))) }.getOrDefault(emptyList())
        defaultModel = (p.getString("model", "") ?: "") to p.getInt("reasoning", 0)
    }

    fun setDefaultModel(model: String, reasoning: Int) {
        defaultModel = model to reasoning
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("model", model)?.putInt("reasoning", reasoning)?.apply()
    }

    fun get(id: String): Automation? = items.firstOrNull { it.id == id }

    fun upsert(a: Automation) {
        items = if (items.any { it.id == a.id }) items.map { if (it.id == a.id) a else it } else listOf(a) + items
        save()
    }

    fun delete(id: String) { items = items.filterNot { it.id == id }; save() }

    fun addRun(id: String, run: AutoRun) {
        val a = get(id) ?: return
        val rest = a.history.filterNot { it.status == "run" && run.status != "run" }
        upsert(a.copy(history = (listOf(run) + rest).take(30)))
    }

    private fun save() {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("items", encode(items).toString())?.apply()
    }

    /** Следующий запуск после [from] (мс) или null, если выключена. */
    fun nextRun(a: Automation, from: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!a.on) return null
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), zone)
        if (a.freq == AutoFreq.Hour) return now.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
        val t = runCatching { LocalTime.parse(a.time) }.getOrDefault(LocalTime.of(9, 0))
        val allowed: Set<DayOfWeek> = when (a.freq) {
            AutoFreq.Daily -> DayOfWeek.entries.toSet()
            AutoFreq.Work -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            AutoFreq.Week -> a.days.ifEmpty { listOf(0) }.map { DayOfWeek.of(it.coerceIn(0, 6) + 1) }.toSet()
            AutoFreq.Hour -> emptySet()
        }
        for (d in 0..7) {
            val cand = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate().plusDays(d.toLong()), t), zone)
            if (cand.isAfter(now) && cand.dayOfWeek in allowed) return cand.toInstant().toEpochMilli()
        }
        return null
    }

    fun encode(list: List<Automation>): JSONArray = JSONArray().apply {
        list.forEach { a ->
            put(
                JSONObject().put("id", a.id).put("name", a.name).put("icon", a.icon).put("tone", a.tone).put("on", a.on)
                    .put("freq", a.freq.name).put("time", a.time).put("days", JSONArray(a.days)).put("charge", a.charge).put("wifi", a.wifi)
                    .put("perm", a.perm).put("notify", a.notify).put("project", a.project).put("model", a.model).put("reasoning", a.reasoning)
                    .put("prompt", a.prompt).put("createdAt", a.createdAt)
                    .put("history", JSONArray().apply { a.history.forEach { r -> put(JSONObject().put("at", r.at).put("s", r.status).put("d", r.durationMs ?: -1).put("r", r.result).put("sid", r.sessionId ?: "")) } }),
            )
        }
    }

    fun decode(arr: JSONArray): List<Automation> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val days = o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) } } ?: listOf(0)
        val hist = o.optJSONArray("history")?.let { h ->
            (0 until h.length()).map { k ->
                val r = h.getJSONObject(k)
                AutoRun(r.optLong("at"), r.optString("s"), r.optLong("d", -1).takeIf { it >= 0 }, r.optString("r"), r.optString("sid").ifBlank { null })
            }
        } ?: emptyList()
        Automation(
            id = o.getString("id"), name = o.optString("name"), icon = o.optString("icon", "flow"), tone = o.optString("tone"), on = o.optBoolean("on", true),
            freq = runCatching { AutoFreq.valueOf(o.optString("freq")) }.getOrDefault(AutoFreq.Daily), time = o.optString("time", "09:00"), days = days,
            charge = o.optBoolean("charge"), wifi = o.optBoolean("wifi"), perm = o.optString("perm", "read"), notify = o.optString("notify", "err"),
            project = o.optString("project"), model = o.optString("model"), reasoning = o.optInt("reasoning"), prompt = o.optString("prompt"),
            history = hist, createdAt = o.optLong("createdAt", 0L),
        )
    }
}
