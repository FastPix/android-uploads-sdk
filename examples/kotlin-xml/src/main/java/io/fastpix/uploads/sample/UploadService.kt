package io.fastpix.uploads.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Keeps the process alive while an upload runs in the background and shows a progress
// notification. Stops itself once the upload finishes.
class UploadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(UploadManager.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            UploadManager.state.collectLatest { state ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                if (!state.active) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: UploadUiState): Notification {
        ensureChannel()
        val text = if (state.active) "${state.fileName} — ${state.percent}%" else state.status
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uploading to FastPix")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(state.active)
            .setProgress(100, state.percent, false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "fastpix_uploads"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, UploadService::class.java)
            )
        }
    }
}
