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
    @Test fun threeFastMessagesCreateOneSessionAndKeepSubmissionOrder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DshApplication>()
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
            assertTrue("created=${created.get()}, sent=$texts, journal=${app.chatRunJournal.records.value}", withTimeoutOrNull(10_000) { complete.await(); true } == true)
            assertEquals(1, created.get())
            assertEquals(listOf("s-1", "s-1", "s-1"), sessions.toList())
            assertEquals(listOf("one", "two", "three"), texts.toList())
        } finally { scope.cancel() }
    }
}
