package com.dsh.core.data

import com.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E-21 там, где его не закрыли (G-51): `core:data` разбирал ответы Host без предела глубины. `[[[[…` в несколько тысяч
 * уровней давал StackOverflowError, который `runCatching` не ловит, — падение процесса.
 */
class DeepJsonTest {
    private val deep = "{\"result\":{\"ok\":true,\"value\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}}"

    @Test
    fun deepHostResponseIsAFailureNotACrash() = runBlocking {
        val fakes = RepositoryFakes { method, _ -> if (method == "rpcRaw") RemoteResult.Success(deep) else null }
        val calls = HostCalls(fakes.client)
        assertEquals(null, calls.value("local", "anything"))
        assertEquals(true, calls.valueOrThrow("local", "anything").isFailure)
    }
}
