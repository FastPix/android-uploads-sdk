package io.fastpix.uploads.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test fun `shouldRetry true until maxAttempts reached`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 100, maxDelayMillis = 1000)
        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
    }

    @Test fun `maxAttempts zero never retries`() {
        val policy = RetryPolicy(maxAttempts = 0, baseDelayMillis = 100, maxDelayMillis = 1000)
        assertFalse(policy.shouldRetry(0))
    }

    @Test fun `delayFor produces exponential growth capped by maxDelay`() {
        val policy = RetryPolicy(
            maxAttempts = 10,
            baseDelayMillis = 1000,
            maxDelayMillis = 8_000,
            random = { 0.0 }, // disable jitter for deterministic assertions
        )
        assertEquals(1000L, policy.delayFor(1)) // 1000 * 2^0
        assertEquals(2000L, policy.delayFor(2)) // 1000 * 2^1
        assertEquals(4000L, policy.delayFor(3)) // 1000 * 2^2
        assertEquals(8000L, policy.delayFor(4)) // capped
        assertEquals(8000L, policy.delayFor(10)) // still capped
    }

    @Test fun `delayFor adds bounded jitter`() {
        val policy = RetryPolicy(
            maxAttempts = 10,
            baseDelayMillis = 1000,
            maxDelayMillis = 100_000,
            random = { 0.5 }, // half a base unit of jitter
        )
        assertEquals(1000L + 500L, policy.delayFor(1))
    }

    @Test fun `delayFor with max jitter still respects maxDelayMillis`() {
        val policy = RetryPolicy(
            maxAttempts = 10,
            baseDelayMillis = 1000,
            maxDelayMillis = 1500,
            random = { 1.0 },
        )
        // exponential base attempt 1 = 1000, jitter = 1000, sum = 2000, capped to 1500.
        assertEquals(1500L, policy.delayFor(1))
    }

    @Test fun `delayFor rejects attempt zero`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 100, maxDelayMillis = 1000)
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(0) }
    }

    @Test fun `constructor rejects invalid bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(maxAttempts = -1, baseDelayMillis = 100, maxDelayMillis = 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(maxAttempts = 3, baseDelayMillis = -1, maxDelayMillis = 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            // maxDelay < baseDelay
            RetryPolicy(maxAttempts = 3, baseDelayMillis = 1000, maxDelayMillis = 500)
        }
    }
}
