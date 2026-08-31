package io.fastpix.uploads.java;

import android.content.Context;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

import io.fastpix.uploads.FastPixUploader;
import io.fastpix.uploads.UploadError;
import io.fastpix.uploads.UploadListener;
import io.fastpix.uploads.UploadState;

// Holds the single in-flight upload outside the Activity so it survives rotation and
// backgrounding. Not persisted, so killing the app ends the upload.
public final class UploadManager {

    public interface Observer {
        void onState(UploadUiState state);
    }

    private static final UploadManager INSTANCE = new UploadManager();

    public static UploadManager get() {
        return INSTANCE;
    }

    private UploadManager() {}

    private final UploadUiState state = new UploadUiState();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private FastPixUploader uploader;

    public UploadUiState state() {
        return state;
    }

    public void addObserver(Observer o) {
        observers.add(o);
        o.onState(state);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public void startUpload(Context context, File file, String sessionUri) {
        Context app = context.getApplicationContext();
        cancel();
        try {
            uploader = new FastPixUploader.Builder(app)
                    .file(file)
                    .sessionUri(sessionUri)
                    .chunkSize(Config.CHUNK_SIZE)
                    .listener(listener)
                    .build();
        } catch (UploadError e) {
            state.active = false;
            state.status = "Error: " + e.getMessage();
            notifyObservers();
            return;
        }
        state.active = true;
        state.percent = 0;
        state.fileName = file.getName();
        state.status = "Starting";
        notifyObservers();
        UploadService.start(app);
        uploader.start();
    }

    public void pause() {
        if (uploader != null) uploader.pause();
    }

    public void resume() {
        if (uploader != null) uploader.resume();
    }

    public void cancel() {
        if (uploader != null) {
            uploader.cancel();
            uploader = null;
        }
    }

    private void notifyObservers() {
        for (Observer o : observers) o.onState(state);
    }

    private final UploadListener listener = new UploadListener() {
        @Override public void onStateChange(UploadState s) {
            state.active = !s.isTerminal();
            state.status = s.name();
            notifyObservers();
        }

        @Override public void onProgress(long bytesUploaded, long totalBytes, double percentage) {
            state.percent = (int) Math.min(100, Math.max(0, Math.round(percentage)));
            notifyObservers();
        }

        // The callbacks below aren't used by this sample; the UI only tracks state and progress.
        @Override public void onPrepared(int totalChunks, long totalBytes, long chunkSize) {
            // No-op.
        }

        @Override public void onChunkUploaded(int chunkIndex, int totalChunks, long bytesAcked) {
            // No-op.
        }

        @Override public void onRetryScheduled(int attempt, long delayMillis, UploadError cause) {
            // No-op.
        }

        @Override public void onNetworkStateChange(boolean online) {
            // No-op.
        }

        @Override public void onSuccess(long elapsedMillis) {
            state.percent = 100;
            state.active = false;
            state.status = "Completed";
            notifyObservers();
        }

        @Override public void onFailure(UploadError error, long elapsedMillis) {
            state.active = false;
            state.status = "Failed: " + error.getMessage();
            notifyObservers();
        }

        @Override public void onCancelled(long elapsedMillis) {
            state.active = false;
            state.status = "Cancelled";
            notifyObservers();
        }
    };
}
