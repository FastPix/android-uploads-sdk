package io.fastpix.uploads.internal

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

internal object HttpClientFactory {

    @Volatile private var shared: OkHttpClient? = null

    /**
     * Returns a process-wide shared OkHttpClient configured for resumable chunk PUTs.
     *
     * Critically: retryOnConnectionFailure(false). We do our own retry/backoff at the
     * upload-engine layer with status-query recovery; letting OkHttp silently re-PUT
     * the body would break confirmed-progress accounting.
     */
    fun get(debugLogging: Boolean): OkHttpClient {
        shared?.let { return it }
        return synchronized(this) {
            shared ?: build(debugLogging).also { shared = it }
        }
    }

    private fun build(debugLogging: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        if (debugLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }
}
