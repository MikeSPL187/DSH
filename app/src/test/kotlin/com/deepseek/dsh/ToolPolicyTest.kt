package com.deepseek.dsh

import com.deepseek.dsh.feature.chat.ApprovalTier
import com.deepseek.dsh.feature.chat.approvalTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPolicyTest {
    @Test
    fun `arguments sent as an object or as a string are both recognized`() {
        val asObject = Json.parseToJsonElement("""{"command":"rm notes.txt"}""")
        val asString = JsonPrimitive("""{"command":"rm notes.txt"}""")
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", toolArgsText(asObject)))
        assertEquals(ApprovalTier.DESTRUCTIVE, approvalTier("bash", toolArgsText(asString)))
        assertNull(toolArgsText(null))
    }
}
