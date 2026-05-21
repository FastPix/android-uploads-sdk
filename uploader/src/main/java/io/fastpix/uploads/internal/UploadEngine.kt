package io.fastpix.uploads.internal

import io.fastpix.uploads.UploadError
import io.fastpix.uploads.UploadState
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Orchestrates the full GCS resumable upload lifecycle:
 *   - Owns a single-thread executor that serializes every state mutation,
 *     eliminating races between OkHttp callbacks, network-monitor callbacks,
 *     consumer commands (start/pause/resume/cancel), and retry timers.
 *   - Drives an explicit [UploadStateMachine].
 *   - Implements both branches of the GCS protocol: chunk PUT and status-query PUT.
 *   - Recovers from every "ambiguous" state (resume after pause, network restore,
 *     transient failure) with a status query before resending data, so the client's
 *     view of "uploaded so far" never diverges from the server's.
 */
internal class UploadEngine(
    private val sessionUri: String,
    private val chunkReader: ChunkReader,
    private val chunkSize: Long,
    private val httpClient: OkHttpClient,
    private val retryPolicy: RetryPolicy,
    private val networkMonitor: NetworkMonitor,
    private val dispatcher: CallbackDispatcher,
) {
    private val totalBytes: Long = chunkReader.totalBytes
    private val totalChunks: Int = if (totalBytes == 0L) {
        0
    } else {
        ((totalBytes + chunkSize - 1) / chunkSize).toInt()
    }

    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fastpix-upload-engine").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "fastpix-upload-scheduler").apply { isDaemon = true }
        }

    private val stateMachine = UploadStateMachine { state -> dispatcher.stateChanged(state) }

    /** Cumulative bytes acknowledged by the server. Single source of truth for chunk accounting. */
    private var confirmedBytes: Long = 0L
    private var chunksAcked: Int = 0
    private var retryAttempt: Int = 0
    private var startedAtMillis: Long = 0L
    private var currentCall: Call? = null

    /**
     * Monotonic high-water mark of bytes we've reported to the consumer. Updated from
     * both the engine thread (when a 308/200 confirms bytes) and the OkHttp call thread
     * (as the request body streams out). [reportProgress] uses a CAS loop so concurrent
     * updates from these two threads never lose progress and never regress on retry.
     */
    private val reportedBytes = AtomicLong(0L)

    /**
     * The integer percent we most recently emitted. Used to throttle [dispatcher.progress]
     * to once per whole-percent boundary — enough granularity for smooth UI, no spam.
     */
    private val lastReportedPercent = AtomicLong(-1L)

    /**
     * Monotonic generation counter incremented on every cancel/replace. Each enqueued
     * OkHttp Call captures the generation; on completion the callback checks whether
     * the generation still matches before mutating engine state. This is how we ignore
     * the late-arriving "Canceled" onFailure that OkHttp fires synchronously when we
     * call Call.cancel() — without this, a pause() would still poison engine state.
     */
    private val callGeneration = AtomicLong(0)

    // --- consumer-facing commands; all posted to the engine thread -----------

    fun start() = submitToEngine {
        if (stateMachine.state != UploadState.IDLE) return@submitToEngine
        startedAtMillis = System.currentTimeMillis()
        stateMachine.transition(UploadState.PREPARING)
        dispatcher.prepared(totalChunks, totalBytes, chunkSize)

        networkMonitor.start(object : NetworkMonitor.Listener {
            override fun onAvailable() = onNetworkAvailable()
            override fun onLost() = onNetworkLost()
        })

        if (!networkMonitor.isOnline()) {
            stateMachine.transition(UploadState.NETWORK_LOST)
            dispatcher.networkStateChanged(false)
            return@submitToEngine
        }
        stateMachine.transition(UploadState.UPLOADING)
        sendNextChunk()
    }

    fun pause() = submitToEngine {
        when (stateMachine.state) {
            UploadState.UPLOADING, UploadState.RETRYING, UploadState.QUERYING_STATUS -> {
                cancelInFlightCall()
                stateMachine.transition(UploadState.PAUSED)
            }
            else -> Unit
        }
    }

    fun resume() = submitToEngine {
        when (stateMachine.state) {
            UploadState.PAUSED -> {
                if (!networkMonitor.isOnline()) {
                    stateMachine.transition(UploadState.NETWORK_LOST)
                } else {
                    issueStatusQuery()
                }
            }
            else -> Unit
        }
    }

    fun cancel() = submitToEngine {
        if (stateMachine.isTerminal()) return@submitToEngine
        cancelInFlightCall()
        stateMachine.transition(UploadState.CANCELLED)
        dispatcher.cancelled(elapsed())
        teardown()
    }

    /** Release executors/monitor without firing further callbacks; used after natural terminal state. */
    fun shutdown() {
        teardown()
    }

    // --- network-monitor signals; bridged onto engine thread ------------------

    private fun onNetworkAvailable() = submitToEngine {
        dispatcher.networkStateChanged(true)
        if (stateMachine.state == UploadState.NETWORK_LOST) {
            issueStatusQuery()
        }
    }

    private fun onNetworkLost() = submitToEngine {
        dispatcher.networkStateChanged(false)
        when (stateMachine.state) {
            UploadState.UPLOADING, UploadState.RETRYING, UploadState.QUERYING_STATUS -> {
                cancelInFlightCall()
                stateMachine.transition(UploadState.NETWORK_LOST)
            }
            else -> Unit
        }
    }

    // --- protocol: chunk PUT --------------------------------------------------

    private fun sendNextChunk() {
        // Defensive: empty file → nothing to send.
        if (totalBytes == 0L) {
            completeSuccessfully()
            return
        }
        if (confirmedBytes >= totalBytes) {
            completeSuccessfully()
            return
        }
        val start = confirmedBytes
        val end = min(start + chunkSize, totalBytes)
        val contentRange = GcsResumableProtocol.chunkContentRange(start, end, totalBytes)

        // In-flight progress: fires on the OkHttp call thread as bytes stream out.
        // We translate chunk-relative bytesWritten into absolute file position and feed
        // it through reportProgress, which is monotonic + percent-throttled — so a
        // mid-chunk failure followed by a retry cannot make the UI go backwards.
        val body = ChunkRequestBody(chunkReader, start, end) { bytesWritten ->
            reportProgress(start + bytesWritten)
        }
        val request = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", contentRange)
            .put(body)
            .build()
        enqueue(request, isStatusQuery = false, expectedEnd = end)
    }

    // --- protocol: status query (Content-Range: bytes */TOTAL) ---------------

    private fun issueStatusQuery() {
        stateMachine.transition(UploadState.QUERYING_STATUS)
        val contentRange = GcsResumableProtocol.statusQueryContentRange(totalBytes)
        val emptyBody = ByteArray(0).toRequestBody()
        val request = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", contentRange)
            .header("Content-Length", "0")
            .put(emptyBody)
            .build()
        enqueue(request, isStatusQuery = true, expectedEnd = totalBytes)
    }

    // --- shared enqueue + dispatch -------------------------------------------

    private fun enqueue(request: Request, isStatusQuery: Boolean, expectedEnd: Long) {
        val generation = callGeneration.incrementAndGet()
        val call = httpClient.newCall(request)
        currentCall = call
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val rangeHeader = response.header("Range")
                val bodyPreview = response.use {
                    runCatching { it.body?.string()?.take(512) }.getOrNull()
                }
                submitToEngine {
                    if (generation != callGeneration.get()) return@submitToEngine
                    handleResponse(code, rangeHeader, bodyPreview, isStatusQuery, expectedEnd)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                submitToEngine {
                    if (generation != callGeneration.get()) return@submitToEngine
                    handleTransportFailure(e)
                }
            }
        })
    }

    private fun handleResponse(
        code: Int,
        rangeHeader: String?,
        bodyPreview: String?,
        wasStatusQuery: Boolean,
        expectedEnd: Long,
    ) {
        when (GcsResumableProtocol.classify(code)) {
            GcsResumableProtocol.ResponseClass.Complete -> {
                confirmedBytes = totalBytes
                if (!wasStatusQuery) chunksAcked = totalChunks
                reportProgress(confirmedBytes, force = true)
                completeSuccessfully()
            }

            GcsResumableProtocol.ResponseClass.Incomplete -> {
                val persistedEnd = GcsResumableProtocol.parsePersistedEndByte(rangeHeader)
                val newConfirmed = persistedEnd?.let { it + 1L } ?: 0L
                // Server can't have less than it had before; defensive clamp.
                if (newConfirmed > confirmedBytes) confirmedBytes = newConfirmed
                if (!wasStatusQuery) {
                    // Update chunksAcked based on how many full chunks the server has now.
                    chunksAcked = (confirmedBytes / chunkSize).toInt()
                        .coerceAtMost(totalChunks)
                    dispatcher.chunkUploaded(chunksAcked, totalChunks, confirmedBytes)
                }
                reportProgress(confirmedBytes)

                retryAttempt = 0
                if (confirmedBytes >= totalBytes) {
                    completeSuccessfully()
                } else {
                    stateMachine.transition(UploadState.UPLOADING)
                    sendNextChunk()
                }
            }

            GcsResumableProtocol.ResponseClass.SessionExpired -> {
                failTerminally(UploadError.SessionExpired())
            }

            GcsResumableProtocol.ResponseClass.Retryable -> {
                scheduleRetry(
                    UploadError.ServerError(code, bodyPreview),
                    transientServer = true,
                )
            }

            GcsResumableProtocol.ResponseClass.FatalClient -> {
                failTerminally(UploadError.ClientError(code, bodyPreview))
            }

            GcsResumableProtocol.ResponseClass.Unexpected -> {
                failTerminally(
                    UploadError.UnexpectedResponse(
                        "Unexpected HTTP $code from resumable session"
                    )
                )
            }
        }
    }

    private fun handleTransportFailure(e: IOException) {
        // Intentional cancellation lands here too (OkHttp wraps it as IOException("Canceled")).
        // If state already reflects an intentional stop, do nothing — the trigger has already
        // emitted the right callback.
        when (stateMachine.state) {
            UploadState.PAUSED, UploadState.CANCELLED,
            UploadState.NETWORK_LOST, UploadState.COMPLETED, UploadState.FAILED -> return
            else -> scheduleRetry(UploadError.NetworkFailure(e), transientServer = false)
        }
    }

    // --- retry ---------------------------------------------------------------

    private fun scheduleRetry(cause: UploadError, transientServer: Boolean) {
        if (!retryPolicy.shouldRetry(retryAttempt)) {
            failTerminally(UploadError.RetryLimitExceeded(retryPolicy.maxAttempts, cause))
            return
        }
        retryAttempt++
        val delayMs = retryPolicy.delayFor(retryAttempt)
        if (!stateMachine.transition(UploadState.RETRYING)) {
            // We can't enter RETRYING from current state — likely terminal/paused already.
            return
        }
        dispatcher.retryScheduled(retryAttempt, delayMs, cause)
        scheduler.schedule({
            submitToEngine {
                if (stateMachine.state == UploadState.RETRYING) {
                    issueStatusQuery()
                }
                // Any other state (paused, cancelled, network-lost) absorbs the wake silently.
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        @Suppress("UNUSED_PARAMETER") val ignored = transientServer
    }

    // --- terminal transitions ------------------------------------------------

    private fun completeSuccessfully() {
        if (stateMachine.transition(UploadState.COMPLETED)) {
            dispatcher.success(elapsed())
        }
        teardown()
    }

    private fun failTerminally(error: UploadError) {
        if (stateMachine.transition(UploadState.FAILED)) {
            dispatcher.failure(error, elapsed())
        }
        teardown()
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Thread-safe, monotonic, percent-throttled progress emitter. Callable from any
     * thread (engine thread on confirmation, OkHttp call thread during streaming).
     *
     *  - Monotonic: candidates ≤ the current high-water mark are dropped, so a retry
     *    restarting in-flight bytes from the chunk start cannot make the UI regress.
     *  - Throttled: fires [dispatcher.progress] only when the *integer* percent
     *    changes, so a typical upload emits ~100 events total regardless of file size.
     *  - [force] bypasses the throttle so the terminal 100% always lands even if the
     *    last percent boundary was already emitted.
     */
    private fun reportProgress(candidateBytes: Long, force: Boolean = false) {
        if (totalBytes <= 0) return
        // CAS the high-water mark upward. Drop if not larger.
        var current: Long
        do {
            current = reportedBytes.get()
            if (candidateBytes <= current && !force) return
        } while (!reportedBytes.compareAndSet(current, maxOf(current, candidateBytes)))

        val effective = if (force) candidateBytes else maxOf(current, candidateBytes)
        val newPercent = (effective * 100L / totalBytes).coerceIn(0L, 100L)
        if (force || lastReportedPercent.getAndSet(newPercent) != newPercent) {
            dispatcher.progress(effective, totalBytes)
        }
    }

    private fun cancelInFlightCall() {
        callGeneration.incrementAndGet() // Invalidate any in-flight callback.
        currentCall?.cancel()
        currentCall = null
    }

    private fun elapsed(): Long =
        if (startedAtMillis == 0L) 0L else System.currentTimeMillis() - startedAtMillis

    /**
     * Submit a task to the engine, swallowing [RejectedExecutionException] that arises
     * when the executor has already been shut down by [teardown]. Late OkHttp callbacks,
     * late ConnectivityManager callbacks, duplicate consumer commands (e.g. cancel()
     * called twice), and retry-scheduler wake-ups can all land after teardown — none of
     * them should crash the caller's thread.
     */
    private fun submitToEngine(block: () -> Unit) {
        try {
            engineExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Engine is terminated; the late event has nothing useful to do.
        }
    }

    @Volatile private var torndown: Boolean = false

    private fun teardown() {
        if (torndown) return
        torndown = true
        networkMonitor.stop()
        currentCall = null
        scheduler.shutdownNow()
        engineExecutor.shutdown()
    }
}
