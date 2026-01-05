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
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AutoStreamConfig
import horse.amazin.babymonitor.shared.AutoStreamConfigData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioStreamController: AudioStreamController
    private lateinit var notificationManager: NotificationManager
    private lateinit var dataClient: DataClient

    private val autoStreamConfigListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path == AutoStreamConfigData.PATH) {
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val threshold = dataMap.getFloat(AutoStreamConfigData.KEY_THRESHOLD_DB)
                        val duration = dataMap.getLong(AutoStreamConfigData.KEY_DURATION_MS)
                        AudioMonitorServiceState.updateAutoStreamConfig(
                            AutoStreamConfig(threshold, duration)
                        )
                    }
                    DataEvent.TYPE_DELETED -> {
                        AudioMonitorServiceState.updateAutoStreamConfig(null)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        dataClient = Wearable.getDataClient(this)
        dataClient.addListener(autoStreamConfigListener)
        audioStreamController = AudioStreamController(applicationContext).also { it.start() }
        audioStreamController.startMonitoring()
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
        dataClient.removeListener(autoStreamConfigListener)
        audioStreamController.stop()
        audioStreamController.stopMonitoring()
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
                if (streaming) {
                    audioStreamController.stopMonitoring()
                } else {
                    audioStreamController.startMonitoring()
                }
                updateNotification(currentNotificationText())
            }
        }
        serviceScope.launch {
            audioStreamController.currentLoudness.collect { loudness ->
                AudioMonitorServiceState.updateLoudness(loudness)
            }
        }
        serviceScope.launch {
            var aboveThresholdMs = 0L
            var lastSampleAt: Long? = null
            var pendingAutoStart = false
            var belowThresholdMs = 0L
            var lastBelowSampleAt: Long? = null
            audioStreamController.currentLoudness.collect { loudness ->
                val config = AudioMonitorServiceState.autoStreamConfig.value
                val isStreaming = audioStreamController.isStreaming.value
                if (config == null || loudness == null) {
                    aboveThresholdMs = 0
                    lastSampleAt = null
                    pendingAutoStart = false
                    belowThresholdMs = 0
                    lastBelowSampleAt = null
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (isStreaming) {
                    if (loudness < config.thresholdDb) {
                        val previousBelow = lastBelowSampleAt
                        if (previousBelow != null) {
                            belowThresholdMs += (now - previousBelow)
                        }
                        lastBelowSampleAt = now
                        if (belowThresholdMs >= config.durationMs) {
                            belowThresholdMs = 0
                            lastBelowSampleAt = null
                            audioStreamController.stopStreaming()
                        }
                    } else {
                        belowThresholdMs = 0
                        lastBelowSampleAt = null
                    }
                    return@collect
                }
                if (pendingAutoStart) {
                    if (loudness < config.thresholdDb) {
                        pendingAutoStart = false
                        aboveThresholdMs = 0
                        lastSampleAt = null
                    }
                    return@collect
                }
                if (loudness >= config.thresholdDb) {
                    val previous = lastSampleAt
                    if (previous != null) {
                        aboveThresholdMs += (now - previous)
                    }
                    lastSampleAt = now
                    if (aboveThresholdMs >= config.durationMs) {
                        pendingAutoStart = true
                        audioStreamController.startStreaming()
                    }
                } else {
                    aboveThresholdMs = 0
                    lastSampleAt = null
                }
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
