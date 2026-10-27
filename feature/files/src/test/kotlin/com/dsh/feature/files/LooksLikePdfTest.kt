package com.dsh.feature.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LooksLikePdfTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(text: String) = tmp.newFile().apply { writeBytes(text.toByteArray(Charsets.ISO_8859_1)) }

    private val whole = "%PDF-1.4\n" + "x".repeat(4000) + "\nxref\n0 1\ntrailer\n<< >>\nstartxref\n9\n%%EOF\n"

    @Test
    fun `whole pdf passes, with or without bytes before the header`() {
        assertTrue(PdfDoc.looksLikePdf(file(whole)))
        assertTrue(PdfDoc.looksLikePdf(file("ï»¿" + whole)))
    }

    @Test
    fun `not a pdf, truncated, empty and missing files are rejected before PdfRenderer`() {
        assertFalse(PdfDoc.looksLikePdf(file("%PDF-1.4 not really")))
        assertFalse(PdfDoc.looksLikePdf(file(whole.substring(0, 3000))))
        assertFalse(PdfDoc.looksLikePdf(file("<html>" + whole.substring(9))))
        assertFalse(PdfDoc.looksLikePdf(file("")))
        assertFalse(PdfDoc.looksLikePdf(tmp.root.resolve("missing.pdf")))
    }
}
