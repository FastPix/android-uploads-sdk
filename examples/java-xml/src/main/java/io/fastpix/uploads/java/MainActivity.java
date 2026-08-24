package io.fastpix.uploads.java;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import io.fastpix.uploads.java.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private File selectedFile;

    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handlePickedFile(uri);
            });

    private final ActivityResultLauncher<String> requestNotifications =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final UploadManager.Observer observer = this::renderState;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.pickFileButton.setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        binding.startUploadButton.setOnClickListener(v -> startUpload());
        binding.pauseButton.setOnClickListener(v -> UploadManager.get().pause());
        binding.resumeButton.setOnClickListener(v -> UploadManager.get().resume());
        binding.abortButton.setOnClickListener(v -> UploadManager.get().cancel());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        UploadManager.get().addObserver(observer);
    }

    @Override protected void onStop() {
        UploadManager.get().removeObserver(observer);
        super.onStop();
    }

    private void startUpload() {
        if (selectedFile == null) {
            Toast.makeText(this, R.string.error_file_required, Toast.LENGTH_SHORT).show();
            return;
        }
        final File file = selectedFile;
        binding.statusText.setText("Status: creating upload…");
        FastPixApi.createUpload(new FastPixApi.UrlCallback() {
            @Override public void onUrl(String url) {
                UploadManager.get().startUpload(MainActivity.this, file, url);
            }

            @Override public void onError(String message) {
                binding.statusText.setText("Status: " + message);
            }
        });
    }

    private void renderState(UploadUiState state) {
        binding.uploadProgress.setProgress(state.percent);
        binding.progressText.setText(state.percent + "%");
        binding.statusText.setText("Status: " + state.status);
        binding.startUploadButton.setEnabled(!state.active);
        binding.pickFileButton.setEnabled(!state.active);
        binding.pauseButton.setEnabled(state.active);
        binding.abortButton.setEnabled(state.active);
    }

    private void handlePickedFile(Uri uri) {
        String name = queryDisplayName(uri);
        if (name == null) name = "upload_" + System.currentTimeMillis();
        File dest = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("cannot open " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            selectedFile = dest;
            binding.selectedFileText.setText(dest.getName() + " (" + dest.length() + " bytes)");
        } catch (Exception e) {
            selectedFile = null;
            Toast.makeText(this, R.string.error_copy_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.moveToFirst()) return c.getString(i);
            }
        }
        return null;
    }
}
