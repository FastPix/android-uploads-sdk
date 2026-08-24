package io.fastpix.uploads.compose

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { UploadScreen() }
            }
        }
    }
}

@Composable
private fun UploadScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UploadManager.state.collectAsState()
    var pickedFile by remember { mutableStateOf<File?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Needed to show the upload notification on Android 13+.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pickedFile = copyToCache(context, uri) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("FastPix Background Upload", style = MaterialTheme.typography.headlineSmall)

        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.active) {
            Text("Pick file")
        }
        Text(pickedFile?.let { "${it.name} (${it.length()} bytes)" } ?: "No file selected")

        Button(
            onClick = {
                val file = pickedFile ?: return@Button
                errorMsg = null
                scope.launch {
                    runCatching { FastPixApi.createUpload() }
                        .onSuccess { UploadManager.startUpload(context, file, it.url) }
                        .onFailure { errorMsg = "Create upload failed: ${it.message}" }
                }
            },
            enabled = pickedFile != null && !state.active,
        ) { Text("Start upload") }

        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${state.percent}%  •  ${errorMsg ?: state.status}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { UploadManager.pause() }, enabled = state.active) { Text("Pause") }
            Button(onClick = { UploadManager.resume() }, enabled = state.active) { Text("Resume") }
            Button(onClick = { UploadManager.cancel() }, enabled = state.active) { Text("Cancel") }
        }
    }
}

private fun copyToCache(context: Context, uri: Uri): File? {
    val name = queryDisplayName(context, uri) ?: "upload_${System.currentTimeMillis()}"
    val dest = File(context.cacheDir, name)
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: error("cannot open $uri")
        dest
    }.getOrNull()
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
    }
    return null
}
