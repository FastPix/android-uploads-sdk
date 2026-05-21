package io.fastpix.uploads.internal

import io.fastpix.uploads.UploadError
import io.fastpix.uploads.UploadListener
import io.fastpix.uploads.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Locks down the contract that terminal lifecycle callbacks
 * ([UploadListener.onSuccess], [UploadListener.onFailure], [UploadListener.onCancelled])
 * are always delivered, in order, after the state-change callback that announces
 * the terminal state. Previously a "defense in depth" terminated-flag in the
 * dispatcher silently dropped the terminal callback itself.
 */
class CallbackDispatcherTest {

    private class Recording : UploadListener {
        val events = mutableListOf<String>()
        override fun onStateChange(state: UploadState) { events.add("state:$state") }
        override fun onProgress(bytesUploaded: Long, totalBytes: Long, percentage: Double) {
            events.add("progress:$bytesUploaded/$totalBytes")
        }
        override fun onPrepared(totalChunks: Int, totalBytes: Long, chunkSize: Long) {
            events.add("prepared:$totalChunks,$totalBytes")
        }
        override fun onChunkUploaded(chunkIndex: Int, totalChunks: Int, bytesAcked: Long) {
            events.add("chunk:$chunkIndex/$totalChunks")
        }
        override fun onRetryScheduled(attempt: Int, delayMillis: Long, cause: UploadError) {
            events.add("retry:$attempt")
        }
        override fun onNetworkStateChange(online: Boolean) { events.add("net:$online") }
        override fun onSuccess(elapsedMillis: Long) { events.add("success:$elapsedMillis") }
        override fun onFailure(error: UploadError, elapsedMillis: Long) {
            events.add("failure:${error::class.simpleName}")
        }
        override fun onCancelled(elapsedMillis: Long) { events.add("cancelled:$elapsedMillis") }
    }

    private val direct = Executor { it.run() }

    @Test fun `cancel delivers state-CANCELLED then onCancelled in order`() {
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, direct)
        dispatcher.stateChanged(UploadState.CANCELLED)
        dispatcher.cancelled(elapsedMillis = 1234L)
        assertEquals(
            listOf("state:CANCELLED", "cancelled:1234"),
            listener.events,
        )
    }

    @Test fun `success delivers state-COMPLETED then onSuccess in order`() {
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, direct)
        dispatcher.stateChanged(UploadState.COMPLETED)
        dispatcher.success(elapsedMillis = 42L)
        assertEquals(
            listOf("state:COMPLETED", "success:42"),
            listener.events,
        )
    }

    @Test fun `failure delivers state-FAILED then onFailure in order`() {
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, direct)
        dispatcher.stateChanged(UploadState.FAILED)
        dispatcher.failure(UploadError.SessionExpired(), elapsedMillis = 7L)
        assertEquals(
            listOf("state:FAILED", "failure:SessionExpired"),
            listener.events,
        )
    }

    @Test fun `dispatch order preserved through a realistic upload sequence`() {
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, direct)

        dispatcher.stateChanged(UploadState.PREPARING)
        dispatcher.prepared(totalChunks = 2, totalBytes = 100, chunkSize = 50)
        dispatcher.stateChanged(UploadState.UPLOADING)
        dispatcher.progress(50, 100)
        dispatcher.chunkUploaded(1, 2, bytesAcked = 50)
        dispatcher.progress(100, 100)
        dispatcher.chunkUploaded(2, 2, bytesAcked = 100)
        dispatcher.stateChanged(UploadState.COMPLETED)
        dispatcher.success(elapsedMillis = 500)

        assertEquals(
            listOf(
                "state:PREPARING",
                "prepared:2,100",
                "state:UPLOADING",
                "progress:50/100",
                "chunk:1/2",
                "progress:100/100",
                "chunk:2/2",
                "state:COMPLETED",
                "success:500",
            ),
            listener.events,
        )
    }

    @Test fun `progress skipped when totalBytes is zero`() {
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, direct)
        dispatcher.progress(0, 0)
        assertTrue(listener.events.isEmpty())
    }

    @Test fun `null listener swallows all calls without crashing`() {
        val dispatcher = CallbackDispatcher(listener = null, executor = direct)
        // The fact that this doesn't throw is the assertion.
        dispatcher.stateChanged(UploadState.UPLOADING)
        dispatcher.success(42)
        dispatcher.failure(UploadError.NetworkFailure(RuntimeException("x")), 42)
        dispatcher.cancelled(42)
        dispatcher.progress(1, 2)
    }

    @Test fun `runs on the supplied executor`() {
        val executed = mutableListOf<Runnable>()
        val capturing = Executor { executed.add(it) }
        val listener = Recording()
        val dispatcher = CallbackDispatcher(listener, capturing)
        dispatcher.stateChanged(UploadState.UPLOADING)
        // Submitted but not yet run (executor stored, didn't invoke).
        assertTrue(listener.events.isEmpty())
        assertEquals(1, executed.size)
        executed.single().run()
        assertEquals(listOf("state:UPLOADING"), listener.events)
    }
}
