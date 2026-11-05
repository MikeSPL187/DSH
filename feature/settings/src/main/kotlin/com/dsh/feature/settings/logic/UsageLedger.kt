package com.dsh.feature.settings.logic

import android.content.Context
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Provider-reported usage, partitioned by Host/session/attempt; replay replaces an entry. */
object UsageLedger {
    data class Entry(val key: String, val at: Long, val tokens: Long)
    data class Summary(val today: Long, val month: Long)
    private const val PREFS = "dsh_usage_ledger_v1"

    fun parse(host: String, session: String, at: Long, data: JsonObject): Entry? {
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val usage = data["usage"] as? JsonObject ?: (data["stream"] as? JsonArray)?.asReversed()?.firstNotNullOfOrNull { item ->
            val chunk = (item as? JsonObject)?.get("chunk") as? JsonObject
            if ((chunk?.get("type") as? JsonPrimitive)?.contentOrNull == "usage") chunk["usage"] as? JsonObject else null
        } ?: return null
        val turn = data.number("turn") ?: return null
        val step = data.number("step") ?: return null
        val input = usage.number("inputTokens") ?: return null
        val output = usage.number("outputTokens") ?: return null
        val tokens = usage.number("totalTokens") ?: runCatching {
            listOf(input, output, usage.number("cacheReadTokens") ?: 0, usage.number("cacheWriteTokens") ?: 0).fold(0L, Math::addExact)
        }.getOrNull() ?: return null
        if (at <= 0) return null
        return Entry("$host/$session/$turn/$step/${data.number("_attempt") ?: 0}", at, tokens)
    }

    @Synchronized fun record(context: Context, entry: Entry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = load(context).filterNot { it.key == entry.key } + entry
        val cutoff = Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault()).toLocalDate().minusMonths(2).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val json = JSONArray().apply { entries.filter { it.at >= cutoff }.forEach { put(JSONObject().put("key", it.key).put("at", it.at).put("tokens", it.tokens)) } }
        prefs.edit().putString("entries", json.toString()).apply()
    }

    fun peek(context: Context, now: Long = System.currentTimeMillis()) = summarize(load(context), now)

    private fun load(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.getString("key"), it.getLong("at"), it.getLong("tokens")) } }
    }.getOrDefault(emptyList())

    fun summarize(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val startMonth = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val unique = entries.associateBy { it.key }.values.filter { it.at <= now }
        return Summary(unique.filter { it.at >= startDay }.sumOf { it.tokens }, unique.filter { it.at >= startMonth }.sumOf { it.tokens })
    }
}
