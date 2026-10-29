package com.dsh

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.data.DshDao
import com.dsh.core.data.DshRepository
import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.protocol.DshClientProtocol
import com.dsh.core.protocol.RemoteResult
import com.dsh.feature.settings.ModelProviderUi
import com.dsh.feature.settings.logic.ApiKeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Провайдеры моделей: отказ Host не выдаётся за успех, ключи не путаются (QA DSH 1.1.0: DSH-05, 06, 07). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelProvidersTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val calls = mutableListOf<String>()
    private lateinit var context: Application

    private fun provider(id: String = "qa-provider", configured: Boolean = true) = ModelProviderUi(
        id = id, name = id, models = emptyList(), keyRef = "QA_PROVIDER_API_KEY", keyConfigured = configured,
        settingsNs = "llm-pi-ai", settingsPath = listOf("providers", id), baseUrl = "https://qa.invalid/v1",
        declared = false, configured = configured,
    )

    /** Host отвечает [replies] по имени метода; остальное — `ok: true`. */
    private fun providers(replies: Map<String, RemoteResult<String>>): ModelProviders {
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, m, args ->
            when (m.name) {
                "rpcRaw" -> (args[1] as String).let { method ->
                    calls += method
                    replies[method] ?: RemoteResult.Success("""{"result":{"ok":true,"value":{}}}""")
                }
                "getModelCatalog" -> RemoteResult.Success(ModelCatalogInfo(emptyList(), emptyList(), ""))
                else -> error("unexpected call: ${m.name}")
            }
        } as DshClientProtocol
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, _, _ -> Unit } as DshDao
        return ModelProviders(DshRepository(dao, client, scope))
    }

    /** Локальная отметка о ключе (Keystore в Robolectric нет — проверяется согласованность метаданных). */
    private fun seedKey(ref: String) {
        context.getSharedPreferences("dsh_key_vault", 0).edit()
            .putString("$ref.last4", "2345").putString("$ref.enc", "QA-CIPHERTEXT-FIXTURE").commit()
        assertTrue(ApiKeyVault.has(ref))
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ApiKeyVault.init(context)
        ApiKeyVault.clearAll()
    }

    @After fun tearDown() {
        scope.cancel()
        ApiKeyVault.clearAll()
    }

    @Test
    fun `rejected provider settings are not reported as saved`() = runBlocking {
        val rejected = mapOf("settings/mutate" to RemoteResult.Success("""{"result":{"ok":false,"error":{"code":"settings/rejected"}}}"""))
        assertFalse(providers(rejected).save("local-host", provider(), "", baseUrl = "https://qa.invalid/v1", models = listOf("qa-model")))
    }

    @Test
    fun `new provider is rolled back when its settings are rejected`() = runBlocking {
        val rejected = mapOf("settings/mutate" to RemoteResult.Success("""{"result":{"ok":false,"error":{"code":"gateway/input-invalid"}}}"""))
        assertFalse(providers(rejected).save("local-host", provider(configured = false), "sk-qa-synthetic", baseUrl = "https://qa.invalid/v1"))
        assertEquals(listOf("credentials/set", "settings/mutate", "credentials/unset"), calls.filter { it in setOf("credentials/set", "credentials/unset", "settings/mutate") })
        assertFalse(ApiKeyVault.has("QA_PROVIDER_API_KEY"))
    }

    @Test
    fun `accepted provider is saved`() = runBlocking {
        assertTrue(providers(emptyMap()).save("local-host", provider(), "", baseUrl = "https://qa.invalid/v1"))
    }

    @Test
    fun `failed remote key deletion keeps the local key status`() = runBlocking {
        val p = provider()
        seedKey(p.keyRef)
        val offline = mapOf("credentials/unset" to RemoteResult.Failure("qa/offline", "network disconnected"))
        assertFalse(providers(offline).deleteKey("local-host", p))
        assertTrue("Host still keeps the key, so the app must not show it as deleted", ApiKeyVault.has(p.keyRef))
        assertFalse(providers(offline).remove("local-host", p))
        assertTrue(ApiKeyVault.has(p.keyRef))
    }

    @Test
    fun `confirmed key deletion clears the local key status`() = runBlocking {
        val p = provider()
        seedKey(p.keyRef)
        assertTrue(providers(emptyMap()).deleteKey("local-host", p))
        assertFalse(ApiKeyVault.has(p.keyRef))
    }

    @Test
    fun `custom gateway named after DeepSeek is not the official provider`() {
        assertFalse(isDeepSeek(provider("my-deepseek-proxy").copy(keyRef = "MY_DEEPSEEK_PROXY_API_KEY")))
        assertTrue(isDeepSeek(provider("deepseek-official").copy(keyRef = "DEEPSEEK_API_KEY")))
        assertTrue(isDeepSeek(provider("deepseek").copy(keyRef = "DEEPSEEK_API_KEY")))
    }

    @Test
    fun `custom provider id never takes an existing or official slot`() {
        assertEquals("my-deepseek-proxy", customProviderId("My DeepSeek proxy", emptySet()))
        assertEquals("deepseek-custom", customProviderId("DeepSeek", emptySet()))
        assertEquals("openrouter-custom", customProviderId("OpenRouter", setOf("openrouter")))
        assertEquals("openrouter-custom-2", customProviderId("OpenRouter", setOf("openrouter", "openrouter-custom")))
        assertEquals("custom-api", customProviderId("!!!", emptySet()))
    }
}
