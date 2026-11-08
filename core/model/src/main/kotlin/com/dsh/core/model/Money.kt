package com.dsh.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlinx.serialization.json.*

data class Money(val amount: BigDecimal, val currency: String) {
    fun formatted(locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f %s", amount.setScale(2, RoundingMode.HALF_UP), currency)
}

/** Currency choice does not depend on array order or a zero balance in an unused wallet. */
fun parseDeepSeekBalance(body: String): Money? = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val balances = root["balance_infos"]?.jsonArray.orEmpty().mapNotNull { row ->
        val info = row as? JsonObject ?: return@mapNotNull null
        val currency = info["currency"]?.jsonPrimitive?.contentOrNull?.uppercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return@mapNotNull null
        val amount = info["total_balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 } ?: return@mapNotNull null
        Money(amount, currency)
    }
    val candidates = balances.filter { it.amount.signum() > 0 }.ifEmpty { balances }
    candidates.sortedWith(compareBy<Money> { when (it.currency) { "CNY" -> 0; "USD" -> 1; else -> 2 } }.thenBy { it.currency }).firstOrNull()
}.getOrNull()
