package com.dsh

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Application-owned preparation survives Activity recreation. Only a resumed screen consumes it. */
class BackupExportController(private val scope: CoroutineScope) {
    data class State(val busy: Boolean = false, val result: Result<File>? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    @Synchronized fun start(create: suspend () -> Result<File>) {
        if (mutable.value.busy || mutable.value.result != null) return
        mutable.value = State(busy = true)
        scope.launch {
            val result = try { create() } catch (cancelled: kotlinx.coroutines.CancellationException) { mutable.value = State(); throw cancelled } catch (error: Exception) { Result.failure(error) }
            mutable.value = State(result = result)
        }
    }
    @Synchronized fun consume(): Result<File>? {
        val result = mutable.value.result ?: return null
        mutable.value = State()
        return result
    }
}
