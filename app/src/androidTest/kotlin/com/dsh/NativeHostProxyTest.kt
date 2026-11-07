package com.dsh

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Android system proxy must not intercept authentication or RPC to the on-device Host. */
class NativeHostProxyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().decodeToString() }
    }

    @Test fun localAuthenticationAndRpcWorkWithAnUnreachableSystemProxy(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DshApplication
        val status = withContext(Dispatchers.IO) { app.runtimeManager.start(StorageAccess.workspaceRoot(app).absolutePath).getOrThrow() }
        val port = requireNotNull(status.port)
        val previous = shell("settings get global http_proxy").trim()
        try {
            shell("settings put global http_proxy 127.0.0.1:9")
            delay(1500)
            app.cookieJar.clearHost("127.0.0.1", port)
            assertTrue("System proxy intercepted local Host authentication", app.bindLocalHost(port, status.token))
            val raw = app.dshRepository.hostSettings.describeSettingsRaw("local-host")
            assertNotNull("System proxy intercepted local Host RPC", raw)
            assertEquals("Host rejected settings/describe", true,
                Json.parseToJsonElement(raw!!).jsonObject["result"]?.jsonObject?.get("ok")?.jsonPrimitive?.booleanOrNull)
        } finally {
            if (previous in setOf("", "null")) {
                shell("settings put global http_proxy :0")
                shell("settings delete global http_proxy")
            } else {
                require(previous.matches(Regex("[A-Za-z0-9.:-]+")))
                shell("settings put global http_proxy $previous")
            }
        }
    }
}
