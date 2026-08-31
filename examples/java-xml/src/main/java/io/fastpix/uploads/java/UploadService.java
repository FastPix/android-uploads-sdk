package io.fastpix.uploads.java;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

// Keeps the process alive while an upload runs in the background and shows a progress
// notification. Stops itself once the upload finishes.
public class UploadService extends Service {

    private static final String CHANNEL_ID = "fastpix_uploads";
    private static final int NOTIFICATION_ID = 1;

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, UploadService.class));
    }

    private final UploadManager.Observer observer = state -> {
        manager().notify(NOTIFICATION_ID, buildNotification(state));
        if (!state.active) stopSelf();
    };

    @Nullable
    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(UploadManager.get().state()), type);
        UploadManager.get().addObserver(observer);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        UploadManager.get().removeObserver(observer);
        super.onDestroy();
    }

    private Notification buildNotification(UploadUiState state) {
        ensureChannel();
        String text = state.active ? state.fileName + " — " + state.percent + "%" : state.status;
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Uploading to FastPix")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(state.active)
                .setProgress(100, state.percent, false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        manager().createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW));
    }

    private NotificationManager manager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }
}
