package horse.amazin.babymonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.BabyMonitorSettings
import horse.amazin.babymonitor.shared.MESSAGE_PATH_RECEIVER_SET_ENABLED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.jvm.java

class BabyMonitorReceiverService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

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
        startForeground(NOTIFICATION_ID, buildNotification("Starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {}
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeNode.value?.let {
            sendStopMessage(it)
        }

        activeNode.value = null
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

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent =
            PendingIntent.getService(
                applicationContext,
                0,
                Intent(applicationContext, BabyMonitorReceiverService::class.java).apply {
                    action = ACTION_STOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Baby Monitor")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setLocalOnly(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    @OptIn(FlowPreview::class)
    private fun collectPlaybackState() {
        serviceScope.launch {
            loudnessReceiver.lastReceived.collect {
                lastReceived.value = it
            }
        }
        serviceScope.launch {
            loudnessReceiver.lastReceived.sample(1000).collect {
                updateNotification("Loudness: ${it ?: "--"}")
            }
        }
        serviceScope.launch {
            playbackController.playbackStatus.collect {
                playbackStatus.value = it
            }
        }
        serviceScope.launch {
            playbackController.isStreaming.collect {
                isStreaming.value = it
            }
        }
    }

    private fun sendStopMessage(node: Node) {
        val settings = Bundle().apply {
            putString(BabyMonitorSettings.KEY_ACTION, BabyMonitorSettings.ACTION_STOP)
        }

        val parcel = Parcel.obtain()
        val messageData = try {
            parcel.writeBundle(settings)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }

        val messageClient = Wearable.getMessageClient(applicationContext)
        messageClient.sendMessage(node.id, MESSAGE_PATH_RECEIVER_SET_ENABLED, messageData)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback_service2"
        private const val NOTIFICATION_ID = 1001
        val lastReceived = MutableStateFlow<Float?>(null)
        val playbackStatus = MutableStateFlow("Idle")
        val isStreaming = MutableStateFlow(false)
        val activeNode = MutableStateFlow<Node?>(null)

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val intent = Intent(context, BabyMonitorReceiverService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }
    }
}
