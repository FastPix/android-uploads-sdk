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
        return when (from) {
            UploadState.IDLE -> to == UploadState.PREPARING || to.isTerminal
            UploadState.PREPARING -> when (to) {
                UploadState.UPLOADING, UploadState.QUERYING_STATUS,
                UploadState.FAILED, UploadState.CANCELLED -> true
                else -> false
            }
            UploadState.UPLOADING -> when (to) {
                UploadState.UPLOADING, UploadState.QUERYING_STATUS,
                UploadState.PAUSED, UploadState.RETRYING, UploadState.NETWORK_LOST,
                UploadState.COMPLETED, UploadState.FAILED, UploadState.CANCELLED -> true
                else -> false
            }
            // RETRYING / PAUSED / NETWORK_LOST must traverse QUERYING_STATUS before resuming
            // data PUTs. That's the architectural rule that keeps us spec-compliant — we
            // never resend bytes without first asking GCS where it actually stopped.
            UploadState.RETRYING -> when (to) {
                UploadState.QUERYING_STATUS,
                UploadState.PAUSED, UploadState.NETWORK_LOST,
                UploadState.FAILED, UploadState.CANCELLED -> true
                else -> false
            }
            UploadState.PAUSED -> when (to) {
                UploadState.QUERYING_STATUS,
                UploadState.NETWORK_LOST, UploadState.CANCELLED -> true
                else -> false
            }
            UploadState.NETWORK_LOST -> when (to) {
                UploadState.QUERYING_STATUS,
                UploadState.PAUSED, UploadState.CANCELLED -> true
                else -> false
            }
            UploadState.QUERYING_STATUS -> when (to) {
                UploadState.UPLOADING, UploadState.RETRYING,
                UploadState.PAUSED, UploadState.NETWORK_LOST,
                UploadState.COMPLETED, UploadState.FAILED, UploadState.CANCELLED -> true
                else -> false
            }
            UploadState.COMPLETED, UploadState.FAILED, UploadState.CANCELLED -> false
        }
    }
}
