package com.dsh.feature.files

import android.graphics.Color
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

    /**
     * Двухстраничный PDF (A4 книжная и альбомная) с чёрным квадратом 100 × 100 в 50 pt от левого верхнего угла.
     * Собран вручную с точной таблицей xref — как обычный файл с диска, без PdfDocument.
     */
    private fun pdf(): File {
        val pages = listOf(595 to 842, 842 to 595)
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
        pages.forEachIndexed { i, (w, h) ->
            val content = "0 0 0 rg 50 ${h - 150} 100 100 re f"
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Contents ${4 + i * 2} 0 R >>"
            objects += "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        }
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(dir, "two-pages.pdf").apply { writeBytes(out.toString().toByteArray(Charsets.US_ASCII)) }
    }

    @Test
    fun rendersPagesWithTheirRatioOnWhite() = runBlocking {
        val doc = PdfDoc.open(pdf())!!
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

    /** Android 8: неудачный PdfRenderer ломал все следующие — битые файлы не должны до него доходить. */
    @Test
    fun brokenFilesAreNullAndDoNotBreakTheNextPdf() = runBlocking {
        assertNull(PdfDoc.open(File(dir, "broken.pdf").apply { writeText("%PDF-1.4 not really") }))
        val whole = pdf().readBytes()
        assertNull(PdfDoc.open(File(dir, "cut.pdf").apply { writeBytes(whole.copyOf(whole.size / 2)) }))
        PdfDoc.open(pdf())!!.use { assertEquals(2, it.pages) }
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) = org.junit.Assert.assertTrue("$actual ≉ $expected", kotlin.math.abs(expected - actual) <= delta)
}
