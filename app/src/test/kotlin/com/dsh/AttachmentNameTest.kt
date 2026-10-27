package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

/** G-21: имя вложения — одно слово в ссылке `@attachments/<имя>`, без пути и управляющих символов. */
class AttachmentNameTest {
    @Test
    fun spacesDoNotBreakTheReference() {
        assertEquals("My_Photo.pdf", safeAttachmentName("My Photo.pdf"))
        assertEquals("a_b.txt", safeAttachmentName("  a \t b.txt "))
        assertEquals("evil.sh", safeAttachmentName("../../evil.sh"))
        assertEquals("attachment", safeAttachmentName(".."))
        assertEquals("x.png", safeAttachmentName("x\u0000.png"))
    }
}
