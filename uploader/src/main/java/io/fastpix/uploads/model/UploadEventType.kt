package io.fastpix.uploads.model


interface FastPixUploadCallbacks {
    fun onProgressUpdate(progress: Double)
    fun onSuccess(timiMillis: Long)
    fun onError(error: String, timiMillis: Long)
    fun onNetworkStateChange(isOnline: Boolean)
    fun onUploadInit()
    fun onAbort()
    fun onChunkHandled(
        totalChunks: Int,
        filSizeInBytes: Long,
        currentChunk: Int,
        currentChunkSizeInBytes: Long
    )
    fun onChunkUploadingFailed(failedChunkRetries: Int, chunkCount: Int, chunkSize: Long)
    fun onPauseUploading()
    fun onResumeUploading()
}
