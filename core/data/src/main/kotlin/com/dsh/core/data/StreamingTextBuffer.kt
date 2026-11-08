package com.dsh.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lossless stream accumulation; only presentation snapshots are coalesced. */
internal class StreamingTextBuffer(private val scope: CoroutineScope) {
    private val lock = Any()
    private val text = StringBuilder()
    private val mutable = MutableStateFlow("")
    val snapshots = mutable.asStateFlow()
    private var pending: Job? = null
    private var generation = 0L

    fun append(delta: String, baseline: Boolean) = synchronized(lock) {
        if (baseline) text.setLength(0)
        text.append(delta)
        if (pending == null) {
            val current = generation
            pending = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                delay(8)
                synchronized(lock) {
                    if (generation == current) {
                        mutable.value = text.toString()
                        pending = null
                    }
                }
            }
            pending?.start()
        }
    }

    /** A session switch or terminal event must invalidate every pending presentation. */
    fun clear() = synchronized(lock) {
        generation++
        pending?.cancel()
        pending = null
        text.setLength(0)
        mutable.value = ""
    }
}
