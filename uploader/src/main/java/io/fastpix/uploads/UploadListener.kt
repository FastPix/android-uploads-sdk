package io.fastpix.uploads

/**
 * Lifecycle callbacks for a single resumable upload. All methods are dispatched
 * on the executor supplied via the Builder (defaults to the Android main looper),
 * so implementations may safely touch UI state without trampoline wrappers.
 *
 * Every callback has a no-op default so consumers only override what they need.
 */
interface UploadListener {

    /** Fired on every state transition. Useful for driving UI state machines. */
    fun onStateChange(state: UploadState) {}

    /**
     * Confirmed-byte progress. Reflects bytes the server has acknowledged
     * (via a 200/201 or 308 with Range), never client-side buffered bytes,
     * so it never regresses on retry.
     */
    fun onProgress(bytesUploaded: Long, totalBytes: Long, percentage: Double) {}

    /** Fired once when the upload session is initialised and chunk count is known. */
    fun onPrepared(totalChunks: Int, totalBytes: Long, chunkSize: Long) {}

    /** Fired after each chunk PUT is acknowledged by the server. */
    fun onChunkUploaded(chunkIndex: Int, totalChunks: Int, bytesAcked: Long) {}

    /** Fired before sleeping for backoff. `delayMillis` is the wait before retry attempt `attempt`. */
    fun onRetryScheduled(attempt: Int, delayMillis: Long, cause: UploadError) {}

    /** Network connectivity flipped. The engine pauses on loss and queries status on recovery. */
    fun onNetworkStateChange(online: Boolean) {}

    /** Terminal: all bytes uploaded and acknowledged. */
    fun onSuccess(elapsedMillis: Long) {}

    /** Terminal: unrecoverable failure (max retries, fatal status code, fatal local error). */
    fun onFailure(error: UploadError, elapsedMillis: Long) {}

    /** Terminal: consumer-initiated cancellation. */
    fun onCancelled(elapsedMillis: Long) {}
}
