package horse.amazin.babymonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class BabyMonitorService : Service() {
    private lateinit var playbackController: PlaybackController
    private lateinit var loudnessReceiver: LoudnessReceiver
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        playbackController = PlaybackController(applicationContext)
        loudnessReceiver = LoudnessReceiver(applicationContext)
        collectPlaybackState()
        playbackController.init()
        loudnessReceiver.init()
        isActive.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isActive.value = false
        playbackController.close()
        loudnessReceiver.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Baby Monitor Playback",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Playback status for Baby Monitor"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Baby Monitor")
            .setContentText("Playback service running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun collectPlaybackState() {
        serviceScope.launch {
            loudnessReceiver.lastReceived.collect { lastReceived.value = it }
        }
        serviceScope.launch {
            playbackController.playbackStatus.collect { playbackStatus.value = it }
        }
        serviceScope.launch {
            playbackController.isStreaming.collect { isStreaming.value = it }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback_service2"
        private const val NOTIFICATION_ID = 1001
        val lastReceived = MutableStateFlow<Float?>(null)
        val playbackStatus = MutableStateFlow("Idle")
        val isStreaming = MutableStateFlow(false)
        val isActive = MutableStateFlow(false)
    }
}
