package io.fastpix.uploads.internal

import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads a [start, endExclusive) byte slice of a file into an okio BufferedSink.
 *
 * Uses RandomAccessFile.seek() rather than InputStream.skip(): seek is O(1) and
 * always positions to the exact byte, whereas InputStream.skip() can short-skip
 * and silently misalign uploads.
 */
internal class ChunkReader(private val file: File) {

    val totalBytes: Long = file.length()

    /**
     * Stream the requested byte range into [sink], invoking [onBytesWritten] after
     * each buffer's worth is flushed. Returns the number of bytes written.
     *
     * Throws [IOException] only on actual I/O failure; range validation throws
     * [IllegalArgumentException].
     */
    @Throws(IOException::class)
    fun readInto(
        start: Long,
        endExclusive: Long,
        sink: BufferedSink,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onBytesWritten: ((Long) -> Unit)? = null,
    ): Long {
        require(start in 0..totalBytes) { "start out of range: $start (file size=$totalBytes)" }
        require(endExclusive in start..totalBytes) {
            "endExclusive out of range: $endExclusive (start=$start, file size=$totalBytes)"
        }
        val toSend = endExclusive - start
        if (toSend == 0L) return 0L

        val buffer = ByteArray(bufferSize)
        var written = 0L
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (written < toSend) {
                val want = minOf(buffer.size.toLong(), toSend - written).toInt()
                val read = raf.read(buffer, 0, want)
                if (read == -1) {
                    throw IOException(
                        "Unexpected EOF reading $file at offset ${start + written} " +
                            "(wanted $toSend bytes, got $written)"
                    )
                }
                sink.write(buffer, 0, read)
                written += read
                onBytesWritten?.invoke(written)
            }
        }
        return written
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 64 * 1024
    }
}
