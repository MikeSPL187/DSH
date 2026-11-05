package com.dsh.core.model

@kotlinx.serialization.Serializable
enum class RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, CANCELLED }

/** Durable sequence orders lifecycle events even when a follow stream replays old history. */
data class RunState(
    val status: RunStatus,
    val seq: Long,
    val at: Long,
    val errorCode: String? = null,
    val message: String? = null,
) {
    val terminal: Boolean get() = status !in setOf(RunStatus.QUEUED, RunStatus.RUNNING)
}
