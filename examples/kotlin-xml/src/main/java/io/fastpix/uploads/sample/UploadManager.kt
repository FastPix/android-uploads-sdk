package io.fastpix.uploads.sample

import android.content.Context
import io.fastpix.uploads.FastPixUploader
import io.fastpix.uploads.UploadError
import io.fastpix.uploads.UploadListener
import io.fastpix.uploads.UploadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.roundToInt

data class UploadUiState(
    val active: Boolean = false,
    val state: UploadState? = null,
    val percent: Int = 0,
    val fileName: String = "",
    val status: String = "Idle",
)

// Holds the single in-flight upload outside the Activity so it survives rotation and
// backgrounding. Not persisted, so killing the app ends the upload.
object UploadManager {

    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    private var uploader: FastPixUploader? = null

    fun startUpload(context: Context, file: File, sessionUri: String) {
        val app = context.applicationContext
        cancel()
        uploader = runCatching {
            FastPixUploader.Builder(app)
                .file(file)
                .sessionUri(sessionUri)
                .chunkSize(Config.CHUNK_SIZE)
                .listener(listener)
                .build()
        }.getOrElse { e ->
            _state.value = UploadUiState(status = "Error: ${e.message}")
            return
        }
        _state.value = UploadUiState(active = true, fileName = file.name, status = "Starting")
        UploadService.start(app)
        uploader?.start()
    }

    fun pause() { uploader?.pause() }
    fun resume() { uploader?.resume() }

    fun cancel() {
        uploader?.cancel()
        uploader = null
    }

    private inline fun update(block: (UploadUiState) -> UploadUiState) {
        _state.value = block(_state.value)
    }

    private val listener = object : UploadListener {
        override fun onStateChange(state: UploadState) =
            update { it.copy(state = state, active = !state.isTerminal, status = state.name) }

        override fun onProgress(bytesUploaded: Long, totalBytes: Long, percentage: Double) =
            update { it.copy(percent = percentage.roundToInt().coerceIn(0, 100)) }

        override fun onSuccess(elapsedMillis: Long) =
            update { it.copy(percent = 100, active = false, status = "Completed") }

        override fun onFailure(error: UploadError, elapsedMillis: Long) =
            update { it.copy(active = false, status = "Failed: ${error.message}") }

        override fun onCancelled(elapsedMillis: Long) =
            update { it.copy(active = false, status = "Cancelled") }
    }
}
