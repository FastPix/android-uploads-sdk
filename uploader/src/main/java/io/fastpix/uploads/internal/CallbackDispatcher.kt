package io.fastpix.uploads.internal

import io.fastpix.uploads.UploadError
import io.fastpix.uploads.UploadListener
import io.fastpix.uploads.UploadState
import java.util.concurrent.Executor

/**
 * Posts every UploadListener invocation onto a consumer-supplied Executor (typically
 * the main looper), so listener implementations never have to wrap in runOnUiThread.
 *
 * No state, no gating: ordering and terminal-once guarantees are enforced upstream by
 * [UploadStateMachine] (rejects transitions out of terminal states), the generation
 * counter in [UploadEngine.cancelInFlightCall] (drops late OkHttp callbacks), and
 * [UploadEngine.submitToEngine] (drops tasks submitted after teardown). The dispatcher's
 * sole job is to marshal calls onto the consumer executor in submission order.
 */
internal class CallbackDispatcher(
    private val listener: UploadListener?,
    private val executor: Executor,
) {
    fun stateChanged(state: UploadState) =
        post { listener?.onStateChange(state) }

    fun progress(bytesUploaded: Long, totalBytes: Long) {
        if (totalBytes <= 0) return
        val pct = (bytesUploaded.toDouble() / totalBytes.toDouble()) * 100.0
        post { listener?.onProgress(bytesUploaded, totalBytes, pct.coerceIn(0.0, 100.0)) }
    }

    fun prepared(totalChunks: Int, totalBytes: Long, chunkSize: Long) =
        post { listener?.onPrepared(totalChunks, totalBytes, chunkSize) }

    fun chunkUploaded(chunkIndex: Int, totalChunks: Int, bytesAcked: Long) =
        post { listener?.onChunkUploaded(chunkIndex, totalChunks, bytesAcked) }

    fun retryScheduled(attempt: Int, delayMillis: Long, cause: UploadError) =
        post { listener?.onRetryScheduled(attempt, delayMillis, cause) }

    fun networkStateChanged(online: Boolean) =
        post { listener?.onNetworkStateChange(online) }

    fun success(elapsedMillis: Long) = post { listener?.onSuccess(elapsedMillis) }

    fun failure(error: UploadError, elapsedMillis: Long) =
        post { listener?.onFailure(error, elapsedMillis) }

    fun cancelled(elapsedMillis: Long) = post { listener?.onCancelled(elapsedMillis) }

    private inline fun post(crossinline block: () -> Unit) {
        if (listener == null) return
        executor.execute { block() }
    }
}
