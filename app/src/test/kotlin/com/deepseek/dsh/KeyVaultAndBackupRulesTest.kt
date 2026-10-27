package com.deepseek.dsh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deepseek.dsh.feature.settings.logic.ApiKeyVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KeyVaultAndBackupRulesTest {
    /**
     * G-36: Keystore не зашифровал ключ (в Robolectric AndroidKeyStore нет — тот же сбой, что на части телефонов) —
     * «ключ …abcd сохранён» не показывается. Раньше last4 сохранялся без зашифрованного ключа.
     */
    @Test
    fun keyIsNotShownSavedWhenEncryptionFails() {
        val context: Context = ApplicationProvider.getApplicationContext()
        ApiKeyVault.init(context)
        assertFalse(ApiKeyVault.save("TEST_KEY", "sk-test-0000abcd"))
        assertFalse(ApiKeyVault.has("TEST_KEY"))
    }

    /** G-37: перенос «телефон → телефон» и облачная копия исключают данные DSH. */
    @Test
    fun manifestExcludesDataFromTransfer() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<device-transfer>") && rules.contains("<cloud-backup>"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ThirdPartyPluginsPolicyTest {
    /** G-48: плагины из сети по умолчанию не ставятся; файл с телефона — явный выбор пользователя. */
    @Test
    fun networkPluginsAreOffByDefault() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertFalse(com.deepseek.dsh.runtime.bridge.BridgePolicy.isThirdPartyPluginsAllowed(context))
        assertTrue(isLocalPluginSpec("/workspace/Default/my-plugin"))
        assertFalse(isLocalPluginSpec("@someone/dsh-plugin@1.2.3"))
        assertFalse(isLocalPluginSpec("github:owner/repo"))
        com.deepseek.dsh.runtime.bridge.BridgePolicy.setThirdPartyPluginsAllowed(context, true)
        assertTrue(com.deepseek.dsh.runtime.bridge.BridgePolicy.isThirdPartyPluginsAllowed(context))
    }
}
