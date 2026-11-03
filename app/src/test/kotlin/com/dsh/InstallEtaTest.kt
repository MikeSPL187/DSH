package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Оценка оставшегося времени установки Linux: по скорости текущего этапа, а не с начала установки. */
class InstallEtaTest {
    @Test
    fun `no estimate in the first seconds of a stage`() {
        assertNull(installEta(stageStartMs = 0, stageStartProgress = 0.46f, nowMs = 3_000, progress = 0.50f))
    }

    @Test
    fun `no estimate while the stage has not moved`() {
        assertNull(installEta(stageStartMs = 0, stageStartProgress = 0.46f, nowMs = 60_000, progress = 0.465f))
    }

    @Test
    fun `slow unpack after instant download is estimated by unpack speed`() {
        // Загрузка заняла секунду (0 → 46 %), распаковка идёт 1 % в минуту: осталось ~44 мин, а не «≈ 5 с».
        val eta = installEta(stageStartMs = 0, stageStartProgress = 0.46f, nowMs = 120_000, progress = 0.48f)!!
        assertEquals(52 * 60, eta, 5)
    }

    @Test
    fun `nothing near the end`() {
        assertNull(installEta(stageStartMs = 0, stageStartProgress = 0.5f, nowMs = 60_000, progress = 0.995f))
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
}
