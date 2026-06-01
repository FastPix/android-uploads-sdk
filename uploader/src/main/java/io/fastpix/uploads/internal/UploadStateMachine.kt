package io.fastpix.uploads.internal

import io.fastpix.uploads.UploadState

/**
 * Explicit state machine for the upload lifecycle. Replaces the five booleans
 * (isPause, isOffline, isAborted, isOnlyChunk, isFirstTime) of the original SDK
 * with a single enum and validated transitions.
 *
 * Not thread-safe by itself — the engine drives it from a single serial executor
 * thread, so the only callers are confined to that thread.
 */
internal class UploadStateMachine(
    private val onStateChange: (UploadState) -> Unit,
) {
    var state: UploadState = UploadState.IDLE
        private set

    private val allowedTransitions = mapOf(
        UploadState.IDLE to setOf(
            UploadState.PREPARING
        ),

        UploadState.PREPARING to setOf(
            UploadState.UPLOADING,
            UploadState.QUERYING_STATUS,
            UploadState.FAILED,
            UploadState.CANCELLED
        ),

        UploadState.UPLOADING to setOf(
            UploadState.UPLOADING,
            UploadState.QUERYING_STATUS,
            UploadState.PAUSED,
            UploadState.RETRYING,
            UploadState.NETWORK_LOST,
            UploadState.COMPLETED,
            UploadState.FAILED,
            UploadState.CANCELLED
        ),

        // RETRYING / PAUSED / NETWORK_LOST must traverse QUERYING_STATUS
        // before resuming uploads to remain resumable-upload compliant.
        UploadState.RETRYING to setOf(
            UploadState.QUERYING_STATUS,
            UploadState.PAUSED,
            UploadState.NETWORK_LOST,
            UploadState.FAILED,
            UploadState.CANCELLED
        ),

        UploadState.PAUSED to setOf(
            UploadState.QUERYING_STATUS,
            UploadState.NETWORK_LOST,
            UploadState.CANCELLED
        ),

        UploadState.NETWORK_LOST to setOf(
            UploadState.QUERYING_STATUS,
            UploadState.PAUSED,
            UploadState.CANCELLED
        ),

        UploadState.QUERYING_STATUS to setOf(
            UploadState.UPLOADING,
            UploadState.RETRYING,
            UploadState.PAUSED,
            UploadState.NETWORK_LOST,
            UploadState.COMPLETED,
            UploadState.FAILED,
            UploadState.CANCELLED
        )
    )

    /**
     * Attempt the transition. Returns true if accepted, false if disallowed
     * from the current state. Disallowed transitions are silently dropped so
     * late callbacks (e.g. an OkHttp response that lands after cancel) cannot
     * resurrect a terminal session.
     */
    fun transition(next: UploadState): Boolean {
        if (state == next) return false
        if (!isAllowed(state, next)) return false
        state = next
        onStateChange(next)
        return true
    }

    fun isTerminal(): Boolean = state.isTerminal

    private fun isAllowed(from: UploadState, to: UploadState): Boolean {
        if (from.isTerminal) return false
        return allowedTransitions[from]?.contains(to) == true
    }
}
