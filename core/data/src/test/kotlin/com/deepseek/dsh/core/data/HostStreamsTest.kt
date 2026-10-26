package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.protocol.DshClientProtocol
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Потоки Host (WebSocket) обрываются при перезапуске Host: это не должно ронять приложение. */
class HostStreamsTest {
    private val streamCalls = AtomicInteger()

    /** Host, у которого каждый WebSocket-поток сразу обрывается, как при перезапуске («Connection reset»). */
    private val brokenHost: DshClientProtocol = Proxy.newProxyInstance(
        DshClientProtocol::class.java.classLoader,
        arrayOf(DshClientProtocol::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "subscribeEvents", "forwardedHostEvents", "forwardedWaterfalls", "forwardedEventPayloads" -> emptyFlow<Any>()
            "watchAccount", "sessionControl", "workspaceFollow", "jobList", "jobFollow", "watchClaudeCodeBand" -> flow<Any> {
                streamCalls.incrementAndGet()
                throw IOException("Connection reset")
            }
            "toString" -> "BrokenHost"
            "hashCode" -> 0
            "equals" -> false
            else -> error("unexpected ${m.name}")
        }
    } as DshClientProtocol

    private val noDao: DshDao = Proxy.newProxyInstance(
        DshDao::class.java.classLoader,
        arrayOf(DshDao::class.java),
    ) { _, m, _ -> error("dao ${m.name} is not expected") } as DshDao

    @Test
    fun brokenHostStreamsDoNotCrashTheApp() {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        val repository = DshRepository(noDao, brokenHost, scope)
        repository.subscribeRemoteEvents("local")
        repository.tasks.watchJobs("local", "s1")
        Thread.sleep(300)
        scope.cancel()
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        // account/watch, session/control, workspace/follow и job/list действительно оборвались.
        assertTrue(streamCalls.get() >= 4)
    }

    @Test
    fun keepSubscribedResubscribesAfterFailureAndEnd() = runBlocking {
        val attempts = AtomicInteger()
        val result = runCatching {
            withTimeout(2_000) {
                keepSubscribed(pauseMs = { 1L }) {
                    if (attempts.incrementAndGet() % 2 == 0) throw IOException("reset")
                    if (attempts.get() >= 5) throw IllegalStateException("enough")
                }
            }
        }
        // Обрывы и обычный конец потока не выходят из цикла; выйти можно только отменой (таймаут).
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(attempts.get() > 5)
    }

    @Test
    fun brokenJobFollowEndsWithStatusInsteadOfThrowing() = runBlocking {
        val repository = DshRepository(noDao, brokenHost, CoroutineScope(Dispatchers.Unconfined))
        val frames = repository.tasks.followJob("local", "s1", "job-1").toList()
        assertEquals(1, frames.size)
        val frame = Json.parseToJsonElement(frames.single()) as JsonObject
        assertEquals("status", frame["type"]!!.jsonPrimitive.content)
        assertTrue((frame["job"] as JsonObject)["status"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun deeplyNestedBandDoesNotOverflowTheStack() {
        // Дерево строится в памяти: такую глубину сам kotlinx.serialization не разбирает (это отдельная находка).
        var tree: JsonElement = JsonPrimitive("x")
        repeat(20_000) {
            tree = JsonObject(mapOf("type" to JsonPrimitive("Box"), "children" to JsonArray(listOf(tree))))
        }
        val value = JsonObject(mapOf("generation" to JsonPrimitive(1), "tree" to tree))
        val snapshot = parseBandSnapshot(value)
        assertNotNull(snapshot?.root)
        var node = snapshot?.root
        var levels = 0
        while (node is BandNode.Box) { levels++; node = node.children.firstOrNull() }
        assertTrue(levels <= MAX_BAND_DEPTH + 1)
    }

    @Test
    fun shallowBandIsParsedFully() {
        val value = Json.parseToJsonElement(
            """{"generation":2,"tree":{"type":"Box","children":[{"type":"Text","props":{"text":"hi"}}]}}""",
        )
        val root = parseBandSnapshot(value)?.root as BandNode.Box
        assertEquals("hi", (root.children.single() as BandNode.Text).text)
    }
}
