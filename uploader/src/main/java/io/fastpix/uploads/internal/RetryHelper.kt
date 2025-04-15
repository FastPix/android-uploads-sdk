package io.fastpix.uploads.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Retry helper class for handling retries
 */
internal class RetryHelper {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun runAfterDelay(delayMillis: Long = 2000, task: () -> Unit) {
        scope.launch {
            delay(delayMillis)
            task()
        }
    }

    fun cancel() {
        scope.cancel()
    }
}
