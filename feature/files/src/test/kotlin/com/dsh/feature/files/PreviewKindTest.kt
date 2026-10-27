package com.dsh.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewKindTest {
    @Test
    fun `preview kind follows the extension`() {
        assertEquals(PreviewKind.MARKDOWN, previewKindOf("README.md"))
        assertEquals(PreviewKind.HTML, previewKindOf("index.HTM"))
        assertEquals(PreviewKind.PDF, previewKindOf("spec.pdf"))
        assertEquals(PreviewKind.NONE, previewKindOf("Makefile"))
        assertEquals(PreviewKind.NONE, previewKindOf("app.kt"))
    }
}
