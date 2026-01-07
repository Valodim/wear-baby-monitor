package horse.amazin.babymonitor.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AudioStreamChannel
import horse.amazin.babymonitor.shared.BabyMonitorConfig
import horse.amazin.babymonitor.shared.AutoStreamConfigData
import horse.amazin.babymonitor.shared.LoudnessData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AudioMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioStreamController: AudioStreamController

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val dataClient by lazy { Wearable.getDataClient(applicationContext) }
    private val channelClient by lazy { Wearable.getChannelClient(applicationContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(applicationContext) }

    private var streamingChannel: ChannelClient.Channel? = null

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            if (channel == streamingChannel) {
                closeStreamingChannel()
            }
        }
    }

    private val autoStreamConfigListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path == AutoStreamConfigData.PATH) {
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> {
                        // updateConfig(event.dataItem)
                    }

                    DataEvent.TYPE_DELETED -> {
                        BabyMonitorConfigState.updateAutoStreamConfig(null)
                    }
                }
            }
        }
    }

    private fun updateConfig(dataItem: DataItem) {
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val threshold = dataMap.getFloat(AutoStreamConfigData.KEY_THRESHOLD_DB)
        val duration = dataMap.getInt(AutoStreamConfigData.KEY_MIN_DURATION_MS)
        BabyMonitorConfigState.updateAutoStreamConfig(
            BabyMonitorConfig(threshold, duration)
        )
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        dataClient.addListener(autoStreamConfigListener)

        BabyMonitorConfigState.updateAutoStreamConfig(
            BabyMonitorConfig(-75f, 1000)
        )

//        serviceScope.launch {
//            val dataItems = dataClient.dataItems.await()
//            val configData = dataItems.find { it.uri.path == AutoStreamConfigData.PATH }
//            if (configData != null) {
//                updateConfig(configData)
//            } else {
//                AudioMonitorServiceState.updateAutoStreamConfig(
//                    AutoStreamConfig(-75f, 500)
//                )
//            }
//            dataItems.release()
//        }

        audioStreamController = AudioStreamController(applicationContext, this::onLoudnessSample)
        audioStreamController.initialize()

        channelClient.registerChannelCallback(channelCallback)

        observeControllerState()

        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText(false)))

        _streamStatus.value = "Monitoring"
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
        super.onDestroy()
        dataClient.removeListener(autoStreamConfigListener)
        channelClient.unregisterChannelCallback(channelCallback)
        audioStreamController.close()
        serviceScope.cancel()
        _streamStatus.value = "Idle"
         _currentLoudness.value = null
    }

    var belowThresholdSince: Long? = null
    var aboveThresholdSince: Long? = null
    var pendingChange = false

    private fun observeControllerState() {
        serviceScope.launch {
            isStreaming.collect { isStreaming ->
                updateNotification(currentNotificationText(isStreaming))
            }
        }
        serviceScope.launch {
            audioStreamController.currentLoudness.collect { loudness ->
                _currentLoudness.value = loudness
            }
        }
        serviceScope.launch(Dispatchers.Default) {
            audioStreamController.currentLoudness.collect { loudness ->
                loudness ?: return@collect

                val config = BabyMonitorConfigState.babyMonitorConfig.value ?: return@collect

                // Skip value until pending change is applied
                if (pendingChange) {
                    return@collect
                }

                val now = System.currentTimeMillis()

                if (loudness > config.thresholdDb && aboveThresholdSince == null) {
                    aboveThresholdSince = now
                    belowThresholdSince = null
                    return@collect
                }

                if (loudness <= config.thresholdDb && belowThresholdSince == null) {
                    aboveThresholdSince = null
                    belowThresholdSince = now
                    return@collect
                }

                if (streamingChannel != null) {
                    val currentBelowThreshold = belowThresholdSince
                    if (currentBelowThreshold != null) {
                        val duration = now - currentBelowThreshold
                        if (duration > config.durationMs * 3) {
                            closeStreamingChannel()
                        } else {
                            _streamStatus.value = "Streaming (%d/%d)".format(duration / 100, config.durationMs * 3 / 100)
                        }
                        return@collect
                    }
                } else {
                    val currentAboveThreshold = aboveThresholdSince
                    if (currentAboveThreshold != null) {
                        val duration = now - currentAboveThreshold
                        if (duration > config.durationMs) {
                            openStreamingChannel()
                        } else {
                            _streamStatus.value = "Monitoring (%d/%d)".format(duration / 100, config.durationMs / 100)
                        }
                    }
                }
            }
        }
    }

    private fun openStreamingChannel() {
        if (streamingChannel != null) {
            return
        }
        pendingChange = true
        serviceScope.launch(Dispatchers.Main) {
            try {
                val node = nodeClient.connectedNodes.await().firstOrNull() ?: return@launch
                val channel = channelClient.openChannel(node.id, AudioStreamChannel.PATH).await()
                val outputStream = channelClient.getOutputStream(channel).await()
                audioStreamController.startStreaming(outputStream)
                streamingChannel = channel

                _isStreaming.value = true
                _streamStatus.value = "Streaming audio.."
            } catch (e: Exception) {
                Log.e("AudioMonitorService", "Failed initializing channel", e)
            } finally {
                pendingChange = false
            }
        }
    }

    private fun closeStreamingChannel() {
        audioStreamController.stopStreaming()
        streamingChannel = null
        _isStreaming.value = false
        _streamStatus.value = "Monitoring"
    }

    private fun onLoudnessSample(db: Float, timestamp: Long) {
        val request = PutDataMapRequest.create(LoudnessData.PATH).apply {
            dataMap.putFloat(LoudnessData.KEY_DB, db)
            dataMap.putLong(LoudnessData.KEY_TIMESTAMP, timestamp)
        }.asPutDataRequest()
        dataClient.putDataItem(request)
    }

    private fun currentNotificationText(isStreaming: Boolean): String {
        return if (isStreaming) {
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Monitoring",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "audio_monitoring2"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        private val _streamStatus = MutableStateFlow("Idle")
        val streamStatus: StateFlow<String> = _streamStatus.asStateFlow()

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

        private val _currentLoudness = MutableStateFlow<Float?>(null)
        val currentLoudness: StateFlow<Float?> = _currentLoudness.asStateFlow()
    }
}