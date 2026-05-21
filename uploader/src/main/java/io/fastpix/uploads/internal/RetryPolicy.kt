package io.fastpix.uploads.internal

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with full jitter, bounded by [maxDelayMillis] and [maxAttempts].
 *
 * Stateless: [delayFor] is a pure function of attempt number and jitter source,
 * so it's trivially testable with a fixed RNG.
 */
internal class RetryPolicy(
    val maxAttempts: Int,
    val baseDelayMillis: Long,
    val maxDelayMillis: Long,
    private val random: () -> Double = Math::random,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0" }
        require(baseDelayMillis >= 0) { "baseDelayMillis must be >= 0" }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis ($maxDelayMillis) must be >= baseDelayMillis ($baseDelayMillis)"
        }
    }

    /** True if a further retry should be scheduled given [completedAttempts] retries already performed. */
    fun shouldRetry(completedAttempts: Int): Boolean = completedAttempts < maxAttempts

    /**
     * Delay before retry attempt number [attempt] (1-indexed). Implements
     * full-jitter backoff: `min(maxDelay, baseDelay * 2^(attempt-1)) + uniform(0..baseDelay)`.
     */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val exponential = baseDelayMillis * 2.0.pow(attempt - 1).toLong()
        val capped = min(exponential, maxDelayMillis)
        val jitter = (random() * baseDelayMillis).toLong().coerceAtLeast(0L)
        return min(capped + jitter, maxDelayMillis)
    }
}
