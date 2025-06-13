package io.fastpix.uploads.internal

import android.content.Context
import android.util.Log
import io.fastpix.uploads.ChunkSizeException
import io.fastpix.uploads.FileContentEmptyException
import io.fastpix.uploads.FileNotFoundException
import io.fastpix.uploads.FileNotReadableException
import io.fastpix.uploads.SignedUrlNotFoundException
import io.fastpix.uploads.model.FastPixUploadCallbacks
import io.fastpix.uploads.utils.NetworkHandler
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.ceil


class FastPixUploadSdk private constructor(
    private val context: Context,
    private val _file: File?,
    private val _signedUrl: String?,
    private val _chunkSize: Long = 16384,
    private val retryDelay: Long = 2000L,
    private val maxRetries: Int = 5,
    private val callback: FastPixUploadCallbacks?
) {
    private var _totalChunks = 0.0 // Number of chunks
    private var isOffline = false // Offline Check
    private var isPause = false
    private var isAborted = false
    private var isOnlyChunk = false

    private var chunkOffset = 0L
    private var successiveChunkCount = 0L
    private var failedChunkRetries = 0

    private var nextChunkRangeStart = 0L
    private var startTime = 0L
    private var uploadCall: Call? = null // Api call reference for uploading
    private val okHttpClient: OkHttpClient = createOkHttpClient() // Api Client
    private val maxDelayMs: Long = 10000
    private val retryHelper = RetryHelper()


    /**
     * Api Client with added interceptor for loggin API body
     */
    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }) // Change to Level.BODY for detailed logs
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS).build()
    }

    /**
     * Builder Function to initialize the required params for SDK
     * @param context, required
     * @param file, required
     * @param signedUrl, required
     * @param callback, non mandatory in case of failure
     * @param chunkSize, non mandatory in BYTES
     */
    class Builder(private val context: Context) {
        private var file: File? = null
        private var signedUrl: String? = null
        private var chunkSize: Long = 16384
        private var callback: FastPixUploadCallbacks? = null
        private var retryDelay = 2000L
        private var maxRetries = 5

        fun setFile(file: File?) = apply { this.file = file }

        fun setSignedUrl(signedUrl: String) = apply { this.signedUrl = signedUrl }

        fun setChunkSize(chunkSize: Long) = apply { this.chunkSize = chunkSize }

        fun setRetryDelay(retryDelay: Long) = apply { this.retryDelay = retryDelay }

        fun callback(callback: FastPixUploadCallbacks) = apply { this.callback = callback }

        fun setMaxRetries(maxRetries: Int) = apply { this.maxRetries = maxRetries }

        /**
         * Provide the instance of FastPixUploadSdk
         * And also verifies if the provided data in SDK is valid or not
         * @param chunkSize should be between 5mbs and 500mbs
         */
        fun build(): FastPixUploadSdk {
            if (chunkSize < Constants.MINIMUM_CHUNK_SIZE || chunkSize > Constants.MAXIMUM_CHUNK_SIZE) {
                throw ChunkSizeException()
            }
            if (file == null || file?.exists() == false) {
                throw FileNotFoundException()
            }
            if (file?.canRead() == false) {
                throw FileNotReadableException()
            }
            if (file?.length() == 0L) {
                throw FileContentEmptyException()
            }
            if (signedUrl == null) {
                throw SignedUrlNotFoundException()
            }
            return FastPixUploadSdk(
                context = context,
                _file = file,
                _signedUrl = signedUrl,
                _chunkSize = chunkSize,
                retryDelay = retryDelay,
                maxRetries = maxRetries,
                callback = callback
            )
        }
    }

    /**
     * Starts uploading process and also handles the network state
     * @param _totalChunks gets calculated on the call of this function
     */
    fun startUpload() {
        startTime = System.currentTimeMillis()
        _totalChunks = ceil((_file?.length()?.toDouble() ?: 0.0) / _chunkSize)
        if (_totalChunks == 1.0) {
            isOnlyChunk = true
        }
        callback?.onChunkHandled(
            _totalChunks.toInt(), _file?.length() ?: 0L, chunkCount, getCurrentChunkSize()
        )
        handleNetworkState()

        initUploadProcess()
    }

    private fun initUploadProcess() {
        callback?.onUploadInit()
        handleChunkStreaming()
    }

    fun getTotalTimeSpentOnProcess(): Long {
        val totalTime = System.currentTimeMillis() - startTime
        return totalTime
    }

    var chunkCount = 0

    private fun handleChunkStreaming() {
        val fileLength = _file?.length() ?: 0L
        val start = nextChunkRangeStart
        val end = minOf(_chunkSize * (chunkOffset + 1), fileLength)
        val contentRange = "bytes $start-${end - 1}/${fileLength}"

        val requestBody = StreamingFileRequestBody(file = _file,
            mediaType = "application/octet-stream".toMediaType(),
            startByte = start,
            endByte = end,
            onProgress = { written, total ->
                val currentUploaded = start + written
                val progressCount = (currentUploaded * 100) / (_file?.length() ?: 0L)
                callback?.onProgressUpdate(progressCount.toDouble())
            })

        try {
            val request = Request.Builder()
                .url(_signedUrl.toString()).addHeader("Content-Range", contentRange)
                .put(requestBody).build()
            uploadCall = okHttpClient.newCall(request)
            uploadCall?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback?.onError(
                        e.message ?: Constants.SOMETHING_WENT_WRONG, getTotalTimeSpentOnProcess()
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful || response.code == 308) {
                        val rangeHeader = response.header("Range")
                        if (response.code == 308 && !rangeHeader.equals("bytes=0-${end - 1}")) {
                            handleChunkUploadFailure(response.message)
                            Log.e("statusCode", "yes");
                        } else {
                            if (retryHelper != null) {
                                retryHelper.cancel()
                            }
                            failedChunkRetries = 0
                            Log.e("statusCode", "No");
                            chunkCount++
                            successiveChunkCount++
                            chunkOffset++
                            nextChunkRangeStart = end
                            callback?.onChunkHandled(
                                _totalChunks.toInt(),
                                _file?.length() ?: 0L,
                                chunkCount,
                                getCurrentChunkSize()
                            )
                            validateUploadStatus()
                        }
                    } else {
                        handleChunkUploadFailure(response.message)
                    }
                    response.close()
                }
            })
        } catch (ex: Exception) {
//            handleChunkUploadFailure(ex.message.orEmpty())
        }
    }

    private fun getCurrentChunkSize(): Long {
        val fileLength = _file?.length() ?: 0L
        val safeEnd = minOf(minOf(_chunkSize * (chunkOffset + 1), fileLength), fileLength)
        return (safeEnd - nextChunkRangeStart).coerceAtLeast(0L)
    }

    fun abort() {
        if (uploadCall != null) uploadCall?.cancel()
        unregisterSDK()
        callback?.onAbort()
    }

    /**
     * Validate Upload Status
     */
    private fun validateUploadStatus() {
        if (_totalChunks.toLong() == successiveChunkCount) {
            callback?.onSuccess(getTotalTimeSpentOnProcess())
            unregisterSDK()
        } else {
            handleChunkStreaming()
        }
    }

    /**
     * Handles the logic if the any chunk gets failed to upload
     * @param message, Error message
     */
    private fun handleChunkUploadFailure(message: String) {
        if (!isPause && !isOffline && !isAborted
            && _totalChunks > 0 && (failedChunkRetries < maxRetries)
        ) {
            retryChunkUploading()
        } else {
            callback?.onError("Chunk Uploading Failed:\n$message", getTotalTimeSpentOnProcess())
            unregisterSDK()
        }
    }

    /**
     * Retry the failed chunk
     */
    private fun retryChunkUploading() {
        val delay = (Math.pow(2.0, failedChunkRetries.toDouble()) * retryDelay).toLong()
        val jitter = (Math.random() * retryDelay).toLong()
        val brackOff = minOf(delay + jitter, maxDelayMs)
        Log.e(
            "timeStamp",
            Calendar.getInstance().time.toString() + " " + brackOff + " " + failedChunkRetries
        )
        retryHelper.runAfterDelay(brackOff) {
            failedChunkRetries++
            callback?.onChunkUploadingFailed(
                failedChunkRetries, chunkCount, getCurrentChunkSize()
            )
            handleChunkStreaming()
        }

    }

    /**
     * Pauses the uploading process
     */
    fun pauseUploading() {
        if (!isOffline && !isPause && !isAborted) {
            isPause = true
            if (uploadCall != null) {
                uploadCall?.cancel()
            }
        }
    }

    /**
     * Resumes the uploading process
     */
    fun resumeUploading() {
        if (isPause && !isOffline && !isAborted && (_totalChunks.toLong() != successiveChunkCount)) {
            isPause = false
            handleChunkStreaming()
        }
    }

    /**
     * Aborts the uploading process and releases all the resources
     */
    fun unregisterSDK() {
        if (uploadCall != null) {
            uploadCall?.cancel()
        }
        isOnlyChunk = false
        chunkOffset = 0
        successiveChunkCount = 0
        _totalChunks = 0.0
        failedChunkRetries = 0
        isOffline = false
        isPause = false
        isAborted = false
    }

    var isFirstTime = true

    /**
     * Handles the network state
     */
    private fun handleNetworkState() {
        NetworkHandler.getInstance(context)
            .setNetworkListener(object : NetworkHandler.NetworkListener {
                override fun onAvailable() {
                    callback?.onNetworkStateChange(true)
                    isOffline = false
                    if (!isPause && !isAborted && !isFirstTime) {
                        handleChunkStreaming()
                    } else {
                        isFirstTime = false
                    }
                }

                override fun onLost() {
                    callback?.onNetworkStateChange(false)
                    isOffline = true
                    if (uploadCall != null) {
                        uploadCall?.cancel()
                    }
                }
            })
    }

}