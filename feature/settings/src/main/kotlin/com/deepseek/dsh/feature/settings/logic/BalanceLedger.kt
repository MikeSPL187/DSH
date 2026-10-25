package com.deepseek.dsh.feature.settings.logic

import com.deepseek.dsh.core.designsystem.kit.*
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Учёт расходов по снимкам баланса провайдера: у DeepSeek нет API расхода по дням,
 * поэтому каждое чтение баланса записывается, а расход — это сумма уменьшений баланса
 * (пополнения не считаются). Храним не больше 400 снимков.
 */
object BalanceLedger {
    private const val PREFS = "dsh_balance_ledger"

    data class Summary(val today: Double, val month: Double, val daysLeft: Int?)

    private data class Sample(val at: Long, val amount: Double)

    private fun load(context: Context): MutableList<Sample> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("samples", "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Sample(it.optLong("t"), it.optDouble("a")) } }.toMutableList()
    }

    /** Записать текущий баланс и вернуть сводку. */
    fun record(context: Context, amount: Double, now: Long = System.currentTimeMillis()): Summary {
        val list = load(context)
        if (list.lastOrNull()?.amount != amount || now - (list.lastOrNull()?.at ?: 0) > 6 * 3600_000L) list += Sample(now, amount)
        while (list.size > 400) list.removeAt(0)
        val arr = JSONArray().apply { list.forEach { put(JSONObject().put("t", it.at).put("a", it.amount)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("samples", arr.toString()).apply()
        return summarize(list.map { it.at to it.amount }, amount, now)
    }

    /** Сводка по снимкам (время, баланс); вынесено для тестов. */
    fun summarize(samples: List<Pair<Long, Double>>, current: Double, now: Long, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = now - 7 * 86_400_000L
        fun spentSince(from: Long): Double {
            var spent = 0.0
            // Считаем только падения между снимками внутри периода: что было до него — неизвестно когда.
            var prev: Double? = null
            for ((t, a) in samples) {
                if (t < from) continue
                if (prev != null && a < prev) spent += prev - a
                prev = a
            }
            return spent
        }
        val week = spentSince(weekStart)
        val firstAt = samples.firstOrNull()?.first ?: now
        val days = ((now - maxOf(firstAt, weekStart)) / 86_400_000.0).coerceAtLeast(1.0)
        val perDay = week / days
        return Summary(spentSince(dayStart), spentSince(monthStart), if (perDay > 0.0001) (current / perDay).toInt() else null)
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
