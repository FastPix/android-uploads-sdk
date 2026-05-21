package io.fastpix.uploads

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.fastpix.uploads.internal.CallbackDispatcher
import io.fastpix.uploads.internal.ChunkReader
import io.fastpix.uploads.internal.GcsResumableProtocol
import io.fastpix.uploads.internal.HttpClientFactory
import io.fastpix.uploads.internal.NetworkMonitor
import io.fastpix.uploads.internal.RetryPolicy
import io.fastpix.uploads.internal.UploadEngine
import java.io.File
import java.util.concurrent.Executor

/**
 * Resumable upload client for Google Cloud Storage signed session URIs.
 *
 * Implementation follows https://cloud.google.com/storage/docs/performing-resumable-uploads:
 *  - Sends each chunk via PUT with `Content-Range: bytes start-end/total`.
 *  - Treats HTTP 308 as "resume incomplete" and uses the response `Range` header to
 *    advance the upload cursor, so the client and server never disagree about progress.
 *  - On every resume (after pause, network restore, transient error) the engine issues
 *    a status-query PUT (`Content-Range: bytes * /TOTAL`) before sending more data.
 *  - Distinguishes retryable status codes (408, 429, 5xx) from fatal ones (other 4xx,
 *    410 Gone -> session expired).
 *
 * Instances are single-use: call [start] once, then optionally [pause]/[resume], and
 * exactly one of [UploadListener.onSuccess], [UploadListener.onFailure], or
 * [UploadListener.onCancelled] will fire.
 */
class FastPixUploader private constructor(
    private val engine: UploadEngine,
) {
    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun cancel() = engine.cancel()

    class Builder(private val context: Context) {
        private var file: File? = null
        private var sessionUri: String? = null
        private var chunkSize: Long = DEFAULT_CHUNK_SIZE
        private var maxRetries: Int = DEFAULT_MAX_RETRIES
        private var retryBaseDelayMillis: Long = DEFAULT_RETRY_BASE_DELAY_MS
        private var retryMaxDelayMillis: Long = DEFAULT_RETRY_MAX_DELAY_MS
        private var listener: UploadListener? = null
        private var callbackExecutor: Executor? = null
        private var debugLogging: Boolean = false

        /** The local file to upload. Must exist, be readable, and non-empty. */
        fun file(file: File) = apply { this.file = file }

        /**
         * The resumable session URI returned by the server-side `POST .../o?uploadType=resumable`
         * (or its signed-URL equivalent). This is *not* a one-shot signed URL — it is the
         * session URI you receive in the Location header when you initiate the session.
         */
        fun sessionUri(uri: String) = apply { this.sessionUri = uri }

        /**
         * Chunk size in bytes. GCS requires this to be a multiple of 256 KiB
         * (262144 bytes); the Builder enforces it. Range: 5 MiB to 500 MiB.
         */
        fun chunkSize(bytes: Long) = apply { this.chunkSize = bytes }

        fun maxRetries(attempts: Int) = apply { this.maxRetries = attempts }

        /** Base delay before retry attempt 1; subsequent attempts use exponential backoff with jitter. */
        fun retryBaseDelay(millis: Long) = apply { this.retryBaseDelayMillis = millis }

        /** Upper bound on a single retry delay regardless of attempt number. */
        fun retryMaxDelay(millis: Long) = apply { this.retryMaxDelayMillis = millis }

        fun listener(listener: UploadListener) = apply { this.listener = listener }

        /**
         * Executor to deliver every [UploadListener] callback on. Defaults to the main
         * looper, so listener implementations can touch UI without trampoline wrappers.
         */
        fun callbackExecutor(executor: Executor) = apply { this.callbackExecutor = executor }

        /** When true, install HttpLoggingInterceptor at BASIC level. Off by default. */
        fun debugLogging(enabled: Boolean) = apply { this.debugLogging = enabled }

        @Throws(UploadError::class)
        fun build(): FastPixUploader {
            val file = file ?: throw UploadError.InvalidConfiguration("file is required")
            val uri = sessionUri ?: throw UploadError.InvalidConfiguration("sessionUri is required")
            if (!file.exists()) throw UploadError.FileNotFound(file.absolutePath)
            if (!file.canRead()) throw UploadError.FileNotReadable(file.absolutePath)
            if (file.length() == 0L) throw UploadError.FileEmpty(file.absolutePath)
            try {
                GcsResumableProtocol.validateChunkSize(chunkSize, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
            } catch (e: IllegalArgumentException) {
                throw UploadError.InvalidConfiguration(e.message ?: "invalid chunk size")
            }
            require(maxRetries >= 0) { "maxRetries must be >= 0" }
            require(retryBaseDelayMillis >= 0) { "retryBaseDelay must be >= 0" }
            require(retryMaxDelayMillis >= retryBaseDelayMillis) {
                "retryMaxDelay must be >= retryBaseDelay"
            }

            val reader = ChunkReader(file)
            val dispatcher = CallbackDispatcher(
                listener = listener,
                executor = callbackExecutor ?: MainThreadExecutor,
            )
            val engine = UploadEngine(
                sessionUri = uri,
                chunkReader = reader,
                chunkSize = chunkSize,
                httpClient = HttpClientFactory.get(debugLogging),
                retryPolicy = RetryPolicy(
                    maxAttempts = maxRetries,
                    baseDelayMillis = retryBaseDelayMillis,
                    maxDelayMillis = retryMaxDelayMillis,
                ),
                networkMonitor = NetworkMonitor(context),
                dispatcher = dispatcher,
            )
            return FastPixUploader(engine)
        }
    }

    companion object {
        /** GCS minimum recommended chunk size (must also be a multiple of 256 KiB). */
        const val MIN_CHUNK_SIZE: Long = 5L * 1024L * 1024L

        /** Hard upper bound to keep memory & retry blast-radius reasonable. */
        const val MAX_CHUNK_SIZE: Long = 500L * 1024L * 1024L

        const val DEFAULT_CHUNK_SIZE: Long = 8L * 1024L * 1024L
        const val DEFAULT_MAX_RETRIES: Int = 5
        const val DEFAULT_RETRY_BASE_DELAY_MS: Long = 2_000L
        const val DEFAULT_RETRY_MAX_DELAY_MS: Long = 30_000L
    }
}

private object MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())
    override fun execute(command: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) command.run()
        else handler.post(command)
    }
}
