package io.fastpix.uploads.sample



import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object OkHttpHelper {

    private val client: OkHttpClient by lazy {
        val interceptor = HttpLoggingInterceptor()
        interceptor.level = HttpLoggingInterceptor.Level.BODY
        val builder = OkHttpClient.Builder()
        builder.addInterceptor(interceptor)
        builder.build()
        OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .readTimeout(10000, TimeUnit.SECONDS)
            .writeTimeout(10000, TimeUnit.SECONDS)
            .connectTimeout(10000, TimeUnit.SECONDS)
            .build()
    }

    fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody,
        callback: Callback
    ) {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        client.newCall(request).enqueue(callback)
    }

    fun put(
        url: String,
        fileContent: ByteArray,
        callback: Callback
    ) {
        val requestBody =
            fileContent.toRequestBody(
                "application/octet-stream".toMediaTypeOrNull(),
            )

        val request = Request.Builder()
            .url(url)
            .put(requestBody) // Use .post() for POST requests
            .build();
        client.newCall(request).enqueue(callback)
    }
}