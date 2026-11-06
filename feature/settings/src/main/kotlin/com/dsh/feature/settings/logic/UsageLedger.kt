package com.dsh.feature.settings.logic

import android.content.Context
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/** Durable provider usage. Old token-only entries remain explicitly unpriced. */
object UsageLedger {
    data class Entry(
        val key: String, val at: Long, val tokens: Long,
        val provider: String? = null, val model: String? = null,
        val input: Long? = null, val output: Long? = null,
        val cacheRead: Long? = null, val cacheWrite: Long? = null,
        val estimate: ModelTariffs.Estimate? = null,
    )
    data class Costs(val currencies: Map<String, Pair<BigDecimal, BigDecimal>> = emptyMap(), val unknown: Int = 0) {
        /** Decimal, not binary floating point; retain small but nonzero charges. */
        fun display(): String? = currencies.takeIf { it.isNotEmpty() }?.entries?.sortedBy { it.key }?.joinToString(" · ") { (currency, range) ->
            fun amount(n: BigDecimal): String = if (n.signum() > 0 && n < BigDecimal("0.0001")) "<0.0001"
                else n.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            val low = amount(range.first)
            val high = amount(range.second)
            "≈ " + (if (low == high) low else "$low–$high") + " $currency"
        }
    }
    data class Summary(val today: Long, val month: Long, val todayCost: Costs = Costs(), val monthCost: Costs = Costs())
    // Same preference file: migration reads v1 records, writes optional v2 fields, never reprices history.
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = revision
    private const val PREFS = "dsh_usage_ledger_v1"
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    fun parse(host: String, session: String, at: Long, data: JsonObject): Entry? {
        val usage = data["usage"] as? JsonObject ?: (data["stream"] as? JsonArray)?.asReversed()?.firstNotNullOfOrNull { item ->
            val chunk = (item as? JsonObject)?.get("chunk") as? JsonObject
            if (chunk?.string("type") == "usage") chunk["usage"] as? JsonObject else null
        } ?: return null
        if (listOf("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "totalTokens").any {
                usage.containsKey(it) && usage.number(it) == null
            }) return null
        val turn = data.number("turn") ?: return null
        val step = data.number("step") ?: return null
        val input = usage.number("inputTokens") ?: return null
        val output = usage.number("outputTokens") ?: return null
        val read = usage.number("cacheReadTokens")
        val write = usage.number("cacheWriteTokens")
        // Missing buckets cannot be silently treated as zero without an authoritative total.
        val knownTotal = runCatching { listOf(input, output, read ?: 0, write ?: 0).fold(0L, Math::addExact) }.getOrNull() ?: return null
        val tokens = usage.number("totalTokens") ?: if (read != null && write != null) knownTotal else return null
        if (tokens < knownTotal || (read != null && write != null && tokens != knownTotal) || at <= 0) return null
        val source = (data["message"] as? JsonObject)?.get("source") as? JsonObject
        val provider = source?.string("provider")
        val model = source?.string("model")
        val exact = tokens == knownTotal
        return Entry("$host/$session/$turn/$step/${data.number("_attempt") ?: 0}", at, tokens,
            provider, model, input, output, read, write,
            if (exact) ModelTariffs.estimate(provider, model, at, input, output, read ?: 0, write ?: 0) else null)
    }

    @Synchronized fun record(context: Context, entry: Entry): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = load(context)
        // Replayed historical events retain the tariff snapshot captured on first receipt.
        val previous = old.firstOrNull { it.key == entry.key }
        val retained = if (previous != null && previous.at == entry.at && previous.tokens == entry.tokens) previous else entry
        val entries = old.filterNot { it.key == entry.key } + retained
        val zone = ZoneId.systemDefault()
        val cutoff = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate().minusMonths(2)
            .withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Persist before returning: a process kill must not lose the latest completed request.
        return prefs.edit().putString("entries", encode(entries.filter { it.at >= cutoff })).commit().also {
            if (it) revision.value += 1
        }
    }

    fun peek(context: Context, now: Long = System.currentTimeMillis()) = summarize(load(context), now)
    private fun load(context: Context) = decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "[]").orEmpty())

    internal fun encode(entries: List<Entry>): String = JsonArray(entries.map { e -> buildJsonObject {
        put("key", e.key); put("at", e.at); put("tokens", e.tokens)
        e.provider?.let { put("provider", it) }; e.model?.let { put("model", it) }
        e.input?.let { put("input", it) }; e.output?.let { put("output", it) }
        e.cacheRead?.let { put("cacheRead", it) }; e.cacheWrite?.let { put("cacheWrite", it) }
        e.estimate?.let { cost -> put("estimate", buildJsonObject {
            put("currency", cost.currency); put("minimum", cost.minimum.toPlainString())
            put("maximum", cost.maximum.toPlainString()); put("tariff", cost.tariff)
        }) }
    } }).toString()

    internal fun decode(raw: String): List<Entry> = runCatching {
        (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { value -> runCatching {
            val e = value.jsonObject
            val c = e["estimate"] as? JsonObject
            val cost = c?.let {
                val minimum = BigDecimal(it.string("minimum")!!)
                val maximum = BigDecimal(it.string("maximum")!!)
                require(minimum.signum() >= 0 && maximum >= minimum)
                ModelTariffs.Estimate(it.string("currency")!!, minimum, maximum, it.string("tariff")!!)
            }
            Entry(e.string("key")!!, e.number("at")!!, e.number("tokens")!!,
                e.string("provider"), e.string("model"), e.number("input"), e.number("output"),
                e.number("cacheRead"), e.number("cacheWrite"), cost)
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun summarize(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val startMonth = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val unique = entries.associateBy { it.key }.values.filter { it.at <= now }
        val today = unique.filter { it.at >= startDay }
        val month = unique.filter { it.at >= startMonth }
        fun costs(rows: List<Entry>): Costs {
            val totals = rows.mapNotNull { it.estimate }.groupBy { it.currency }.mapValues { (_, estimates) ->
                estimates.fold(BigDecimal.ZERO to BigDecimal.ZERO) { sum, e -> sum.first + e.minimum to sum.second + e.maximum }
            }
            return Costs(totals, rows.count { it.estimate == null })
        }
        return Summary(today.sumOf { it.tokens }, month.sumOf { it.tokens }, costs(today), costs(month))
    }
}
