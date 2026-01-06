package horse.amazin.babymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private lateinit var playbackController: PlaybackController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        playbackController = PlaybackController(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        collectPlaybackState()
        playbackController.onStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playbackController.onStop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Baby Monitor Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback status for Baby Monitor"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Baby Monitor")
        .setContentText("Playback service running")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(buildContentIntent())
        .setOngoing(true)
        .build()

    private fun collectPlaybackState() {
        serviceScope.launch {
            playbackController.lastReceived.collect { lastReceived.value = it }
        }
        serviceScope.launch {
            playbackController.playbackStatus.collect { playbackStatus.value = it }
        }
        serviceScope.launch {
            playbackController.isPlaying.collect { isPlaying.value = it }
        }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback_service"
        private const val NOTIFICATION_ID = 1001
        val lastReceived = MutableStateFlow<Float?>(null)
        val playbackStatus = MutableStateFlow("Idle")
        val isPlaying = MutableStateFlow(false)
    }
}
