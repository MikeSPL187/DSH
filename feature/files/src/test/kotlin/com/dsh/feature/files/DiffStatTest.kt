package com.dsh.feature.files

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** «+n −m» без нулевой стороны: у пустого нового файла раньше стояло «+0». */
class DiffStatTest {
    private fun stat(a: Int, d: Int) = diffStat(a, d, Color.Green, Color.Red).text

    @Test
    fun zeroSidesAreNotShown() {
        assertEquals("", stat(0, 0))
        assertEquals("+3", stat(3, 0))
        assertEquals("−2", stat(0, 2))
        assertEquals("+3 −2", stat(3, 2))
    }
}
