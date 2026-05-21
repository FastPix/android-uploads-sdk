package io.fastpix.uploads.internal

/**
 * Pure functions implementing the wire-level rules of GCS resumable upload,
 * per https://cloud.google.com/storage/docs/performing-resumable-uploads.
 *
 * No I/O, no Android types, no mutable state — everything in here is unit-testable
 * without a network or a device.
 */
internal object GcsResumableProtocol {

    /** GCS requires every non-final chunk to be a multiple of 256 KiB. */
    const val CHUNK_SIZE_MULTIPLE: Long = 256L * 1024L

    /** "Resume Incomplete." GCS returns this when more bytes are expected. */
    const val HTTP_RESUME_INCOMPLETE: Int = 308

    /** Session URI no longer valid — caller must mint a new one. */
    const val HTTP_GONE: Int = 410

    /** Build the Content-Range header for a chunk PUT: "bytes start-endInclusive/total". */
    fun chunkContentRange(start: Long, endExclusive: Long, total: Long): String {
        require(start >= 0) { "start must be >= 0, was $start" }
        require(endExclusive in (start + 1)..total) {
            "endExclusive must be in (start, total], was $endExclusive (start=$start, total=$total)"
        }
        return "bytes $start-${endExclusive - 1}/$total"
    }

    /** Build the Content-Range header for a status-query PUT: "bytes * /total" (no spaces). */
    fun statusQueryContentRange(total: Long): String {
        require(total > 0) { "total must be > 0, was $total" }
        return "bytes */$total"
    }

    /**
     * Parse the `Range` response header GCS returns with a 308. Format is `bytes=0-X`
     * where X is the highest byte index persisted. Returns null when the header is
     * absent or zero bytes have been persisted (GCS omits Range in that case).
     *
     * The next byte to send is `result + 1`.
     */
    fun parsePersistedEndByte(rangeHeader: String?): Long? {
        if (rangeHeader.isNullOrBlank()) return null
        // Expected: "bytes=0-12345"
        val eq = rangeHeader.indexOf('=')
        if (eq < 0) return null
        val dash = rangeHeader.indexOf('-', startIndex = eq + 1)
        if (dash < 0) return null
        val startPart = rangeHeader.substring(eq + 1, dash).trim()
        val endPart = rangeHeader.substring(dash + 1).trim()
        if (startPart != "0") return null // GCS resumable always reports cumulative range from 0
        return endPart.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Next absolute file byte the client should send, given a 308 Range header. */
    fun nextByteToSend(rangeHeader: String?): Long {
        val persistedEnd = parsePersistedEndByte(rangeHeader) ?: return 0L
        return persistedEnd + 1L
    }

    /**
     * Classify an HTTP response code from a chunk PUT or status query into a typed outcome.
     * Pure dispatcher — does not consult headers; for 308 the caller still has to read `Range`.
     */
    fun classify(statusCode: Int): ResponseClass = when {
        statusCode == 200 || statusCode == 201 -> ResponseClass.Complete
        statusCode == HTTP_RESUME_INCOMPLETE -> ResponseClass.Incomplete
        statusCode == HTTP_GONE -> ResponseClass.SessionExpired
        statusCode == 408 || statusCode == 429 -> ResponseClass.Retryable
        statusCode in 500..599 -> ResponseClass.Retryable
        statusCode in 400..499 -> ResponseClass.FatalClient
        else -> ResponseClass.Unexpected
    }

    enum class ResponseClass {
        /** 200/201 — upload finished, object now exists in GCS. */
        Complete,

        /** 308 Resume Incomplete — read Range header to know what to send next. */
        Incomplete,

        /** 410 Gone — session is dead, mint a new one. */
        SessionExpired,

        /** 408/429/5xx — transient, retry with backoff. */
        Retryable,

        /** 4xx other than 408/429/410 — caller misconfigured; do not retry. */
        FatalClient,

        /** 1xx/3xx (non-308) or anything outside the spec. */
        Unexpected,
    }

    /**
     * Validate Builder-supplied chunk size against the GCS spec.
     * Throws on invalid input so the Builder can surface InvalidConfiguration.
     */
    fun validateChunkSize(chunkSize: Long, minBytes: Long, maxBytes: Long) {
        require(chunkSize in minBytes..maxBytes) {
            "chunkSize must be in [$minBytes, $maxBytes], was $chunkSize"
        }
        require(chunkSize % CHUNK_SIZE_MULTIPLE == 0L) {
            "chunkSize ($chunkSize) must be a multiple of 256 KiB ($CHUNK_SIZE_MULTIPLE) per GCS resumable spec"
        }
    }
}
