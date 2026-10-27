package com.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanReviewParseTest {
    private val review = Json.parseToJsonElement(
        """[{"id":"plan-review","header":"Plan review","question":"Approve this plan and leave plan mode?",
            "detail":"# Fix build\n\n1. Bump `kotlin` in `gradle/libs.versions.toml`\n2. Run tests",
            "options":[{"label":"Approve"},{"label":"Keep planning"}],
            "intent":{"kind":"plan-review","approve":"Approve","callId":"c1"}}]""",
    ) as JsonArray

    @Test
    fun `plan review question becomes a plan and answers carry the host labels`() {
        val plan = planReviewOf("e1", review)!!
        assertEquals("Fix build", plan.title)
        assertEquals(2, plan.steps)
        assertEquals(1, plan.files)
        val approve = Json.parseToJsonElement(planReviewAnswer(plan, null)).jsonObject["answers"]!!.jsonArray.single().jsonObject
        assertEquals("plan-review", approve["id"]!!.jsonPrimitive.content)
        assertEquals("Approve", approve["selected"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull(approve["custom"])
        val refine = Json.parseToJsonElement(planReviewAnswer(plan, "split step 1")).jsonObject["answers"]!!.jsonArray.single().jsonObject
        assertEquals("Keep planning", refine["selected"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("split step 1", refine["custom"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ordinary questions stay questions`() {
        val plain = Json.parseToJsonElement("""[{"id":"q","question":"Which?","options":[{"label":"A"},{"label":"B"}]}]""") as JsonArray
        assertNull(planReviewOf("e2", plain))
    }
}
