package io.fastpix.uploads.model


/**
 * Interface defining callbacks for monitoring the state and progress of an upload
 * operation using the FastPixUpload SDK.
 */
interface FastPixUploadCallbacks {

    /**
     * Called to update the upload progress.
     *
     * @param progress A double between 0.0 and 1.0 representing the completion percentage.
     */
    fun onProgressUpdate(progress: Double)

    /**
     * Called when the upload has been successfully completed.
     *
     * @param timiMillis The total time taken for the upload operation in milliseconds.
     */
    fun onSuccess(timiMillis: Long)

    /**
     * Called when an error occurs during the upload process.
     *
     * @param error A message describing the error.
     * @param timiMillis The time elapsed before the error occurred in milliseconds.
     */
    fun onError(error: String, timiMillis: Long)

    /**
     * Called whenever the network connectivity state changes.
     *
     * @param isOnline True if the device is connected to the internet, false otherwise.
     */
    fun onNetworkStateChange(isOnline: Boolean)

    /**
     * Called when the upload process is initialized and about to start.
     */
    fun onUploadInit()

    /**
     * Called when the upload operation has been manually aborted or cancelled.
     */
    fun onAbort()

    /**
     * Called after each chunk is successfully handled (uploaded or processed).
     *
     * @param totalChunks Total number of chunks for the entire file.
     * @param filSizeInBytes The total size of the file in bytes.
     * @param currentChunk The index (starting from 0 or 1) of the current chunk.
     * @param currentChunkSizeInBytes Size of the current chunk in bytes.
     */
    fun onChunkHandled(
        totalChunks: Int,
        filSizeInBytes: Long,
        currentChunk: Int,
        currentChunkSizeInBytes: Long
    )

    /**
     * Called when a chunk upload fails after all retry attempts.
     *
     * @param failedChunkRetries Number of retry attempts made for this chunk.
     * @param chunkCount The index of the chunk that failed.
     * @param chunkSize The size of the failed chunk in bytes.
     */
    fun onChunkUploadingFailed(failedChunkRetries: Int, chunkCount: Int, chunkSize: Long)

    /**
     * Called when the upload is paused either manually or due to network issues.
     */
    fun onPauseUploading()

    /**
     * Called when a paused upload is resumed.
     */
    fun onResumeUploading()
}
