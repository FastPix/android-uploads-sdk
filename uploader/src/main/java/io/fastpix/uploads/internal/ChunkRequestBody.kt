package io.fastpix.uploads.internal

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * OkHttp RequestBody that streams a byte range from a file via [ChunkReader].
 *
 * isOneShot() returns false so OkHttp may theoretically re-emit the body, but the
 * shared client is configured with retryOnConnectionFailure(false), so in practice
 * writeTo() is invoked at most once per Call.
 */
internal class ChunkRequestBody(
    private val chunkReader: ChunkReader,
    private val start: Long,
    private val endExclusive: Long,
    private val onBytesWritten: ((Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType = OCTET_STREAM

    override fun contentLength(): Long = endExclusive - start

    override fun writeTo(sink: BufferedSink) {
        chunkReader.readInto(
            start = start,
            endExclusive = endExclusive,
            sink = sink,
            onBytesWritten = onBytesWritten,
        )
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
