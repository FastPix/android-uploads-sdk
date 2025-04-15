package io.fastpix.uploads.internal

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException


class StreamingFileRequestBody(
    private val file: File?,
    private val mediaType: MediaType?,
    private val startByte: Long,
    private val endByte: Long,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long {
        val fileLength = file?.length() ?: 0L
        val safeEnd = minOf(endByte, fileLength)
        return (safeEnd - startByte).coerceAtLeast(0L)
    }

    override fun writeTo(sink: BufferedSink) {
        val fileLength = file?.length() ?: 0L
        val safeEnd = minOf(endByte, fileLength)
        val totalBytesToSend = safeEnd - startByte

        if (startByte >= fileLength || totalBytesToSend <= 0) {
            throw IOException("Invalid byte range: start=$startByte, end=$endByte, file size=$fileLength")
        }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { inputStream ->
            inputStream.skip(startByte)

            var uploaded = 0L
            var remaining = totalBytesToSend
            var read: Int

            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                read = inputStream.read(buffer, 0, toRead)
                if (read == -1) break

                sink.write(buffer, 0, read)
                uploaded += read
                remaining -= read

                // Report progress
                onProgress(uploaded, totalBytesToSend)
            }
        }

    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024 // 8KB
    }
}