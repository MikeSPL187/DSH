package com.dsh.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TrTest {
    private val original = Tr.language

    @After
    fun restore() {
        Tr.language = original
    }

    @Test
    fun picksTextForInterfaceLanguage() {
        Tr.language = { "ru" }
        assertEquals("Сетевая ошибка", Tr.t("Network error", "Сетевая ошибка"))
        Tr.language = { "zh" }
        assertEquals("Network error", Tr.t("Network error", "Сетевая ошибка"))
        Tr.language = { "de" }
        assertEquals("Network error", Tr.t("Network error", "Сетевая ошибка"))
    }

    @Test
    fun brokenLanguageProviderFallsBackToEnglish() {
        Tr.language = { error("no context") }
        assertEquals("Network error", Tr.t("Network error", "Сетевая ошибка"))
    }
}
