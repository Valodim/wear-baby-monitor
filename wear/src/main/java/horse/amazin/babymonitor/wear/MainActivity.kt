package horse.amazin.babymonitor.wear

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AudioStreamChannel
import horse.amazin.babymonitor.shared.LoudnessData
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var dataClient: DataClient
    private lateinit var channelClient: ChannelClient
    private lateinit var nodeClient: NodeClient
    private var lastSent by mutableStateOf<LoudnessReading?>(null)
    private var isStreaming by mutableStateOf(false)
    private var streamStatus by mutableStateOf("Idle")
    private var currentChannel: ChannelClient.Channel? = null
    private var outputStream: OutputStream? = null
    private var audioRecord: AudioRecord? = null
    private var streamThread: Thread? = null

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            if (channel == currentChannel) {
                updateStreamStatus("Channel closed")
                stopAudioStreamInternal()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataClient = Wearable.getDataClient(this)
        channelClient = Wearable.getChannelClient(this)
        nodeClient = Wearable.getNodeClient(this)
        channelClient.registerChannelCallback(channelCallback)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                var hasMicPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMicPermission = granted
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val loudnessText = if (!hasMicPermission) {
                        "Microphone permission required."
                    } else {
                        val reading = lastSent
                        if (reading == null) {
                            "Waiting to send loudness..."
                        } else {
                            "Last sent: ${"%.1f".format(reading.db)} dB\n${reading.formattedTime}"
                        }
                    }
                    val statusText = "Baby Monitor (Wear)\nAudio: $streamStatus\n$loudnessText"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = statusText)
                        if (!hasMicPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Text(text = "Grant microphone")
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                if (isStreaming) {
                                    stopAudioStream()
                                } else {
                                    startAudioStream()
                                }
                            }) {
                                Text(text = if (isStreaming) "Stop audio" else "Start audio")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasMicPermission()) {
            sendLoudnessSample()
        }
    }

    override fun onStop() {
        stopAudioStream()
        super.onStop()
    }

    override fun onDestroy() {
        channelClient.unregisterChannelCallback(channelCallback)
        super.onDestroy()
    }

    private fun sendLoudnessSample() {
        val db = Random.nextDouble(30.0, 80.0).toFloat()
        val timestamp = System.currentTimeMillis()
        val request = PutDataMapRequest.create(LoudnessData.PATH).apply {
            dataMap.putFloat(LoudnessData.KEY_DB, db)
            dataMap.putLong(LoudnessData.KEY_TIMESTAMP, timestamp)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
        lastSent = LoudnessReading(db, formatTimestamp(timestamp))
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAudioStream() {
        if (!hasMicPermission()) {
            updateStreamStatus("Microphone permission required")
            return
        }
        if (isStreaming) {
            return
        }
        isStreaming = true
        updateStreamStatus("Connecting...")
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    updateStreamStatus("No phone connected")
                    isStreaming = false
                    return@addOnSuccessListener
                }
                channelClient.openChannel(node.id, AudioStreamChannel.PATH)
                    .addOnSuccessListener { channel ->
                        currentChannel = channel
                        channelClient.getOutputStream(channel)
                            .addOnSuccessListener { stream ->
                                outputStream = stream
                                startRecording(stream)
                            }
                            .addOnFailureListener { error ->
                                updateStreamStatus("Output stream error: ${error.message}")
                                stopAudioStreamInternal()
                            }
                    }
                    .addOnFailureListener { error ->
                        updateStreamStatus("Channel open failed: ${error.message}")
                        isStreaming = false
                    }
            }
            .addOnFailureListener { error ->
                updateStreamStatus("Node lookup failed: ${error.message}")
                isStreaming = false
            }
    }

    private fun startRecording(stream: OutputStream) {
        val sampleRate = AudioStreamChannel.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBuffer, 2048)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            updateStreamStatus("AudioRecord init failed")
            stopAudioStreamInternal()
            return
        }
        audioRecord = recorder
        recorder.startRecording()
        updateStreamStatus("Streaming audio")
        streamThread = Thread {
            val buffer = ByteArray(bufferSize)
            try {
                while (isStreaming) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        stream.write(buffer, 0, read)
                        stream.flush()
                    }
                }
            } catch (error: IOException) {
                updateStreamStatus("Stream error: ${error.message}")
            } finally {
                runOnUiThread {
                    stopAudioStreamInternal()
                }
            }
        }.also { it.start() }
    }

    private fun stopAudioStream() {
        updateStreamStatus(if (isStreaming) "Stopping..." else "Idle")
        stopAudioStreamInternal()
        updateStreamStatus("Idle")
    }

    private fun stopAudioStreamInternal() {
        isStreaming = false
        streamThread?.interrupt()
        streamThread = null
        audioRecord?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // Ignore stop errors when recorder is not active.
            }
            release()
        }
        audioRecord = null
        outputStream?.run {
            try {
                close()
            } catch (_: IOException) {
                // Ignore close errors.
            }
        }
        outputStream = null
        val channel = currentChannel
        currentChannel = null
        if (channel != null) {
            channelClient.close(channel)
        }
    }

    private fun updateStreamStatus(message: String) {
        runOnUiThread {
            streamStatus = message
        }
    }

    private data class LoudnessReading(
        val db: Float,
        val formattedTime: String
    )
}
