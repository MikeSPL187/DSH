package com.dsh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/** Each opened journal models one process; a kill cancels timers without a graceful flush. */
internal class JournalTestFixture : Closeable {
    val file = kotlin.io.path.createTempDirectory("dsh-journal-").resolve("outbox.json").toFile()
    private val scopes = mutableListOf<CoroutineScope>()

    fun open(): ChatRunJournal {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return ChatRunJournal(file, scope)
    }

    fun killProcess() {
        scopes.forEach { it.cancel() }
        // Wait for a write already inside fsync/rename before opening the simulated next process.
        runBlocking { scopes.forEach { it.coroutineContext[Job]?.join() } }
        scopes.clear()
    }

    override fun close() {
        killProcess()
        file.parentFile.deleteRecursively()
    }
}
