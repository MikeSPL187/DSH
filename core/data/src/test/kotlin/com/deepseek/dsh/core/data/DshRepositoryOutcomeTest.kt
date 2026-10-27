package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отказ Host — это отказ: репозиторий не делает вид, что действие удалось. */
class DshRepositoryOutcomeTest {
    /** G-02: сбой `session/prompt` возвращается вызывающему, чтобы сообщение пользователя не пропало. */
    @Test
    fun sendFailureIsReturnedToTheCaller() = runBlocking {
        val fakes = RepositoryFakes { method, _ ->
            if (method == "sendMessage") RemoteResult.Failure("dsh/net/timeout", "connection reset") else null
        }
        val result = fakes.repository().sendMessage("local", "s1", "hello")
        assertTrue(result.isFailure)
        assertEquals(true, result.exceptionOrNull()?.message?.contains("connection reset"))
        // Вместо сообщения пользователя в историю не пишется «Не удалось отправить».
        assertFalse("insertMessages" in fakes.daoCalls)
    }

    /** G-03: сессия остаётся «идущей», пока Host не подтвердил остановку. */
    @Test
    fun failedCancelKeepsTheSessionRunning() = runBlocking {
        var hostStops = false
        val fakes = RepositoryFakes { method, _ -> if (method == "cancelSession") hostStops else null }
        val repo = fakes.repository()
        repo.runningSessionIdsFlow.value = setOf("s1")

        assertFalse(repo.cancelSession("local", "s1"))
        assertEquals(setOf("s1"), repo.runningSessionIdsFlow.value)

        hostStops = true
        assertTrue(repo.cancelSession("local", "s1"))
        assertEquals(emptySet<String>(), repo.runningSessionIdsFlow.value)
    }

    /** G-11: HTTP 200 с `ok:false` — отказ Host, локальная база не меняется. */
    @Test
    fun hostRejectionIsNotSuccess() = runBlocking {
        var reply = """{"result":{"ok":false,"error":{"code":"workspace/not-found"}}}"""
        val fakes = RepositoryFakes { method, _ -> if (method == "rpcRaw") RemoteResult.Success(reply) else null }
        val repo = fakes.repository()
        assertFalse(repo.setSessionArchived("local", "s1", archived = true))
        assertFalse("updateSessionArchived" in fakes.daoCalls)
        assertFalse(repo.setSessionPinned("local", "s1", pinned = true))
        assertFalse(repo.renameSession("local", "s1", "Новое имя"))
        assertFalse(repo.createWorkspace("local", "/workspace/Proj"))
        assertFalse(repo.deleteWorkspace("local", "w1"))
        assertFalse(repo.moveWorkspace("local", "w1", null))
        assertTrue(repo.workspaceRename("local", "w1", "Proj").isFailure)

        reply = """{"result":{"ok":true,"value":null}}"""
        assertTrue(repo.setSessionArchived("local", "s1", archived = true))
        assertTrue("updateSessionArchived" in fakes.daoCalls)
        assertTrue(repo.workspaceRename("local", "w1", "Proj").isSuccess)
    }
}
