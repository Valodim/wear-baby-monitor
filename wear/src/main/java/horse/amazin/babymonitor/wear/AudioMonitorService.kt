package horse.amazin.babymonitor.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioStreamController: AudioStreamController
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioStreamController = AudioStreamController(applicationContext).also { it.start() }
        observeControllerState()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText()))
        when (intent?.action) {
            ACTION_START_STREAM -> audioStreamController.startStreaming()
            ACTION_STOP_STREAM -> audioStreamController.stopStreaming()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        audioStreamController.stop()
        serviceScope.cancel()
    }

    private fun observeControllerState() {
        serviceScope.launch {
            audioStreamController.streamStatus.collect { status ->
                AudioMonitorServiceState.updateStreamStatus(status)
            }
        }
        serviceScope.launch {
            audioStreamController.isStreaming.collect { streaming ->
                AudioMonitorServiceState.updateStreaming(streaming)
                updateNotification(currentNotificationText())
            }
        }
        serviceScope.launch {
            audioStreamController.currentLoudness.collect { loudness ->
                AudioMonitorServiceState.updateLoudness(loudness)
            }
        }
    }

    private fun currentNotificationText(): String {
        return if (AudioMonitorServiceState.isStreaming.value) {
            "Streaming audio"
        } else {
            "Idle"
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baby Monitor")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Monitoring",
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "audio_monitoring"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_STREAM = "horse.amazin.babymonitor.wear.action.START_STREAM"
        const val ACTION_STOP_STREAM = "horse.amazin.babymonitor.wear.action.STOP_STREAM"
    }
}
