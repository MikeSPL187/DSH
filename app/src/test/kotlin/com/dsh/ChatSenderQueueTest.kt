package com.dsh

import androidx.compose.runtime.mutableStateListOf
import androidx.test.core.app.ApplicationProvider
import com.dsh.core.data.DshDao
import com.dsh.core.data.DshRepository
import com.dsh.core.model.*
import com.dsh.core.protocol.DshClientProtocol
import com.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DshApplication::class)
class ChatSenderQueueTest {
    private suspend fun pumpUntil(done: () -> Boolean) = withTimeout(10_000) {
        while (!done()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    @Test fun threeFastMessagesCreateOneSessionAndKeepSubmissionOrder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DshApplication>()
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setDefaultNetworkActive(true)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        val created = AtomicInteger()
        val texts = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<String>()
        val complete = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, args ->
            when (method.name) {
                "createSession" -> RemoteResult.Success(SessionInfo("local-host", "s-${created.incrementAndGet()}", "Chat", null, "idle", System.currentTimeMillis()))
                "selectModel" -> RemoteResult.Success(true)
                "sendMessage" -> {
                    sessions += args[1] as String
                    texts += args[2] as String
                    if (texts.size == 3) complete.complete(Unit)
                    RemoteResult.Success(ChatMessage("local-host", args[1] as String, "echo", MessageRole.USER, args[2] as String))
                }
                else -> error("Unexpected client call ${method.name}")
            }
        } as DshClientProtocol
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, _, _ -> Unit } as DshDao
        try {
            val ui = AppUiState(AppSettings(), app.filesDir, true)
            val sender = ChatSender(app, scope, DshRepository(dao, client, scope), ui, AppRuntimeUiState(false, 0f to ""), app.runtimeManager, mutableStateListOf(), { app.filesDir })
            listOf("one", "two", "three").forEach { sender.send(it, ModelChoice("m", "p")) }
            // Destroying the screen scope must not cancel application-owned submissions.
            scope.cancel()
            pumpUntil { complete.isCompleted }
            assertTrue("created=${created.get()}, sent=$texts, journal=${app.chatRunJournal.records.value}", complete.isCompleted)
            assertEquals(1, created.get())
            assertEquals(listOf("s-1", "s-1", "s-1"), sessions.toList())
            assertEquals(listOf("one", "two", "three"), texts.toList())
        } finally { scope.cancel() }
    }
    @Test fun offlineMessageDoesNotReachTheHostOrBecomeAnAuthorizationError() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DshApplication>()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, _ ->
            error("Offline request reached ${method.name}")
        } as DshClientProtocol
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, _, _ -> Unit } as DshDao
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sender = ChatSender(app, scope, DshRepository(dao, client, scope), AppUiState(AppSettings(), app.filesDir, true), AppRuntimeUiState(false, 0f to ""), app.runtimeManager, mutableStateListOf(), { app.filesDir })
            sender.send("offline fixture", ModelChoice("m", "p"))
            pumpUntil { app.chatRunJournal.records.value.any { it.text == "offline fixture" && it.status == RunStatus.FAILED } }
            val record = app.chatRunJournal.records.value.single { it.text == "offline fixture" }
            assertEquals(com.dsh.core.model.Tr.t("No network connection. Connect and retry.", "Нет подключения к сети. Подключитесь и повторите."), record.error)
            assertFalse(record.error.orEmpty().contains("401"))
        } finally { scope.cancel() }
    }

    @Test fun retryAfterModelSelectionFailureReappliesTheModelBeforeSending() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DshApplication>()
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(connectivity.activeNetwork, caps)
        val selected = AtomicInteger()
        val sent = AtomicInteger()
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, _ ->
            when (method.name) {
                "createSession" -> RemoteResult.Success(SessionInfo("local-host", "retry-model", "Chat", null, "idle", System.currentTimeMillis()))
                "selectModel" -> if (selected.incrementAndGet() == 1) RemoteResult.Failure("qa/rejected", "selection rejected") else RemoteResult.Success(true)
                "sendMessage" -> { sent.incrementAndGet(); RemoteResult.Success(ChatMessage("local-host", "retry-model", "echo", MessageRole.USER, "retry fixture")) }
                else -> error("Unexpected call ${method.name}")
            }
        } as DshClientProtocol
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, _, _ -> Unit } as DshDao
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sender = ChatSender(app, scope, DshRepository(dao, client, scope), AppUiState(AppSettings(), app.filesDir, true), AppRuntimeUiState(false, 0f to ""), app.runtimeManager, mutableStateListOf(), { app.filesDir })
            sender.send("retry fixture", ModelChoice("chosen", "provider"))
            pumpUntil { app.chatRunJournal.records.value.any { it.text == "retry fixture" && it.status == RunStatus.FAILED } }
            val failed = app.chatRunJournal.records.value.single { it.text == "retry fixture" }
            assertTrue(failed.requiresModelSelection)
            assertEquals(0, sent.get())
            sender.retry(failed.id)
            pumpUntil { sent.get() == 1 }
            assertEquals(2, selected.get())
        } finally { scope.cancel() }
    }

    @Test fun queuedMessageRetriesFailedInitialModelSelectionBeforeSending() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DshApplication>()
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(connectivity.activeNetwork, caps)
        val selected = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        val client = Proxy.newProxyInstance(DshClientProtocol::class.java.classLoader, arrayOf(DshClientProtocol::class.java)) { _, method, args ->
            when (method.name) {
                "createSession" -> RemoteResult.Success(SessionInfo("local-host", "queued-model", "Chat", null, "idle", System.currentTimeMillis()))
                "selectModel" -> if (selected.incrementAndGet() < 3) RemoteResult.Failure("qa/rejected", "selection rejected") else RemoteResult.Success(true)
                "sendMessage" -> {
                    assertEquals("A queued prompt reached the Host before its model was selected", 3, selected.get())
                    sent += args[2] as String
                    RemoteResult.Success(ChatMessage("local-host", "queued-model", "echo", MessageRole.USER, args[2] as String))
                }
                else -> error("Unexpected call ${method.name}")
            }
        } as DshClientProtocol
        val dao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, _, _ -> Unit } as DshDao
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sender = ChatSender(app, scope, DshRepository(dao, client, scope), AppUiState(AppSettings(), app.filesDir, true), AppRuntimeUiState(false, 0f to ""), app.runtimeManager, mutableStateListOf(), { app.filesDir })
            listOf("first", "second", "third").forEach { sender.send(it, ModelChoice("chosen", "provider")) }
            pumpUntil { sent.isNotEmpty() }
            assertEquals(3, selected.get())
            assertEquals(listOf("third"), sent.toList())
            val failed = app.chatRunJournal.records.value.filter { it.status == RunStatus.FAILED }
            assertEquals(listOf("first", "second"), failed.map { it.text })
            assertTrue("Successful session setup must also clear failed requests' stale setup flags", failed.none { it.requiresModelSelection })
        } finally { scope.cancel() }
    }

}
