package io.fastpix.uploads.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GcsResumableProtocolTest {

    // ---- chunkContentRange --------------------------------------------------

    @Test fun `chunkContentRange formats inclusive end and total`() {
        assertEquals(
            "bytes 0-262143/1048576",
            GcsResumableProtocol.chunkContentRange(0, 262144, 1048576)
        )
    }

    @Test fun `chunkContentRange handles final partial chunk`() {
        // 9 MiB file, 5 MiB chunk: second chunk is bytes 5242880-9437183/9437184.
        assertEquals(
            "bytes 5242880-9437183/9437184",
            GcsResumableProtocol.chunkContentRange(5_242_880L, 9_437_184L, 9_437_184L)
        )
    }

    @Test fun `chunkContentRange rejects zero-length`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.chunkContentRange(100, 100, 1000)
        }
    }

    @Test fun `chunkContentRange rejects end past total`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.chunkContentRange(0, 1001, 1000)
        }
    }

    @Test fun `chunkContentRange rejects negative start`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.chunkContentRange(-1, 100, 1000)
        }
    }

    // ---- statusQueryContentRange --------------------------------------------

    @Test fun `statusQueryContentRange uses asterisk for range`() {
        assertEquals("bytes */9437184", GcsResumableProtocol.statusQueryContentRange(9_437_184L))
    }

    @Test fun `statusQueryContentRange rejects non-positive total`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.statusQueryContentRange(0)
        }
    }

    // ---- parsePersistedEndByte ----------------------------------------------

    @Test fun `parsePersistedEndByte parses standard GCS 308 Range header`() {
        assertEquals(524287L, GcsResumableProtocol.parsePersistedEndByte("bytes=0-524287"))
    }

    @Test fun `parsePersistedEndByte returns null when header is missing`() {
        assertNull(GcsResumableProtocol.parsePersistedEndByte(null))
        assertNull(GcsResumableProtocol.parsePersistedEndByte(""))
        assertNull(GcsResumableProtocol.parsePersistedEndByte("   "))
    }

    @Test fun `parsePersistedEndByte rejects non-zero start (not spec-compliant)`() {
        assertNull(GcsResumableProtocol.parsePersistedEndByte("bytes=100-524287"))
    }

    @Test fun `parsePersistedEndByte rejects malformed values`() {
        assertNull(GcsResumableProtocol.parsePersistedEndByte("bytes=0-"))
        assertNull(GcsResumableProtocol.parsePersistedEndByte("nonsense"))
        assertNull(GcsResumableProtocol.parsePersistedEndByte("bytes=0-abc"))
    }

    @Test fun `nextByteToSend returns 0 when no bytes persisted`() {
        assertEquals(0L, GcsResumableProtocol.nextByteToSend(null))
        assertEquals(0L, GcsResumableProtocol.nextByteToSend(""))
    }

    @Test fun `nextByteToSend is persistedEnd plus one`() {
        assertEquals(524288L, GcsResumableProtocol.nextByteToSend("bytes=0-524287"))
    }

    // ---- classify -----------------------------------------------------------

    @Test fun `classify maps success codes to Complete`() {
        assertEquals(GcsResumableProtocol.ResponseClass.Complete, GcsResumableProtocol.classify(200))
        assertEquals(GcsResumableProtocol.ResponseClass.Complete, GcsResumableProtocol.classify(201))
    }

    @Test fun `classify 308 is Incomplete`() {
        assertEquals(
            GcsResumableProtocol.ResponseClass.Incomplete,
            GcsResumableProtocol.classify(308)
        )
    }

    @Test fun `classify 410 is SessionExpired`() {
        assertEquals(
            GcsResumableProtocol.ResponseClass.SessionExpired,
            GcsResumableProtocol.classify(410)
        )
    }

    @Test fun `classify retryable codes`() {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            assertEquals(
                "code=$code should be retryable",
                GcsResumableProtocol.ResponseClass.Retryable,
                GcsResumableProtocol.classify(code)
            )
        }
    }

    @Test fun `classify 4xx other than 408 and 429 and 410 are FatalClient`() {
        listOf(400, 401, 403, 404, 412).forEach { code ->
            assertEquals(
                "code=$code should be fatal-client",
                GcsResumableProtocol.ResponseClass.FatalClient,
                GcsResumableProtocol.classify(code)
            )
        }
    }

    @Test fun `classify non-spec codes are Unexpected`() {
        assertEquals(
            GcsResumableProtocol.ResponseClass.Unexpected,
            GcsResumableProtocol.classify(100)
        )
        assertEquals(
            GcsResumableProtocol.ResponseClass.Unexpected,
            GcsResumableProtocol.classify(301)
        )
    }

    // ---- validateChunkSize --------------------------------------------------

    @Test fun `validateChunkSize accepts 256 KiB multiples in range`() {
        GcsResumableProtocol.validateChunkSize(5L * 1024 * 1024, 5L * 1024 * 1024, 500L * 1024 * 1024)
        GcsResumableProtocol.validateChunkSize(8L * 1024 * 1024, 5L * 1024 * 1024, 500L * 1024 * 1024)
    }

    @Test fun `validateChunkSize rejects non-256-KiB multiple`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.validateChunkSize(
                5L * 1024 * 1024 + 1,
                5L * 1024 * 1024,
                500L * 1024 * 1024,
            )
        }
        assertTrue(
            "expected message to mention 256 KiB, was: ${ex.message}",
            ex.message!!.contains("256 KiB")
        )
    }

    @Test fun `validateChunkSize rejects below minimum`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.validateChunkSize(
                256L * 1024,
                5L * 1024 * 1024,
                500L * 1024 * 1024,
            )
        }
    }

    @Test fun `validateChunkSize rejects above maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            GcsResumableProtocol.validateChunkSize(
                501L * 1024 * 1024,
                5L * 1024 * 1024,
                500L * 1024 * 1024,
            )
        }
    }
}
