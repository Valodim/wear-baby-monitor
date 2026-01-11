package horse.amazin.babymonitor.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.CHANNEL_PATH_SENDER_AUDIO
import horse.amazin.babymonitor.shared.MESSAGE_PATH_SENDER_LOUDNESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

class BabyMonitorSenderService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioStreamController: AudioStreamController

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val messageClient by lazy { Wearable.getMessageClient(applicationContext) }
    private val channelClient by lazy { Wearable.getChannelClient(applicationContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(applicationContext) }

    private var configNodeId: String? = null
    private var configThresholdDb: Float = -75.0f
    private var configDurationMs: Int = 1000

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

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        channelClient.registerChannelCallback(channelCallback)
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText(false)))
        _streamStatus.value = "Monitoring"

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configNodeId = intent.getStringExtra(EXTRA_NODE_ID)
                val configThresholdDb = intent.getFloatExtra(EXTRA_THRESHOLD_DB, -75.0f)
                val configDurationMs = intent.getIntExtra(EXTRA_MIN_DURATION_MS, 1000)
                initialize(configNodeId, configThresholdDb, configDurationMs)
            }

            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun initialize(configNodeId: String?, configThresholdDb: Float, configDurationMs: Int) {
        if (this.configNodeId != null) return

        this.configNodeId = configNodeId
        this.configThresholdDb = configThresholdDb
        this.configDurationMs = configDurationMs

        audioStreamController = AudioStreamController(applicationContext, this::onLoudnessSample)
        audioStreamController.initialize()

        observeControllerState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        channelClient.unregisterChannelCallback(channelCallback)
        serviceScope.cancel()

        _streamStatus.value = "Idle"
        _currentLoudness.value = null

        // We were never initialized, nothing to do here
        if (this.configNodeId == null) {
            return
        }

        onLoudnessSample(0.0f)

        audioStreamController.close()
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

                // Skip value until pending change is applied
                if (pendingChange) {
                    return@collect
                }

                val now = System.currentTimeMillis()

                if (loudness > configThresholdDb && aboveThresholdSince == null) {
                    aboveThresholdSince = now
                    belowThresholdSince = null
                    return@collect
                }

                if (loudness <= configThresholdDb && belowThresholdSince == null) {
                    aboveThresholdSince = null
                    belowThresholdSince = now
                    return@collect
                }

                if (streamingChannel != null) {
                    val currentBelowThreshold = belowThresholdSince
                    if (currentBelowThreshold != null) {
                        val duration = now - currentBelowThreshold
                        if (duration > configDurationMs * 2) {
                            closeStreamingChannel()
                        } else {
                            _streamStatus.value = "Streaming (%d/%d)".format(
                                duration / 100,
                                configDurationMs * 2 / 100
                            )
                        }
                        return@collect
                    }
                } else {
                    val currentAboveThreshold = aboveThresholdSince
                    if (currentAboveThreshold != null) {
                        val duration = now - currentAboveThreshold
                        if (duration > configDurationMs) {
                            openStreamingChannel()
                        } else {
                            _streamStatus.value =
                                "Monitoring (%d/%d)".format(duration / 100, configDurationMs / 100)
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
                val channel = channelClient.openChannel(node.id, CHANNEL_PATH_SENDER_AUDIO).await()
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

    private fun onLoudnessSample(db: Float) {
        val nodeId = configNodeId ?: return

        val messageData = ByteBuffer.allocate(4).apply {
            putFloat(db)
        }.array()

        messageClient.sendMessage(nodeId, MESSAGE_PATH_SENDER_LOUDNESS, messageData)
    }

    private fun currentNotificationText(isStreaming: Boolean): String {
        return if (isStreaming) {
            "Streaming audio"
        } else {
            "Idle"
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baby Monitor")
            .setContentIntent(contentIntent)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setLocalOnly(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Monitoring",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_NODE_ID = "node_id"
        private const val EXTRA_THRESHOLD_DB = "threshold_db"
        private const val EXTRA_MIN_DURATION_MS = "min_duration_ms"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        private const val CHANNEL_ID = "audio_monitoring3"
        private const val NOTIFICATION_ID = 1001

        private val _streamStatus = MutableStateFlow("Idle")
        val streamStatus: StateFlow<String> = _streamStatus.asStateFlow()

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

        private val _currentLoudness = MutableStateFlow<Float?>(null)
        val currentLoudness: StateFlow<Float?> = _currentLoudness.asStateFlow()

        fun start(context: Context, nodeId: String, thresholdDb: Float, durationMs: Int) {
            val intent = Intent(context, BabyMonitorSenderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_THRESHOLD_DB, thresholdDb)
                putExtra(EXTRA_MIN_DURATION_MS, durationMs)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BabyMonitorSenderService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}