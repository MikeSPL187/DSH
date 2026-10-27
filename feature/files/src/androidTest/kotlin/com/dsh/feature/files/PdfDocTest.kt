package com.dsh.feature.files

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** PdfDoc на настоящем PdfRenderer: страницы, пропорции, белый фон, битый файл. */
@RunWith(AndroidJUnit4::class)
class PdfDocTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun pdf(): File {
        val doc = PdfDocument()
        listOf(595 to 842, 842 to 595).forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawRect(50f, 50f, 150f, 150f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        return File(dir, "two-pages.pdf").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    @Test
    fun rendersPagesWithTheirRatioOnWhite() = runBlocking {
        val file = pdf()
        // Если PdfDoc не открыл файл, повторить открытие напрямую — чтобы тест показал настоящую причину.
        val doc = PdfDoc.open(file) ?: run {
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).close()
            error("PdfDoc.open вернул null, а PdfRenderer открыл файл (${file.length()} байт)")
        }
        doc.use {
            assertEquals(2, it.pages)
            assertEquals(595f / 842f, it.ratio(0), 0.01f)
            assertEquals(842f / 595f, it.ratio(1), 0.01f)
            val bmp = it.render(0, 600)!!
            assertEquals(600, bmp.width)
            assertEquals(849, bmp.height, 2)
            assertEquals(Color.WHITE, bmp.getPixel(bmp.width - 5, bmp.height - 5))
            assertEquals(Color.BLACK, bmp.getPixel(100 * 600 / 595, 100 * 600 / 595))
            assertNotNull(it.render(1, 5000)?.also { big -> assertEquals(PdfDoc.MAX_PAGE_PX, big.width) })
        }
    }

    @Test
    fun brokenFileIsNull() {
        val bad = File(dir, "broken.pdf").apply { writeText("%PDF-1.4 not really") }
        assertNull(PdfDoc.open(bad))
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) = org.junit.Assert.assertTrue("$actual ≉ $expected", kotlin.math.abs(expected - actual) <= delta)
}
