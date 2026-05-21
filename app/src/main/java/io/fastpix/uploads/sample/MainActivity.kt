package io.fastpix.uploads.sample

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.fastpix.uploads.FastPixUploader
import io.fastpix.uploads.UploadError
import io.fastpix.uploads.UploadListener
import io.fastpix.uploads.UploadState
import io.fastpix.uploads.sample.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedFile: File? = null
    private var uploader: FastPixUploader? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePickedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.pickFileButton.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.startUploadButton.setOnClickListener { getSignedUrl() }
        binding.pauseButton.setOnClickListener { uploader?.pause() }
        binding.resumeButton.setOnClickListener { uploader?.resume() }
        binding.abortButton.setOnClickListener { uploader?.cancel() }
    }

    private fun handlePickedFile(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
        val destination = File(cacheDir, displayName)

        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            } ?: error("Unable to open input stream")
        }.onSuccess {
            selectedFile = destination
            binding.selectedFileText.text = "${destination.name} (${destination.length()} bytes)"
        }.onFailure {
            selectedFile = null
            Toast.makeText(this, R.string.error_copy_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return null
    }

    private fun getRequestForSignedUrl(): RequestBody {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request =
            "{\"corsOrigin\":\"*\",\"pushMediaSettings\":{\"metadata\":{\"key1\":\"value1\"},\"accessPolicy\":\"public\",\"maxResolution\":\"2160p\"}}".toRequestBody(
                mediaType
            )
        return request
    }

    private var _signedUrl: String? = null
    private var _uploadId: String? = null

    private var _token = ""
    private var _secretKey = ""

    private fun getSignedUrl() {
        val requestBody = getRequestForSignedUrl()
        val credentials = _token.plus(":").plus(_secretKey)
        val auth = "Basic ".plus(Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP))
        val headers = mapOf(Pair("Authorization", auth), Pair("Content-Type", "application/json"))
        OkHttpHelper.post(
            "https://api.fastpix.com/v1/on-demand/upload",
            headers = headers,
            body = requestBody,
            callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("TAG", "onFailure: ${e.printStackTrace()}", )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val jsonObject = JSONObject(responseBody)
                        val jsonData = jsonObject.getJSONObject("data")
                        _signedUrl = jsonData.getString("url")
                        _uploadId = jsonData.getString("uploadId")
                        // OkHttp invokes this callback on its dispatcher thread.
                        // startUpload() touches Views, so hop back to the main looper.
                        runOnUiThread { startUpload(_signedUrl) }
                    }
                }

            })
    }

    private fun startUpload(signedUrl: String?) {
        val file = selectedFile
        if (file == null) {
            Toast.makeText(this, R.string.error_file_required, Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            FastPixUploader.Builder(this)
                .file(file)
                .sessionUri(signedUrl!!)
                .chunkSize(8L * 1024 * 1024) // 8 MiB (multiple of 256 KiB)
                .maxRetries(5)
                .retryBaseDelay(2_000L)
                .retryMaxDelay(30_000L)
                .listener(uploadListener)
                .build()
        }.onSuccess { sdk ->
            uploader = sdk
            setUploadingUiState(true)
            sdk.start()
        }.onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: error::class.java.simpleName,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun setUploadingUiState(uploading: Boolean) {
        binding.startUploadButton.isEnabled = !uploading
        binding.pickFileButton.isEnabled = !uploading
        binding.pauseButton.isEnabled = uploading
        binding.abortButton.isEnabled = uploading
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = "Status: $text"
    }

    // Listener callbacks already arrive on the main looper thanks to CallbackDispatcher,
    // so no runOnUiThread wrappers are needed here.
    private val uploadListener = object : UploadListener {
        override fun onStateChange(state: UploadState) {
            updateStatus("state: $state")
            if (state.isTerminal) setUploadingUiState(false)
        }

        override fun onProgress(bytesUploaded: Long, totalBytes: Long, percentage: Double) {
            val pct = percentage.roundToInt().coerceIn(0, 100)
            binding.uploadProgress.progress = pct
            binding.progressText.text = "$pct%"
        }

        override fun onPrepared(totalChunks: Int, totalBytes: Long, chunkSize: Long) {
            updateStatus("prepared: $totalChunks chunks, $totalBytes bytes")
        }

        override fun onChunkUploaded(chunkIndex: Int, totalChunks: Int, bytesAcked: Long) {
            updateStatus("chunk $chunkIndex / $totalChunks")
        }

        override fun onRetryScheduled(attempt: Int, delayMillis: Long, cause: UploadError) {
            updateStatus("retry #$attempt in ${delayMillis} ms (${cause.message})")
        }

        override fun onNetworkStateChange(online: Boolean) {
            updateStatus(if (online) "online" else "offline")
        }

        override fun onSuccess(elapsedMillis: Long) {
            binding.uploadProgress.progress = 100
            binding.progressText.text = "100%"
            updateStatus("completed in $elapsedMillis ms")
        }

        override fun onFailure(error: UploadError, elapsedMillis: Long) {
            updateStatus("failure: ${error.message}")
        }

        override fun onCancelled(elapsedMillis: Long) {
            updateStatus("cancelled after $elapsedMillis ms")
        }
    }

    override fun onDestroy() {
        uploader?.cancel()
        uploader = null
        super.onDestroy()
    }
}
