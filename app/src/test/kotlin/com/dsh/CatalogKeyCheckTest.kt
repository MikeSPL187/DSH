package com.dsh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogKeyCheckTest {
    @Test
    fun parsesModelsOfEachProvider() {
        assertEquals(listOf("claude-sonnet-4-5", "claude-haiku-4-5"), catalogModels("anthropic", """{"data":[{"id":"claude-sonnet-4-5"},{"id":"claude-haiku-4-5"}],"has_more":false}"""))
        assertEquals(listOf("gpt-5"), catalogModels("openai", """{"object":"list","data":[{"id":"gpt-5"}]}"""))
        assertEquals(listOf("gemini-2.5-pro"), catalogModels("google", """{"models":[{"name":"models/gemini-2.5-pro"}]}"""))
        assertEquals(emptyList<String>(), catalogModels("openrouter", """{"data":{"label":"x"}}"""))
        assertEquals(emptyList<String>(), catalogModels("openai", "not json"))
    }

    @Test
    fun unknownProviderIsRejectedWithoutNetwork() = runBlocking {
        assertEquals(com.dsh.feature.runtime.KeyCheck.Bad, checkCatalogKey("openai-codex", "sk-anything"))
    }
}
