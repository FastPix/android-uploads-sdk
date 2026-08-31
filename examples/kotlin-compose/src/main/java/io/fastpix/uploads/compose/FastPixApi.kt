package io.fastpix.uploads.compose

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SignedUpload(val url: String, val uploadId: String)

// Calls the FastPix API to create an upload and get its resumable session URL.
object FastPixApi {

    private const val ENDPOINT = "https://api.fastpix.com/v1/on-demand/upload"
    private const val BODY =
        "{\"corsOrigin\":\"*\",\"pushMediaSettings\":{\"accessPolicy\":\"public\",\"maxResolution\":\"2160p\"}}"

    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun createUpload(): SignedUpload = withContext(Dispatchers.IO) {
        val credentials = "${Config.TOKEN}:${Config.SECRET_KEY}"
        val auth = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .post(BODY.toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "HTTP ${response.code}: $body" }
            val data = JSONObject(body).getJSONObject("data")
            SignedUpload(data.getString("url"), data.getString("uploadId"))
        }
    }
}
