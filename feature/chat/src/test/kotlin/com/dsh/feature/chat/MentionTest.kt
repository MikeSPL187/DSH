package com.dsh.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.dsh.core.model.ReferenceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionTest {
    @Test
    fun `trailing mention is found only at a word start and before the next space`() {
        assertEquals(MentionQuery(7, "ap", false), trailingMention("fix @x @ap"))
        assertEquals(MentionQuery(0, "", false), trailingMention("@"))
        assertNull(trailingMention("mail@host"))
        assertNull(trailingMention("@app/a.kt "))
        assertNull(trailingMention("@[Tests](dsh-session:abc)"))
        val quoted = trailingMention("read @\"my no")!!
        assertTrue(quoted.quoted && quoted.filesOnly)
        assertEquals("my no", quoted.query)
        assertNull(trailingMention("read @\"my notes.md\""))
        assertEquals("app/src/", trailingMention("@app/src/Ma")!!.dir)
    }

    @Test
    fun `pick replaces the query and drilling keeps the list open`() {
        val q = trailingMention("see @ap")!!
        val dir = ReferenceCandidate(ReferenceCandidate.Kind.DIRECTORY, "app/", "@app/")
        assertEquals("see @app/ ", applyMention("see @ap", q, dir))
        assertEquals("see @app/", drillMention("see @ap", q, "app/"))
        assertEquals("see @\"my dir/\"".dropLast(1), drillMention("see @my", trailingMention("see @my")!!, "my dir/"))
    }

    @Test
    fun `session mention is shown as its title and the cursor maps around it`() {
        val raw = "ask @[Tests \\] one](dsh-session:YWJj) now"
        val t = MentionTransformation(SpanStyle(), SpanStyle()).filter(AnnotatedString(raw))
        assertEquals("ask @Tests ] one now", t.text.text)
        assertEquals(raw.length, t.offsetMapping.transformedToOriginal(t.text.text.length))
        assertEquals(t.text.text.length, t.offsetMapping.originalToTransformed(raw.length))
        assertEquals(4, t.offsetMapping.originalToTransformed(4))
    }
}
