package com.dsh.core.data

import com.dsh.core.protocol.RemoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryFeedbackIsolationTest {
    private fun reply(message: String) = RemoteResult.Success(
        """{"result":{"value":{"items":[{"messageId":"$message","rating":"up","version":"v1"}]}}}""",
    )

    @Test fun lateFeedbackCannotEnterANewlySelectedChat() = runBlocking<Unit> {
        lateinit var repository: DshRepository
        val fakes = RepositoryFakes { method, args ->
            check(method == "rpcRaw" && args[1] == "messageFeedback/list")
            repository.markSessionViewed("new")
            reply("old-message")
        }
        repository = fakes.repository()
        repository.markSessionViewed("old")
        repository.refreshMessageFeedback("host", "old")
        assertTrue(repository.messageFeedbackFlow.value.isEmpty())
    }

    @Test fun backgroundFeedbackCannotReplaceRatingsOfTheViewedChat() = runBlocking<Unit> {
        var message = "current-message"
        val fakes = RepositoryFakes { method, args ->
            check(method == "rpcRaw" && args[1] == "messageFeedback/list")
            reply(message)
        }
        val repository = fakes.repository()
        repository.markSessionViewed("current")
        repository.refreshMessageFeedback("host", "current")
        assertEquals(setOf("current-message"), repository.messageFeedbackFlow.value.keys)
        message = "background-message"
        repository.refreshMessageFeedback("host", "background")
        assertEquals(setOf("current-message"), repository.messageFeedbackFlow.value.keys)
    }
}
