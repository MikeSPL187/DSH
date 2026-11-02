package com.deepseek.dsh

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Баланс DeepSeek: выбирается валюта с деньгами, а не первая в списке. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BalanceParseTest {
    @Test
    fun `yuan balance is shown even when USD row comes first with zero`() {
        val body = """{"is_available":true,"balance_infos":[
            {"currency":"USD","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"},
            {"currency":"CNY","total_balance":"91.52","granted_balance":"0.00","topped_up_balance":"91.52"}]}"""
        assertEquals(91.52 to "¥", pickBalance(body))
    }

    @Test
    fun `single currency and broken body`() {
        assertEquals(12.4 to "$", pickBalance("""{"balance_infos":[{"currency":"USD","total_balance":"12.40"}]}"""))
        assertEquals(null, pickBalance("not json"))
    }
}
