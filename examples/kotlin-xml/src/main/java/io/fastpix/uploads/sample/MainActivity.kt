package io.fastpix.uploads.sample

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.fastpix.uploads.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFile: File? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePickedFile(uri)
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        binding.pickFileButton.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.startUploadButton.setOnClickListener { getSignedUrl() }
        binding.pauseButton.setOnClickListener { UploadManager.pause() }
        binding.resumeButton.setOnClickListener { UploadManager.resume() }
        binding.abortButton.setOnClickListener { UploadManager.cancel() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UploadManager.state.collect { render(it) }
            }
        }
    }

    private fun render(state: UploadUiState) {
        binding.uploadProgress.progress = state.percent
        binding.progressText.text = "${state.percent}%"
        binding.statusText.text = "Status: ${state.status}"
        binding.startUploadButton.isEnabled = !state.active
        binding.pickFileButton.isEnabled = !state.active
        binding.pauseButton.isEnabled = state.active
        binding.abortButton.isEnabled = state.active
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

    private fun signedUrlRequestBody(): RequestBody {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        return "{\"corsOrigin\":\"*\",\"pushMediaSettings\":{\"accessPolicy\":\"public\",\"maxResolution\":\"2160p\"}}"
            .toRequestBody(mediaType)
    }

    private fun getSignedUrl() {
        val file = selectedFile
        if (file == null) {
            Toast.makeText(this, R.string.error_file_required, Toast.LENGTH_SHORT).show()
            return
        }
        val credentials = "${Config.TOKEN}:${Config.SECRET_KEY}"
        val auth = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        val headers = mapOf("Authorization" to auth, "Content-Type" to "application/json")
        binding.statusText.text = "Status: creating upload…"
        OkHttpHelper.post(
            url = "https://api.fastpix.com/v1/on-demand/upload",
            headers = headers,
            body = signedUrlRequestBody(),
            callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { binding.statusText.text = "Status: ${e.message}" }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        val url = JSONObject(body).getJSONObject("data").getString("url")
                        runOnUiThread { UploadManager.startUpload(this@MainActivity, file, url) }
                    } else {
                        runOnUiThread { binding.statusText.text = "Status: HTTP ${response.code}" }
                    }
                }
            },
        )
    }
}
