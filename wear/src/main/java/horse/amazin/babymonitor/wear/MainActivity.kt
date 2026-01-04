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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var dataClient: DataClient
    private lateinit var channelClient: ChannelClient
    private lateinit var nodeClient: NodeClient
    private var currentLoudness by mutableStateOf<Float?>(null)
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Baby Monitor (Wear)")
                        Text(
                            text = if (!hasMicPermission) {
                                "Mic permission required"
                            } else {
                                val loudness = currentLoudness
                                "Current loudness: ${loudness?.let { "%.1f".format(it) } ?: "--"} dB"
                            }
                        )
                        Text(text = "Stream: $streamStatus")
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
                                Text(text = if (isStreaming) "Stop streaming" else "Start streaming")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        currentLoudness = null
    }

    override fun onStop() {
        stopAudioStream()
        super.onStop()
    }

    override fun onDestroy() {
        channelClient.unregisterChannelCallback(channelCallback)
        super.onDestroy()
    }

    private fun sendLoudnessSample(db: Float, timestamp: Long) {
        val request = PutDataMapRequest.create(LoudnessData.PATH).apply {
            dataMap.putFloat(LoudnessData.KEY_DB, db)
            dataMap.putLong(LoudnessData.KEY_TIMESTAMP, timestamp)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
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
        var lastSentAt = 0L
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
                        val loudnessDb = calculateLoudnessDb(buffer, read)
                        val now = System.currentTimeMillis()
                        currentLoudness = loudnessDb
                        if (now - lastSentAt >= 500L) {
                            sendLoudnessSample(loudnessDb, now)
                            lastSentAt = now
                        }
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
        currentLoudness = null
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

    private fun calculateLoudnessDb(buffer: ByteArray, read: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
            val normalized = sample / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSquares / samples)
        val db = 20.0 * log10(rms.coerceAtLeast(1e-6))
        return db.toFloat()
    }

}
