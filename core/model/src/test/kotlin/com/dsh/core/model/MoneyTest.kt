package com.dsh.core.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MoneyTest {
    @Test fun currencyDoesNotDependOnWalletOrder() {
        val usd = """{"currency":"USD","total_balance":"0.00"}"""
        val cny = """{"currency":"CNY","total_balance":"91.30"}"""
        for (rows in listOf("$usd,$cny", "$cny,$usd")) {
            assertEquals("91.30 CNY", parseDeepSeekBalance("""{"balance_infos":[$rows]}""")?.formatted(Locale.US))
        }
    }
    @Test fun missingOrInvalidBalanceIsUnknownInsteadOfZero() {
        for (raw in listOf("{}", "bad json", """{"balance_infos":[{"currency":"USD","total_balance":"NaN"}]}""")) assertNull(parseDeepSeekBalance(raw))
    }
}
